package io.github.onecdx.app.domain

import java.io.IOException

/**
 * Sumber file untuk sebuah workspace. Milestone 1 memakai folder lokal (SAF);
 * Milestone 2 menambahkan implementasi SFTP dengan kontrak yang sama.
 */
interface WorkspaceRepository {
    val root: FileEntry

    /** Nilai yang disimpan agar workspace bisa dibuka lagi saat aplikasi dijalankan ulang. */
    val location: String

    /** Path lengkap yang ditampilkan ke pengguna (untuk "Salin path"). */
    fun displayPath(entry: FileEntry): String

    @Throws(IOException::class)
    suspend fun list(directory: FileEntry): List<FileEntry>

    @Throws(IOException::class)
    suspend fun readText(file: FileEntry): String

    @Throws(IOException::class)
    suspend fun writeText(file: FileEntry, text: String)
}
