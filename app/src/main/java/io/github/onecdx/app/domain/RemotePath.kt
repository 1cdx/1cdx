package io.github.onecdx.app.domain

/** Utilitas path POSIX untuk server SFTP (selalu dipisah `/`). */
object RemotePath {

    fun join(parent: String, name: String): String = when {
        parent.isEmpty() -> name
        parent.endsWith("/") -> parent + name
        else -> "$parent/$name"
    }

    /** Folder induk dari path absolut; root (`/`) tetap `/`. */
    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return when {
            trimmed.isEmpty() && path.startsWith("/") -> "/"
            index < 0 -> ""
            index == 0 -> "/"
            else -> trimmed.substring(0, index)
        }
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    /**
     * Path relatif [path] terhadap [root] (keduanya absolut), atau null bila di luar root.
     * Contoh: root `/var/www`, path `/var/www/app/main.go` → `app/main.go`.
     */
    fun relativeTo(root: String, path: String): String? {
        val base = root.trimEnd('/')
        if (path == base || path == root) return ""
        val prefix = "$base/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }

    /**
     * Alasan nama file/folder baru tidak valid, atau null bila valid.
     * Nama tidak boleh memuat `/`, NUL, atau berupa `.` / `..`.
     */
    fun invalidNameReason(name: String): String? = when {
        name.isBlank() -> "Nama tidak boleh kosong"
        name == "." || name == ".." -> "Nama tidak boleh \".\" atau \"..\""
        '/' in name -> "Nama tidak boleh memuat \"/\""
        '\u0000' in name -> "Nama memuat karakter tidak valid"
        name != name.trim() -> "Nama tidak boleh diawali atau diakhiri spasi"
        else -> null
    }

    /**
     * Path cache lokal untuk file remote: segmen `.`/`..` dan segmen kosong dibuang sehingga
     * hasilnya tidak bisa keluar dari folder cache server tersebut.
     */
    fun cacheSegments(remotePath: String): List<String> =
        remotePath.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
}
