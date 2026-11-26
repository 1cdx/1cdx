package io.github.onecdx.app.editor

import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Mem-parse semua grammar, language-configuration, dan tema di assets dengan tm4e yang sama
 * dengan yang dipakai aplikasi, supaya aset rusak ketahuan di CI, bukan di perangkat.
 */
class TextMateAssetsTest {

    private val assets = File("src/main/assets")
    private val languagesJson = File(assets, "textmate/languages.json").readText()

    private data class Entry(val name: String, val scope: String, val grammar: String, val config: String?)

    private fun entries(): List<Entry> {
        val blocks = Regex("\\{[^{}]*\"grammar\"[^{}]*(\\{[^{}]*\\}[^{}]*)?\\}").findAll(languagesJson).map { it.value }
        return blocks.map { block ->
            fun field(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1)
            Entry(field("name")!!, field("scopeName")!!, field("grammar")!!, field("languageConfiguration"))
        }.toList()
    }

    private val samples = mapOf(
        "go" to "func main() { fmt.Println(\"hi\") }",
        "python" to "def f(x: int) -> str: return f\"{x}\"",
        "rust" to "fn main() { let v: Vec<u8> = vec![1, 2]; }",
        "javascript" to "const f = async (a) => { await a; } // x",
        "typescript" to "export interface A<T> { x: T }",
        "css" to ".a > b:hover { color: #fff; }",
        "html" to "<div class=\"a\"><script>let a = 1</script></div>",
        "json" to "{\"a\": [1, true, null]}",
        "sql" to "SELECT id FROM users WHERE name = 'x';",
        "shellscript" to "if [ -f \"\$HOME/.x\" ]; then echo ok; fi",
        "java" to "public class A { void f() {} }",
        "markdown" to "# Judul **tebal** `kode`",
        "kotlin" to "fun main() = println(\"hi\")",
        "xml" to "<a b=\"c\"><d/></a>",
    )

    @Test
    fun allGrammarsParseAndTokenize() {
        val list = entries()
        assertEquals("Jumlah bahasa di languages.json", samples.size, list.size)
        for (entry in list) {
            val registry = Registry()
            val grammar = registry.addGrammar(IGrammarSource.fromFile(File(assets, entry.grammar)))
            assertEquals(entry.scope, grammar.scopeName)
            val sample = samples[entry.name] ?: error("Tidak ada contoh kode untuk ${entry.name}")
            val result = try {
                grammar.tokenizeLine(sample)
            } catch (e: Throwable) {
                throw AssertionError("Tokenisasi ${entry.name} gagal: $e", e)
            }
            assertTrue("Tokenisasi ${entry.name} kosong", result.tokens.isNotEmpty())
        }
    }

    @Test
    fun allLanguageConfigurationsLoad() {
        for (entry in entries()) {
            val path = entry.config ?: continue
            val config = File(assets, path).reader().use { LanguageConfiguration.load(it) }
            assertNotNull("language-configuration ${entry.name} gagal dimuat", config)
        }
    }

    @Test
    fun darkPlusThemeLoads() {
        val registry = Registry()
        registry.setTheme(IThemeSource.fromFile(File(assets, "textmate/dark_plus.json")))
        assertTrue(registry.colorMap.isNotEmpty())
    }
}
