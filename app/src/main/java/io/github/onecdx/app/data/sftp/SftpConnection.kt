package io.github.onecdx.app.data.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.github.onecdx.app.domain.AuthMethod
import io.github.onecdx.app.domain.ConnectionState
import io.github.onecdx.app.domain.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64

/** Host key yang dikirim server saat handshake. [entry] berformat `<tipe> <base64>`. */
data class PresentedHostKey(val type: String, val entry: String, val fingerprint: String)

/** Server belum pernah dipercaya: tanyakan fingerprint ke pengguna. */
class HostKeyUnknownException(val key: PresentedHostKey) :
    IOException("Host key server belum dikenal (${key.type} ${key.fingerprint})")

/** Host key server berbeda dari yang dipercaya sebelumnya: kemungkinan serangan man-in-the-middle. */
class HostKeyChangedException(val key: PresentedHostKey?) :
    IOException("Host key server BERUBAH. Koneksi ditolak demi keamanan.")

class SftpOperationException(message: String, cause: Throwable?) : IOException(message, cause)

/**
 * Satu koneksi SSH + channel SFTP untuk sebuah profil.
 *
 * Setiap operasi dijalankan lewat [run]: serial (JSch tidak aman dipakai paralel di satu channel),
 * di [Dispatchers.IO], dan didahului [ensureConnected]. Jika koneksi ternyata putus di tengah
 * operasi (layar redup, pindah jaringan), sesi dibuka ulang dan operasi diulang satu kali.
 */
class SftpConnection(profile: ServerProfile) {

    @Volatile
    var profile: ServerProfile = profile
        private set

    private val mutex = Mutex()
    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private var everConnected = false

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected(profile.address, null))
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Dipakai setelah pengguna mempercayai host key baru. */
    fun updateProfile(updated: ServerProfile) {
        profile = updated
    }

    suspend fun <T> run(block: (ChannelSftp) -> T): T = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            val first = ensureConnected()
            try {
                block(first)
            } catch (e: Exception) {
                if (!isConnectionLost(e)) throw translate(e)
                disconnectQuietly()
                val second = ensureConnected()
                try {
                    block(second)
                } catch (retry: Exception) {
                    if (isConnectionLost(retry)) {
                        disconnectQuietly()
                        _state.value = ConnectionState.Disconnected(profile.address, describe(retry))
                    }
                    throw translate(retry)
                }
            }
        }
    }

    /** Membuka koneksi (bila belum) tanpa menjalankan operasi apa pun; untuk "Test Connection". */
    suspend fun connect(): String = run { it.pwd() }

    /** Memutus koneksi tanpa memblokir pemanggil. */
    fun close() {
        val s = session
        val c = channel
        session = null
        channel = null
        _state.value = ConnectionState.Disconnected(profile.address, null)
        if (s == null && c == null) return
        Thread({
            runCatching { c?.disconnect() }
            runCatching { s?.disconnect() }
        }, "sftp-close").start()
    }

    // ------------------------------------------------------------------ Internal

    /** README §4.D: cek sesi sebelum setiap perintah, sambung ulang bila perlu. */
    private fun ensureConnected(): ChannelSftp {
        val s = session
        val c = channel
        if (s != null && s.isConnected && c != null && c.isConnected && !c.isClosed) return c
        disconnectQuietly()
        val current = profile
        _state.value = ConnectionState.Connecting(current.address, reconnect = everConnected)
        try {
            val (newSession, newChannel) = open(current)
            session = newSession
            channel = newChannel
            everConnected = true
            _state.value = ConnectionState.Connected(current.address)
            return newChannel
        } catch (e: Exception) {
            val error = translate(e)
            _state.value = ConnectionState.Disconnected(current.address, error.message)
            throw error
        }
    }

    private fun open(p: ServerProfile): Pair<Session, ChannelSftp> {
        val jsch = JSch()
        if (p.auth == AuthMethod.KEY) addIdentity(jsch, p)
        val hostKeys = TrustedHostKeyRepository(p.hostKey)
        jsch.setHostKeyRepository(hostKeys)

        val s = jsch.getSession(p.username, p.host, p.port)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig(
            "PreferredAuthentications",
            if (p.auth == AuthMethod.KEY) "publickey" else "password,keyboard-interactive",
        )
        if (p.auth == AuthMethod.PASSWORD) s.setPassword(p.password.orEmpty())
        s.setUserInfo(SilentUserInfo(if (p.auth == AuthMethod.PASSWORD) p.password else null))
        s.setServerAliveInterval(KEEP_ALIVE_MS)
        s.setServerAliveCountMax(KEEP_ALIVE_COUNT)
        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchUnknownHostKeyException) {
            throw hostKeys.presented?.let(::HostKeyUnknownException) ?: e
        } catch (e: JSchChangedHostKeyException) {
            throw HostKeyChangedException(hostKeys.presented)
        }
        try {
            val c = s.openChannel("sftp") as ChannelSftp
            c.connect(CONNECT_TIMEOUT_MS)
            return s to c
        } catch (e: Exception) {
            s.disconnect()
            throw e
        }
    }

    private fun addIdentity(jsch: JSch, p: ServerProfile) {
        val keyBytes = p.privateKey.orEmpty().toByteArray(Charsets.UTF_8)
        val passphrase = p.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val pair = try {
            KeyPair.load(jsch, keyBytes, null)
        } catch (e: JSchException) {
            throw SftpOperationException("Private key tidak bisa dibaca: ${e.message}", e)
        }
        try {
            if (pair.isEncrypted) {
                if (passphrase == null) throw SftpOperationException("Private key dilindungi passphrase. Isi passphrase di profil.", null)
                if (!pair.decrypt(passphrase)) throw SftpOperationException("Passphrase private key salah", null)
            }
        } finally {
            pair.dispose()
        }
        jsch.addIdentity(p.displayName, keyBytes, null, passphrase)
    }

    private fun isConnectionLost(e: Exception): Boolean {
        if (e is SftpException && e.id == ChannelSftp.SSH_FX_CONNECTION_LOST) return true
        val s = session
        val c = channel
        return s == null || !s.isConnected || c == null || c.isClosed || !c.isConnected
    }

    private fun disconnectQuietly() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }

    private fun translate(e: Exception): IOException = when (e) {
        is HostKeyUnknownException, is HostKeyChangedException, is SftpOperationException -> e as IOException
        else -> SftpOperationException(describe(e), e)
    }

    private fun describe(e: Throwable): String {
        val p = profile
        val root = generateSequence(e) { it.cause }.last()
        return when {
            e is SftpException -> when (e.id) {
                ChannelSftp.SSH_FX_NO_SUCH_FILE -> "File atau folder tidak ditemukan"
                ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Akses ditolak server"
                ChannelSftp.SSH_FX_CONNECTION_LOST -> "Koneksi ke server terputus"
                else -> e.message ?: "Operasi SFTP gagal (kode ${e.id})"
            }
            root is UnknownHostException -> "Host tidak ditemukan: ${p.host}"
            root is ConnectException -> "Koneksi ditolak di ${p.host}:${p.port}"
            root is NoRouteToHostException -> "Tidak ada rute ke ${p.host}"
            root is SocketTimeoutException || e.message?.contains("timeout", ignoreCase = true) == true ->
                "Waktu koneksi ke ${p.host} habis"
            e.message?.startsWith("Auth fail") == true || e.message?.startsWith("Auth cancel") == true ->
                "Autentikasi gagal: username, password, atau key ditolak server"
            else -> e.message ?: root.message ?: e.javaClass.simpleName
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val KEEP_ALIVE_MS = 15_000
        const val KEEP_ALIVE_COUNT = 3
    }
}

