package io.github.onecdx.app.domain

/**
 * @param scopeName scope TextMate di `assets/textmate/languages.json`; null = teks biasa.
 */
data class LanguageInfo(val id: String, val displayName: String, val scopeName: String?)

object LanguageResolver {
    val PLAIN_TEXT = LanguageInfo("plaintext", "Plain Text", null)

    private val GO = LanguageInfo("go", "Go", "source.go")
    private val PYTHON = LanguageInfo("python", "Python", "source.python")
    private val RUST = LanguageInfo("rust", "Rust", "source.rust")
    private val KOTLIN = LanguageInfo("kotlin", "Kotlin", "source.kotlin")
    private val JAVA = LanguageInfo("java", "Java", "source.java")
    private val JAVASCRIPT = LanguageInfo("javascript", "JavaScript", "source.js")
    private val TYPESCRIPT = LanguageInfo("typescript", "TypeScript", "source.ts")
    private val HTML = LanguageInfo("html", "HTML", "text.html.basic")
    private val CSS = LanguageInfo("css", "CSS", "source.css")
    private val JSON = LanguageInfo("json", "JSON", "source.json")
    private val SQL = LanguageInfo("sql", "SQL", "source.sql")
    private val SHELL = LanguageInfo("shellscript", "Shell Script", "source.shell")
    private val MARKDOWN = LanguageInfo("markdown", "Markdown", "text.html.markdown")
    private val XML = LanguageInfo("xml", "XML", "text.xml")

    /** Semua bahasa yang punya grammar; dipakai test untuk mencocokkan dengan aset. */
    val ALL_WITH_GRAMMAR = listOf(GO, PYTHON, RUST, KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, HTML, CSS, JSON, SQL, SHELL, MARKDOWN, XML)

    private val byExtension = mapOf(
        "go" to GO,
        "py" to PYTHON, "pyw" to PYTHON, "pyi" to PYTHON,
        "rs" to RUST,
        "kt" to KOTLIN, "kts" to KOTLIN,
        "java" to JAVA,
        "js" to JAVASCRIPT, "mjs" to JAVASCRIPT, "cjs" to JAVASCRIPT, "jsx" to JAVASCRIPT,
        "ts" to TYPESCRIPT, "mts" to TYPESCRIPT, "cts" to TYPESCRIPT, "tsx" to TYPESCRIPT,
        "html" to HTML, "htm" to HTML, "xhtml" to HTML,
        "css" to CSS, "scss" to CSS, "less" to CSS,
        "json" to JSON, "jsonc" to JSON, "webmanifest" to JSON,
        "sql" to SQL,
        "sh" to SHELL, "bash" to SHELL, "zsh" to SHELL, "ksh" to SHELL,
        "md" to MARKDOWN, "markdown" to MARKDOWN,
        "xml" to XML, "svg" to XML, "xsd" to XML, "xsl" to XML, "plist" to XML,
    )

    private val byFileName = mapOf(
        ".bashrc" to SHELL, ".bash_profile" to SHELL, ".profile" to SHELL, ".zshrc" to SHELL,
        ".bash_aliases" to SHELL,
    )

    fun resolve(fileName: String): LanguageInfo {
        byFileName[fileName]?.let { return it }
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return PLAIN_TEXT
        return byExtension[fileName.substring(dot + 1).lowercase()] ?: PLAIN_TEXT
    }
}
