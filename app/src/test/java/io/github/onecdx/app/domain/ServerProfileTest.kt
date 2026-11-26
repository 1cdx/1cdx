package io.github.onecdx.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileTest {

    private val valid = ServerProfile(host = "example.com", username = "deploy", password = "pw")

    @Test
    fun validProfileHasNoErrors() {
        assertEquals(emptyList<String>(), valid.validate())
    }

    @Test
    fun reportsMissingFields() {
        val errors = ServerProfile().validate()
        assertTrue(errors.contains("Host wajib diisi"))
        assertTrue(errors.contains("Username wajib diisi"))
        assertTrue(errors.contains("Password wajib diisi"))
        assertTrue(valid.copy(auth = AuthMethod.KEY).validate().contains("Pilih file private key"))
        assertTrue(valid.copy(host = "a b").validate().contains("Host tidak valid"))
        assertTrue(valid.copy(remotePath = "var/www").validate().isNotEmpty())
        assertTrue(valid.copy(remotePath = "~/app").validate().isEmpty())
    }

    @Test
    fun addressOmitsDefaultPort() {
        assertEquals("deploy@example.com", valid.address)
        assertEquals("deploy@example.com:2222", valid.copy(port = 2222).address)
        assertEquals("deploy@example.com", valid.displayName)
    }

    @Test
    fun duplicateGetsNewIdAndName() {
        val copy = valid.copy(name = "VPS").duplicate()
        assertNotEquals(valid.id, copy.id)
        assertEquals("VPS (salinan)", copy.name)
    }

    @Test
    fun detectsTargetChange() {
        assertTrue(valid.copy(port = 2222).targetChangedFrom(valid))
        assertFalse(valid.copy(name = "x", remotePath = "/srv").targetChangedFrom(valid))
    }

    @Test
    fun codecRoundTripsAndFillsDefaults() {
        val profiles = listOf(valid.copy(hostKey = "ssh-ed25519 AAAA"), valid.copy(id = "b", auth = AuthMethod.KEY, privateKey = "k"))
        assertEquals(profiles, ProfileCodec.decode(ProfileCodec.encode(profiles)))

        // Data lama / tidak lengkap: field kosong diisi default, bukan null
        val decoded = ProfileCodec.decode("""[{"id":"x","host":" h ","username":"u"}]""").single()
        assertEquals("h", decoded.host)
        assertEquals(22, decoded.port)
        assertEquals(AuthMethod.PASSWORD, decoded.auth)
        assertEquals("", decoded.name)
        assertEquals("", decoded.remotePath)
        assertNull(decoded.password)
        assertEquals(emptyList<ServerProfile>(), ProfileCodec.decode(""))
    }
}
