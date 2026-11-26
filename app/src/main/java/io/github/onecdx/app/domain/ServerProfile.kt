package io.github.onecdx.app.domain

import java.util.UUID

enum class AuthMethod { PASSWORD, KEY }

/**
 * Profil koneksi SFTP. Disimpan terenkripsi (lihat `data/ProfileStore`), tidak pernah plain text.
 *
 * Field bertipe nullable/berdefault karena Gson tidak memanggil konstruktor Kotlin;
 * pakai [normalized] setelah deserialisasi.
 *
 * @param privateKey isi file private key (PEM / OpenSSH), bukan path.
 * @param hostKey host key server yang sudah dipercaya pengguna, format `<tipe> <base64>`.
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val auth: AuthMethod = AuthMethod.PASSWORD,
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyName: String? = null,
    val passphrase: String? = null,
    val remotePath: String = "",
    val hostKey: String? = null,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }
    val address: String get() = if (port == DEFAULT_PORT) "$username@$host" else "$username@$host:$port"

    /** Mengisi field yang null setelah dibaca Gson dan merapikan spasi. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    fun normalized(): ServerProfile = copy(
        id = (id ?: "").ifBlank { UUID.randomUUID().toString() },
        name = (name ?: "").trim(),
        host = (host ?: "").trim(),
        port = if (port in 1..65535) port else DEFAULT_PORT,
        username = (username ?: "").trim(),
        auth = auth ?: AuthMethod.PASSWORD,
        remotePath = (remotePath ?: "").trim(),
    )

    /** Daftar kesalahan isian; kosong berarti profil siap dipakai. */
    fun validate(): List<String> = buildList {
        if (host.isBlank()) add("Host wajib diisi")
        else if (host.any { it.isWhitespace() || it == '/' }) add("Host tidak valid")
        if (port !in 1..65535) add("Port harus 1–65535")
        if (username.isBlank()) add("Username wajib diisi")
        when (auth) {
            AuthMethod.PASSWORD -> if (password.isNullOrEmpty()) add("Password wajib diisi")
            AuthMethod.KEY -> if (privateKey.isNullOrBlank()) add("Pilih file private key")
        }
        if (remotePath.isNotEmpty() && !remotePath.startsWith("/") && !remotePath.startsWith("~")) {
            add("Remote path harus diawali / atau ~")
        }
    }

    /** Salinan untuk fitur "Duplikat": id baru, host key ikut (server yang sama). */
    fun duplicate(): ServerProfile = copy(id = UUID.randomUUID().toString(), name = "$displayName (salinan)")

    /** Profil sama tapi target koneksi berubah, sehingga host key lama tidak berlaku lagi. */
    fun targetChangedFrom(old: ServerProfile): Boolean = host != old.host || port != old.port

    companion object {
        const val DEFAULT_PORT = 22
    }
}
