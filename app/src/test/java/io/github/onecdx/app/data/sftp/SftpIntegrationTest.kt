package io.github.onecdx.app.data.sftp

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import io.github.onecdx.app.domain.AuthMethod
import io.github.onecdx.app.domain.ConnectionState
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.ServerProfile
import io.github.onecdx.app.domain.UploadFailedException
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Uji end-to-end terhadap server SSH/SFTP sungguhan (Apache MINA SSHD) di JVM.
 * Algoritma JSch dipaksa ke implementasi BouncyCastle seperti yang terjadi di Android.
 */
class SftpIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverRoot: File
    private lateinit var hostKeyFile: File
    private lateinit var cacheDir: File
    private var server: SshServer? = null
    private var port = 0
    private val opened = ArrayList<SftpConnection>()

    @Before
    fun setUp() {
        serverRoot = tmp.newFolder("server")
        hostKeyFile = File(tmp.root, "hostkey.ser")
        cacheDir = tmp.newFolder("cache")
        startServer()
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        server?.stop(true)
    }

    private fun startServer(keyFile: File = hostKeyFile, bindPort: Int = port): SshServer {
        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = bindPort
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath())
        sshd.passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        sshd.publickeyAuthenticator = PublickeyAuthenticator { user, key, _ ->
            user == USER && KeyUtils.getKeyType(key) in setOf(KeyPairTypes.ED25519, KeyPairTypes.RSA)
        }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(serverRoot.toPath())
        sshd.start()
        if (keyFile == hostKeyFile) {
            server = sshd
            port = sshd.port
        }
        return sshd
    }

    private fun profile(
        hostKey: String? = null,
        password: String = PASSWORD,
        serverPort: Int = port,
    ) = ServerProfile(
        id = "test-server",
        name = "Test",
        host = "127.0.0.1",
        port = serverPort,
        username = USER,
        password = password,
        hostKey = hostKey,
    )

    private fun connection(p: ServerProfile) = SftpConnection(p).also { opened += it }

    private fun trustedHostKey(): String = runBlocking {
        try {
            SftpWorkspaceRepository.open(connection(profile()), cacheDir)
            fail("Host key baru harus ditanyakan dulu")
            error("unreachable")
        } catch (e: HostKeyUnknownException) {
            assertTrue(e.key.fingerprint.startsWith("SHA256:"))
            e.key.entry
        }
    }

    private fun openRepo(p: ServerProfile = profile(hostKey = trustedHostKey())) = runBlocking {
        SftpWorkspaceRepository.open(connection(p), cacheDir)
    }

    @Test
    fun unknownHostKeyIsRejectedUntilTrusted() {
        val key = trustedHostKey()
        val repo = openRepo(profile(hostKey = key))
        assertEquals("/", repo.root.id)
        assertTrue(repo.connection.value is ConnectionState.Connected)
    }

    @Test
    fun changedHostKeyIsRejected() {
        val trusted = trustedHostKey()
        val impostor = startServer(keyFile = File(tmp.root, "other-hostkey.ser"), bindPort = 0)
        try {
            runBlocking {
                SftpWorkspaceRepository.open(connection(profile(hostKey = trusted, serverPort = impostor.port)), cacheDir)
            }
            fail("Host key berbeda harus ditolak")
        } catch (e: HostKeyChangedException) {
            assertNotEquals(trusted, e.key?.entry)
        } finally {
            impostor.stop(true)
        }
    }

    @Test
    fun wrongPasswordGivesReadableError() {
        val key = trustedHostKey()
        try {
            openRepo(profile(hostKey = key, password = "salah"))
            fail("Password salah harus gagal")
        } catch (e: SftpOperationException) {
            assertTrue(e.message, e.message!!.startsWith("Autentikasi gagal"))
        }
    }

    @Test
    fun fileOperationsRoundTrip() = runBlocking {
        val repo = openRepo()
        val src = repo.createDirectory(repo.root, "src")
        val main = repo.createFile(src, "main.go")
        assertEquals("src/main.go", main.path)
        assertTrue(File(serverRoot, "src/main.go").exists())

        repo.writeText(main, "package main\n")
        assertEquals("package main\n", File(serverRoot, "src/main.go").readText())
        assertTrue(File(cacheDir, "sftp_workspaces/test-server/src/main.go").exists())

        val rootList = repo.list(repo.root)
        assertEquals(listOf("src"), rootList.map { it.name })
        assertTrue(rootList.single().isDirectory)
        assertEquals(listOf("main.go"), repo.list(src).map { it.name })

        File(serverRoot, "src/main.go").writeText("// diubah di server\n")
        assertEquals("// diubah di server\n", repo.readText(main))

        try {
            repo.createFile(src, "main.go")
            fail("File yang sudah ada tidak boleh ditimpa")
        } catch (e: SftpOperationException) {
            assertTrue(e.message!!.contains("sudah ada"))
        }

        val renamed = repo.rename(main, "app.go")
        assertEquals("/src/app.go", renamed.id)
        assertEquals("src/app.go", renamed.path)
        assertFalse(File(serverRoot, "src/main.go").exists())
        assertTrue(File(serverRoot, "src/app.go").exists())

        repo.createDirectory(src, "nested")
        repo.writeText(repo.createFile(FileEntry("/src/nested", "nested", true, 0, "src/nested"), "x.txt"), "x")
        repo.delete(src)
        assertFalse(File(serverRoot, "src").exists())
    }

    @Test
    fun failedUploadStaysInCacheAndSyncsAfterReconnect() = runBlocking {
        val repo = openRepo()
        val file = repo.createFile(repo.root, "index.php")
        repo.writeText(file, "v1")

        server!!.stop(true)
        try {
            repo.writeText(file, "v2")
            fail("Upload harus gagal saat server mati")
        } catch (e: UploadFailedException) {
            assertTrue(e.message!!.startsWith("Tersimpan di cache lokal"))
        }
        assertTrue(repo.hasUnsyncedChanges(file))
        // Versi lokal tidak tertimpa versi server dan tetap bisa dibuka tanpa koneksi
        assertEquals("v2", repo.readText(file))
        assertEquals("v1", File(serverRoot, "index.php").readText())

        startServer()
        repo.writeText(file, "v3")
        assertEquals("v3", File(serverRoot, "index.php").readText())
        assertFalse(repo.hasUnsyncedChanges(file))
        assertTrue(repo.connection.value is ConnectionState.Connected)
    }

    @Test
    fun reconnectsTransparentlyAfterConnectionDrop() = runBlocking {
        val repo = openRepo()
        repo.createFile(repo.root, "a.txt")
        server!!.stop(true)
        startServer()
        // Sesi lama sudah mati; operasi berikutnya menyambung ulang sendiri
        assertEquals(listOf("a.txt"), repo.list(repo.root).map { it.name })
    }

    @Test
    fun ed25519KeyAuthentication() = runBlocking {
        val key = trustedHostKey()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        val pem = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        pair.dispose()
        val repo = openRepo(
            profile(hostKey = key).copy(auth = AuthMethod.KEY, password = null, privateKey = pem),
        )
        assertEquals("/", repo.root.id)
    }

    @Test
    fun encryptedKeyNeedsCorrectPassphrase() {
        val key = trustedHostKey()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val pem = ByteArrayOutputStream().also { pair.writePrivateKey(it, "rahasia".toByteArray()) }
            .toString(Charsets.UTF_8.name())
        pair.dispose()
        val base = profile(hostKey = key).copy(auth = AuthMethod.KEY, password = null, privateKey = pem)

        for ((passphrase, expected) in listOf(null to "dilindungi passphrase", "keliru" to "Passphrase private key salah")) {
            try {
                openRepo(base.copy(passphrase = passphrase))
                fail("Harus gagal dengan passphrase $passphrase")
            } catch (e: SftpOperationException) {
                assertTrue(e.message, e.message!!.contains(expected))
            }
        }
        assertEquals("/", openRepo(base.copy(passphrase = "rahasia")).root.id)
    }

    private object KeyPairTypes {
        const val ED25519 = "ssh-ed25519"
        const val RSA = "ssh-rsa"
    }

    companion object {
        private const val USER = "dev"
        private const val PASSWORD = "secret"

        /** Di Android JavaVersion JSch = 8, sehingga semua algoritma ini memakai BouncyCastle. */
        @BeforeClass
        @JvmStatic
        fun useBouncyCastleLikeAndroid() {
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("mlkem768", "com.jcraft.jsch.bc.MLKEM768")
            JSch.setConfig("mlkem1024", "com.jcraft.jsch.bc.MLKEM1024")
        }
    }
}
