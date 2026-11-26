package io.github.onecdx.app.domain

/**
 * Satu file atau folder di workspace.
 *
 * @param id identitas yang hanya dimengerti oleh [WorkspaceRepository] pembuatnya
 *   (document id untuk SAF, path remote untuk SFTP di Milestone 2).
 * @param path path relatif dari root workspace, dipisah `/` (kosong untuk root).
 */
data class FileEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val path: String,
)
