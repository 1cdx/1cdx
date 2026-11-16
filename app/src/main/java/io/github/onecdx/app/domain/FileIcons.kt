package io.github.onecdx.app.domain

/** Label singkat + warna ARGB untuk ikon berbasis ekstensi di explorer dan tab. */
data class FileIcon(val label: String, val color: Long)

object FileIcons {
    private val FOLDER = FileIcon("", 0xFFC09553)
    private val DEFAULT = FileIcon("··", 0xFF9D9D9D)

    private val byExtension = mapOf(
        "go" to FileIcon("GO", 0xFF00ADD8),
        "py" to FileIcon("PY", 0xFFFFD43B),
        "rs" to FileIcon("RS", 0xFFDEA584),
        "kt" to FileIcon("KT", 0xFFA97BFF), "kts" to FileIcon("KT", 0xFFA97BFF),
        "java" to FileIcon("JV", 0xFFE76F00),
        "js" to FileIcon("JS", 0xFFF1DD35), "mjs" to FileIcon("JS", 0xFFF1DD35), "cjs" to FileIcon("JS", 0xFFF1DD35),
        "jsx" to FileIcon("JSX", 0xFF61DAFB), "tsx" to FileIcon("TSX", 0xFF61DAFB),
        "ts" to FileIcon("TS", 0xFF3178C6),
        "html" to FileIcon("<>", 0xFFE44D26), "htm" to FileIcon("<>", 0xFFE44D26),
        "css" to FileIcon("#", 0xFF42A5F5), "scss" to FileIcon("#", 0xFFCD6799),
        "json" to FileIcon("{}", 0xFFCBCB41),
        "sql" to FileIcon("DB", 0xFFDAD8D8),
        "sh" to FileIcon("$_", 0xFF89E051), "bash" to FileIcon("$_", 0xFF89E051), "zsh" to FileIcon("$_", 0xFF89E051),
        "md" to FileIcon("M↓", 0xFF519ABA),
        "xml" to FileIcon("<>", 0xFFE37933), "svg" to FileIcon("SVG", 0xFFFFB13B),
        "yml" to FileIcon("YML", 0xFFCB171E), "yaml" to FileIcon("YML", 0xFFCB171E),
        "txt" to FileIcon("TXT", 0xFF9D9D9D),
        "env" to FileIcon("ENV", 0xFFFAF743),
    )

    fun forEntry(entry: FileEntry): FileIcon = if (entry.isDirectory) FOLDER else forName(entry.name)

    fun forName(name: String): FileIcon {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return DEFAULT
        return byExtension[name.substring(dot + 1).lowercase()] ?: DEFAULT
    }
}
