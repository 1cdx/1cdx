package io.github.onecdx.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.WorkspaceRepository
import io.github.onecdx.app.domain.sortEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Workspace folder lokal lewat Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
 * Isi folder di-query langsung dengan DocumentsContract (satu query per folder),
 * jauh lebih cepat daripada DocumentFile.listFiles().
 */
class SafWorkspaceRepository(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : WorkspaceRepository {

    override val root: FileEntry
    override val location: String = treeUri.toString()

    init {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        root = FileEntry(rootDocId, queryDisplayName(rootDocId) ?: rootDocId.substringAfterLast(':'), true, 0, "")
    }

    override fun displayPath(entry: FileEntry): String {
        // Contoh document id penyimpanan internal: "primary:Projects/app/main.go"
        val docId = entry.id
        return if (docId.startsWith("primary:")) "/storage/emulated/0/" + docId.removePrefix("primary:") else docId
    }

    override suspend fun list(directory: FileEntry): List<FileEntry> = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.id)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        val entries = ArrayList<FileEntry>()
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("Tidak bisa membaca folder ${directory.name}")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val isDirectory = c.getString(2) == Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val path = if (directory.path.isEmpty()) name else "${directory.path}/$name"
                entries += FileEntry(id, name, isDirectory, size, path)
            }
        }
        sortEntries(entries)
    }

    override suspend fun readText(file: FileEntry): String = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(documentUri(file))
            ?: throw IOException("Tidak bisa membuka ${file.name}")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override suspend fun writeText(file: FileEntry, text: String) = withContext(Dispatchers.IO) {
        openForOverwrite(documentUri(file)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun openForOverwrite(uri: Uri): OutputStream {
        // "wt" = write + truncate. Beberapa provider tidak mendukungnya, jadi jatuh ke "w".
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: resolver.openOutputStream(uri, "w")
        return stream ?: throw IOException("Tidak bisa menulis file")
    }

    private fun documentUri(file: FileEntry): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.id)

    private fun queryDisplayName(docId: String): String? = try {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}
