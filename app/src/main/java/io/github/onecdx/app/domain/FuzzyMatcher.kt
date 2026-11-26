package io.github.onecdx.app.domain

/** Pencocokan subsequence ala Ctrl+P di VS Code. */
object FuzzyMatcher {

    /**
     * Skor kecocokan [query] terhadap [path] (makin besar makin cocok), atau null jika
     * huruf-huruf query tidak muncul berurutan di path.
     */
    fun score(query: String, path: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val p = path.lowercase()
        val nameStart = p.lastIndexOf('/') + 1
        var qi = 0
        var score = 0
        var previous = -2
        for (pi in p.indices) {
            if (qi == q.length) break
            if (p[pi] != q[qi]) continue
            score += 1
            if (pi == previous + 1) score += 5
            if (pi >= nameStart) score += 3
            if (pi == 0 || p[pi - 1] in "/._- ") score += 4
            previous = pi
            qi++
        }
        if (qi < q.length) return null
        // Nama file yang persis/berawalan query diutamakan
        val name = p.substring(nameStart)
        if (name == q) score += 50 else if (name.startsWith(q)) score += 20
        return score
    }

    fun filter(query: String, files: List<FileEntry>, limit: Int = 50): List<FileEntry> =
        files.asSequence()
            .mapNotNull { file -> score(query.trim(), file.path)?.let { file to it } }
            .sortedWith(compareByDescending<Pair<FileEntry, Int>> { it.second }.thenBy { it.first.path.length }.thenBy { it.first.path })
            .take(limit)
            .map { it.first }
            .toList()
}
