package io.github.onecdx.app.domain

import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Status koneksi untuk workspace remote; workspace lokal selalu [Local]. */
sealed interface ConnectionState {
    data object Local : ConnectionState
    data class Connecting(val address: String, val reconnect: Boolean) : ConnectionState
    data class Connected(val address: String) : ConnectionState
    data class Disconnected(val address: String, val reason: String?) : ConnectionState
}

/**
 * Sumber file untuk sebuah workspace: folder lokal (SAF) atau server SFTP.
 * Semua fungsi suspend menjalankan I/O di luar main thread.
 */
interface WorkspaceRepository {
    val root: FileEntry

    /** Nilai yang disimpan agar workspace bisa dibuka lagi saat aplikasi dijalankan ulang. */
    val location: String

    /** True bila file disimpan ke server (status bar menampilkan "Syncing…/Uploaded"). */
    val isRemote: Boolean

    val connection: StateFlow<ConnectionState>

    /** Path lengkap yang ditampilkan ke pengguna (untuk "Salin path"). */
    fun displayPath(entry: FileEntry): String

    @Throws(IOException::class)
    suspend fun list(directory: FileEntry): List<FileEntry>

    @Throws(IOException::class)
    suspend fun readText(file: FileEntry): String

    /**
     * True bila ada versi lokal yang belum berhasil di-upload (upload terakhir gagal).
     * [readText] lalu mengembalikan versi lokal itu agar perubahan pengguna tidak hilang.
     */
    fun hasUnsyncedChanges(file: FileEntry): Boolean = false

    /**
     * Menyimpan file. Untuk SFTP: tulis ke cache lokal dulu, lalu upload; bila upload gagal
     * dilempar [UploadFailedException] dan isi file tetap aman di cache.
     */
    @Throws(IOException::class)
    suspend fun writeText(file: FileEntry, text: String)

    @Throws(IOException::class)
    suspend fun createFile(parent: FileEntry, name: String): FileEntry

    @Throws(IOException::class)
    suspend fun createDirectory(parent: FileEntry, name: String): FileEntry

    /** Mengganti nama di folder yang sama; mengembalikan entry baru (id bisa berubah). */
    @Throws(IOException::class)
    suspend fun rename(entry: FileEntry, newName: String): FileEntry

    /** Menghapus file, atau folder beserta seluruh isinya. */
    @Throws(IOException::class)
    suspend fun delete(entry: FileEntry)

    /** Melepas koneksi/sumber daya. Aman dipanggil dari thread mana pun, tidak memblokir. */
    fun close() {}
}

class UploadFailedException(message: String, cause: Throwable?) : IOException(message, cause)

/** Path relatif anak [name] di bawah [parent]. */
fun childPath(parent: FileEntry, name: String): String =
    if (parent.path.isEmpty()) name else "${parent.path}/$name"

/** True bila [entry] sama dengan [ancestor] atau berada di dalam folder [ancestor]. */
fun isSameOrInside(entry: FileEntry, ancestor: FileEntry): Boolean =
    entry.id == ancestor.id || (ancestor.isDirectory && (ancestor.path.isEmpty() || entry.path.startsWith(ancestor.path + "/")))
