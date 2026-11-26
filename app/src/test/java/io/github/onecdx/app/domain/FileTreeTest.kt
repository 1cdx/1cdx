package io.github.onecdx.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTreeTest {

    private fun dir(id: String) = FileEntry(id, id.substringAfterLast('/'), true, 0, id)
    private fun file(id: String) = FileEntry(id, id.substringAfterLast('/'), false, 0, id)

    @Test
    fun sortsFoldersFirstThenNameCaseInsensitive() {
        val sorted = sortEntries(listOf(file("b.txt"), dir("src"), file("A.txt"), dir("Docs")))
        assertEquals(listOf("Docs", "src", "A.txt", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun flattensOnlyExpandedFolders() {
        val children = mapOf(
            "root" to listOf(dir("src"), dir("docs"), file("README.md")),
            "src" to listOf(dir("src/app"), file("src/main.go")),
            "src/app" to listOf(file("src/app/x.go")),
            "docs" to listOf(file("docs/a.md")),
        )
        val rows = flattenTree("root", children, expanded = setOf("src"), loading = setOf("docs"))
        assertEquals(listOf("src", "src/app", "src/main.go", "docs", "README.md"), rows.map { it.entry.id })
        assertEquals(listOf(0, 1, 1, 0, 0), rows.map { it.depth })
        assertEquals(true, rows.first { it.entry.id == "docs" }.loading)
        assertEquals(false, rows.first { it.entry.id == "src/app" }.expanded)
    }
}
