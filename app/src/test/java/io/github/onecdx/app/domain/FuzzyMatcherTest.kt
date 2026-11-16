package io.github.onecdx.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatcherTest {

    private fun file(path: String) = FileEntry(path, path.substringAfterLast('/'), false, 0, path)

    @Test
    fun matchesSubsequenceIgnoringCase() {
        assertNotNull(FuzzyMatcher.score("mgo", "cmd/server/Main.go"))
        assertNull(FuzzyMatcher.score("xyz", "cmd/server/main.go"))
    }

    @Test
    fun prefersExactFileNameOverPathMatch() {
        val files = listOf(file("main/go/helpers.txt"), file("src/main.go"), file("main.go.bak"))
        assertEquals("src/main.go", FuzzyMatcher.filter("main.go", files).first().path)
    }

    @Test
    fun emptyQueryReturnsShortestPathsFirst() {
        val files = listOf(file("a/b/c.txt"), file("x.txt"))
        assertEquals(listOf("x.txt", "a/b/c.txt"), FuzzyMatcher.filter("", files).map { it.path })
    }

    @Test
    fun respectsLimit() {
        val files = (1..100).map { file("f$it.kt") }
        assertEquals(10, FuzzyMatcher.filter("f", files, limit = 10).size)
    }
}
