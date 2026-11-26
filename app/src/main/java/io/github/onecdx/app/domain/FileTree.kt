package io.github.onecdx.app.domain

data class TreeRow(
    val entry: FileEntry,
    val depth: Int,
    val expanded: Boolean,
    val loading: Boolean,
)

/** Folder dulu, lalu urut nama tanpa membedakan huruf besar/kecil (seperti VS Code). */
fun sortEntries(entries: List<FileEntry>): List<FileEntry> =
    entries.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))

/**
 * Meratakan pohon folder yang sudah dimuat menjadi daftar baris untuk RecyclerView.
 * Hanya anak dari folder yang sedang terbuka (`expanded`) yang ikut ditampilkan.
 */
fun flattenTree(
    rootId: String,
    children: Map<String, List<FileEntry>>,
    expanded: Set<String>,
    loading: Set<String>,
): List<TreeRow> {
    val rows = ArrayList<TreeRow>()
    fun walk(directoryId: String, depth: Int) {
        for (entry in children[directoryId].orEmpty()) {
            val open = entry.isDirectory && entry.id in expanded
            rows += TreeRow(entry, depth, open, entry.id in loading)
            if (open) walk(entry.id, depth + 1)
        }
    }
    walk(rootId, 0)
    return rows
}
