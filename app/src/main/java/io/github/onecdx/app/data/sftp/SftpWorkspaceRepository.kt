package io.github.onecdx.app.data.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import io.github.onecdx.app.domain.ConnectionState
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.RemotePath
import io.github.onecdx.app.domain.ServerProfile
import io.github.onecdx.app.domain.UploadFailedException
import io.github.onecdx.app.domain.WorkspaceRepository
import io.github.onecdx.app.domain.childPath
import io.github.onecdx.app.domain.sortEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Workspace di server SFTP (README §4.B–C).
 *
 * - Isi folder dimuat saat folder dibuka (`ls`), bukan rekursif.
 * - File yang dibuka diunduh dulu ke `cacheDir/sftp_workspaces/<server_id>/<remote_path>`.
 * - Simpan = tulis ke cache, lalu upload dengan `put(..., OVERWRITE)`. Bila upload gagal, file ditandai
 *   `pending` sehingga versi cache tidak tertimpa unduhan berikutnya dan bisa di-upload ulang.
 *
 * [FileEntry.id] berisi path absolut di server.
 */
class SftpWorkspaceRepository private constructor(
    private val sftp: SftpConnection,
    private val cacheRoot: File,
    rootPath: String,
) : WorkspaceRepository {

    override val root = FileEntry(rootPath, RemotePath.name(rootPath), true, 0, "")
    override val location: String = LOCATION_PREFIX + sftp.profile.id
    override val isRemote = true
    override val connection: StateFlow<ConnectionState> = sftp.state

    val profile: ServerProfile get() = sftp.profile

    override fun displayPath(entry: FileEntry): String = entry.id

    override suspend fun list(directory: FileEntry): List<FileEntry> {
        val entries = sftp.run { ch ->
            ch.ls(directory.id).mapNotNull { item ->
                val name = item.filename
                if (name == "." || name == "..") return@mapNotNull null
                val remote = RemotePath.join(directory.id, name)
                val attrs = if (item.attrs.isLink) followLink(ch, remote) else item.attrs
                FileEntry(
                    id = remote,
                    name = name,
                    isDirectory = attrs?.isDir == true,
                    size = attrs?.size ?: 0L,
                    path = childPath(directory, name),
                )
            }
        }
        return sortEntries(entries)
    }

    override suspend fun readText(file: FileEntry): String {
        val cache = cacheFile(file)
        if (!pendingMarker(cache).exists()) {
            sftp.run { ch ->
                cache.parentFile?.mkdirs()
                val partial = File(cache.path + PARTIAL_SUFFIX)
                FileOutputStream(partial).use { ch.get(file.id, it) }
                if (!partial.renameTo(cache)) throw IOException("Cache lokal tidak bisa ditulis")
            }
        }
        return withContext(Dispatchers.IO) { cache.readText(Charsets.UTF_8) }
    }

    override fun hasUnsyncedChanges(file: FileEntry): Boolean = pendingMarker(cacheFile(file)).exists()

    override suspend fun writeText(file: FileEntry, text: String) {
        val cache = cacheFile(file)
        val marker = pendingMarker(cache)
        withContext(Dispatchers.IO) {
            cache.parentFile?.mkdirs()
            val partial = File(cache.path + PARTIAL_SUFFIX)
            partial.writeText(text, Charsets.UTF_8)
            if (!partial.renameTo(cache)) throw IOException("Cache lokal tidak bisa ditulis")
            marker.createNewFile()
        }
        try {
            sftp.run { ch -> FileInputStream(cache).use { ch.put(it, file.id, ChannelSftp.OVERWRITE) } }
        } catch (e: IOException) {
            throw UploadFailedException("Tersimpan di cache lokal, upload gagal: ${e.message}", e)
        }
        withContext(Dispatchers.IO) { marker.delete() }
    }

    override suspend fun createFile(parent: FileEntry, name: String): FileEntry {
        val remote = RemotePath.join(parent.id, name)
        sftp.run { ch ->
            ensureAbsent(ch, remote, name)
            ch.put(ByteArrayInputStream(ByteArray(0)), remote, ChannelSftp.OVERWRITE)
        }
        return FileEntry(remote, name, false, 0, childPath(parent, name))
    }

    override suspend fun createDirectory(parent: FileEntry, name: String): FileEntry {
        val remote = RemotePath.join(parent.id, name)
        sftp.run { ch ->
            ensureAbsent(ch, remote, name)
            ch.mkdir(remote)
        }
        return FileEntry(remote, name, true, 0, childPath(parent, name))
    }

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry {
        val target = RemotePath.join(RemotePath.parent(entry.id), newName)
        sftp.run { ch ->
            ensureAbsent(ch, target, newName)
            ch.rename(entry.id, target)
        }
        val renamed = entry.copy(
            id = target,
            name = newName,
            path = entry.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" },
        )
        withContext(Dispatchers.IO) { moveCache(entry, renamed) }
        return renamed
    }

    override suspend fun delete(entry: FileEntry) {
        sftp.run { ch -> deleteRecursively(ch, entry.id) }
        withContext(Dispatchers.IO) {
            val cache = cacheFile(entry)
            cache.deleteRecursively()
            pendingMarker(cache).delete()
        }
    }

    override fun close() = sftp.close()

    // ------------------------------------------------------------------ Internal

    private fun followLink(ch: ChannelSftp, remote: String): SftpATTRS? = try {
        ch.stat(remote)
    } catch (e: SftpException) {
        null // symlink rusak: tampilkan sebagai file biasa
    }

    private fun exists(ch: ChannelSftp, remote: String): Boolean = try {
        ch.lstat(remote)
        true
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
    }

    private fun ensureAbsent(ch: ChannelSftp, remote: String, name: String) {
        if (exists(ch, remote)) throw SftpOperationException("\"$name\" sudah ada", null)
    }

    /** Hapus rekursif tanpa mengikuti symlink (link dihapus, targetnya tidak). */
    private fun deleteRecursively(ch: ChannelSftp, remote: String) {
        val attrs = try {
            ch.lstat(remote)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return else throw e
        }
        if (attrs.isDir && !attrs.isLink) {
            for (item in ch.ls(remote)) {
                val name = item.filename
                if (name == "." || name == "..") continue
                deleteRecursively(ch, RemotePath.join(remote, name))
            }
            ch.rmdir(remote)
        } else {
            ch.rm(remote)
        }
    }

    private fun cacheFile(entry: FileEntry): File =
        RemotePath.cacheSegments(entry.id).fold(cacheRoot) { dir, segment -> File(dir, segment) }

    private fun pendingMarker(cache: File) = File(cache.path + PENDING_SUFFIX)

    private fun moveCache(from: FileEntry, to: FileEntry) {
        val source = cacheFile(from)
        val target = cacheFile(to)
        if (source.exists()) {
            target.parentFile?.mkdirs()
            target.deleteRecursively()
            if (!source.renameTo(target)) source.deleteRecursively()
        }
        val marker = pendingMarker(source)
        if (marker.exists() && !marker.renameTo(pendingMarker(target))) marker.delete()
    }

    companion object {
        const val LOCATION_PREFIX = "sftp:"
        private const val PENDING_SUFFIX = ".1cdx-pending"
        private const val PARTIAL_SUFFIX = ".1cdx-part"

        /**
         * Menyambung ke server dan menentukan folder root workspace:
         * `remotePath` kosong → home user; `~/x` → relatif terhadap home.
         */
        suspend fun open(sftp: SftpConnection, cacheDir: File): SftpWorkspaceRepository {
            val requested = sftp.profile.remotePath
            val rootPath = sftp.run { ch ->
                val home = ch.home
                val path = when {
                    requested.isEmpty() || requested == "~" -> home
                    requested.startsWith("~/") -> RemotePath.join(home, requested.removePrefix("~/"))
                    else -> requested
                }
                val resolved = ch.realpath(path)
                val attrs = try {
                    ch.stat(resolved)
                } catch (e: SftpException) {
                    throw SftpOperationException("Remote path $path tidak ditemukan di server", e)
                }
                if (!attrs.isDir) throw SftpOperationException("Remote path $path bukan folder", null)
                resolved
            }
            val cacheRoot = File(File(cacheDir, "sftp_workspaces"), sftp.profile.id)
            return SftpWorkspaceRepository(sftp, cacheRoot, rootPath)
        }
    }
}
