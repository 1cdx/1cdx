package io.github.onecdx.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun joinsAndSplitsPaths() {
        assertEquals("/var/www/app", RemotePath.join("/var/www", "app"))
        assertEquals("/app", RemotePath.join("/", "app"))
        assertEquals("/var/www", RemotePath.parent("/var/www/app"))
        assertEquals("/", RemotePath.parent("/var"))
        assertEquals("/", RemotePath.parent("/"))
        assertEquals("app", RemotePath.name("/var/www/app/"))
        assertEquals("/", RemotePath.name("/"))
    }

    @Test
    fun relativePaths() {
        assertEquals("app/main.go", RemotePath.relativeTo("/var/www", "/var/www/app/main.go"))
        assertEquals("", RemotePath.relativeTo("/var/www", "/var/www"))
        assertNull(RemotePath.relativeTo("/var/www", "/var/wwwx/a"))
        assertEquals("etc", RemotePath.relativeTo("/", "/etc"))
    }

    @Test
    fun validatesNames() {
        assertNull(RemotePath.invalidNameReason("main.go"))
        assertNull(RemotePath.invalidNameReason(".env"))
        listOf("", " ", ".", "..", "a/b", " a", "a\u0000").forEach {
            assertNotNull("'$it' harus ditolak", RemotePath.invalidNameReason(it))
        }
    }

    @Test
    fun cacheSegmentsCannotEscapeCacheFolder() {
        assertEquals(listOf("var", "www", "a.php"), RemotePath.cacheSegments("/var/www/a.php"))
        assertEquals(listOf("etc", "passwd"), RemotePath.cacheSegments("/../../etc/./passwd"))
        assertEquals(emptyList<String>(), RemotePath.cacheSegments("/"))
    }

    @Test
    fun insideChecksUseRelativePaths() {
        val dir = FileEntry("/srv/app", "app", true, 0, "app")
        assertTrue(isSameOrInside(FileEntry("/srv/app/a.go", "a.go", false, 0, "app/a.go"), dir))
        assertTrue(isSameOrInside(dir, dir))
        assertFalse(isSameOrInside(FileEntry("/srv/apple/a.go", "a.go", false, 0, "apple/a.go"), dir))
    }
}