/** Fingerprint untuk host key tersimpan (`<tipe> <base64>`), atau null bila formatnya rusak. */
fun fingerprintOf(hostKeyEntry: String): String? {
    val parts = hostKeyEntry.split(' ', limit = 2)
    if (parts.size != 2) return null
    return try {
        "${parts[0]} " + sha256Fingerprint(Base64.getDecoder().decode(parts[1]))
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Fingerprint gaya OpenSSH: `SHA256:<base64 tanpa padding>`. */
internal fun sha256Fingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

/**
 * Known-hosts berisi tepat satu kunci: yang tersimpan di profil.
 * Kunci yang dikirim server dicatat di [presented] agar bisa ditampilkan ke pengguna.
 */
private class TrustedHostKeyRepository(private val trusted: String?) : HostKeyRepository {

    @Volatile
    var presented: PresentedHostKey? = null

    override fun check(host: String, key: ByteArray): Int {
        val hostKey = HostKey(host, key)
        val entry = "${hostKey.type} ${hostKey.key}"
        presented = PresentedHostKey(hostKey.type, entry, sha256Fingerprint(key))
        return when (trusted) {
            null -> HostKeyRepository.NOT_INCLUDED
            entry -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "1cdx"
    override fun getHostKey(): Array<HostKey> = trustedKeys(null)

    // Dipakai JSch untuk mendahulukan tipe host key yang sudah dikenal saat negosiasi.
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = trustedKeys(host, type)

    private fun trustedKeys(host: String?, type: String? = null): Array<HostKey> {
        val parts = trusted?.split(' ', limit = 2)?.takeIf { it.size == 2 } ?: return emptyArray()
        if (type != null && type != parts[0]) return emptyArray()
        return try {
            arrayOf(HostKey(host ?: "", Base64.getDecoder().decode(parts[1])))
        } catch (e: Exception) {
            emptyArray()
        }
    }
}

/** UserInfo tanpa dialog: password dipakai juga untuk keyboard-interactive, sisanya ditolak. */
private class SilentUserInfo(private val password: String?) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = password
    override fun promptPassword(message: String?): Boolean = password != null
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<String>?,
        echo: BooleanArray?,
    ): Array<String>? {
        val pw = password ?: return null
        // Umumnya satu prompt "Password:"; prompt lain (mis. OTP) tidak didukung.
        return if (prompt != null && prompt.size == 1 && echo?.firstOrNull() == false) arrayOf(pw) else null
    }
}
