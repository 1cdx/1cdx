package io.github.onecdx.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanguageResolverTest {

    @Test
    fun resolvesByExtensionCaseInsensitive() {
        assertEquals("go", LanguageResolver.resolve("main.go").id)
        assertEquals("python", LanguageResolver.resolve("APP.PY").id)
        assertEquals("kotlin", LanguageResolver.resolve("build.gradle.kts").id)
        assertEquals("typescript", LanguageResolver.resolve("index.tsx").id)
        assertEquals("shellscript", LanguageResolver.resolve("deploy.sh").id)
        assertEquals("html", LanguageResolver.resolve("index.html").id)
    }

    @Test
    fun resolvesDotfilesByName() {
        assertEquals("shellscript", LanguageResolver.resolve(".bashrc").id)
    }

    @Test
    fun unknownOrExtensionlessIsPlainText() {
        assertEquals(LanguageResolver.PLAIN_TEXT, LanguageResolver.resolve("Makefile"))
        assertEquals(LanguageResolver.PLAIN_TEXT, LanguageResolver.resolve(".gitignore"))
        assertEquals(LanguageResolver.PLAIN_TEXT, LanguageResolver.resolve("notes."))
        assertEquals(LanguageResolver.PLAIN_TEXT, LanguageResolver.resolve("data.bin"))
    }

    /** Setiap scope yang dipakai resolver harus terdaftar di assets/textmate/languages.json. */
    @Test
    fun everyScopeHasAGrammarAsset() {
        val json = File("src/main/assets/textmate/languages.json").readText()
        val scopes = Regex("\"scopeName\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        for (language in LanguageResolver.ALL_WITH_GRAMMAR) {
            assertTrue("Grammar untuk ${language.scopeName} tidak ada", language.scopeName in scopes)
        }
        val grammarFiles = Regex("\"grammar\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }
        for (path in grammarFiles) {
            assertTrue("File $path tidak ada", File("src/main/assets/$path").isFile)
        }
    }
}
