package io.github.onecdx.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onecdx.app.data.ProfileStore
import io.github.onecdx.app.data.SafWorkspaceRepository
import io.github.onecdx.app.data.sftp.HostKeyChangedException
import io.github.onecdx.app.data.sftp.HostKeyUnknownException
import io.github.onecdx.app.data.sftp.PresentedHostKey
import io.github.onecdx.app.data.sftp.SftpConnection
import io.github.onecdx.app.data.sftp.SftpWorkspaceRepository
import io.github.onecdx.app.domain.ConnectionState
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.LanguageInfo
import io.github.onecdx.app.domain.LanguageResolver
import io.github.onecdx.app.domain.RemotePath
import io.github.onecdx.app.domain.ServerProfile
import io.github.onecdx.app.domain.TreeRow
import io.github.onecdx.app.domain.UploadFailedException
import io.github.onecdx.app.domain.WorkspaceRepository
import io.github.onecdx.app.domain.flattenTree
import io.github.onecdx.app.domain.isSameOrInside
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TabState(
    val id: String,
    val entry: FileEntry,
    val language: LanguageInfo,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data class Saving(val name: String, val remote: Boolean) : SaveStatus
    data class Saved(val name: String, val remote: Boolean) : SaveStatus
    data class Failed(val name: String, val reason: String, val cachedLocally: Boolean) : SaveStatus
}

data class WorkbenchState(
    val workspaceName: String? = null,
    val isRemote: Boolean = false,
    val connection: ConnectionState = ConnectionState.Local,
    val connectingTo: String? = null,
    val rootExpanded: Boolean = true,
    val tree: List<TreeRow> = emptyList(),
    val tabs: List<TabState> = emptyList(),
    val activeTabId: String? = null,
    val sidebarVisible: Boolean = true,
    val panelVisible: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val log: List<String> = emptyList(),
    val profiles: List<ServerProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    val activeTab: TabState? get() = tabs.firstOrNull { it.id == activeTabId }
}

sealed interface WorkbenchEvent {
    data class Message(val text: String) : WorkbenchEvent
    data class ConfirmClose(val tabId: String, val name: String) : WorkbenchEvent
    data class ConfirmHostKey(val profile: ServerProfile, val key: PresentedHostKey) : WorkbenchEvent
    data class HostKeyChanged(val profile: ServerProfile, val key: PresentedHostKey?) : WorkbenchEvent
}

/** Hasil "Test Connection" di editor profil. */
sealed interface TestOutcome {
    data class Success(val rootPath: String) : TestOutcome
    data class UnknownHostKey(val key: PresentedHostKey) : TestOutcome
    data class ChangedHostKey(val key: PresentedHostKey?) : TestOutcome
    data class Failure(val reason: String) : TestOutcome
}

/**
 * State utama workbench: workspace (lokal atau SFTP), pohon file, tab, isi dokumen, dan profil server.
 *
 * Isi dokumen ([Content] milik sora-editor, termasuk riwayat undo dan posisi kursor)
 * disimpan di sini, bukan di Activity, supaya tetap ada saat pindah tab.
 */
class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("workbench", Context.MODE_PRIVATE)
    private val profileStore = ProfileStore(app)
    private var repository: WorkspaceRepository? = null
    private var connectionJob: Job? = null
    private var connectJob: Job? = null

    private val children = HashMap<String, List<FileEntry>>()
    private val parentOf = HashMap<String, FileEntry>()
    private val expanded = HashSet<String>()
    private val loading = HashSet<String>()

    private val documents = HashMap<String, Content>()
    private val editVersions = HashMap<String, Long>()
    private val opening = HashSet<String>()

    private val _state = MutableStateFlow(WorkbenchState())
    val state: StateFlow<WorkbenchState> = _state.asStateFlow()

    private val _events = Channel<WorkbenchEvent>(Channel.BUFFERED)
    val events: Flow<WorkbenchEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            loadProfiles()
            restoreWorkspace()
        }
    }

    override fun onCleared() {
        repository?.close()
    }

    // ---------------------------------------------------------------- Workspace lokal

    fun openWorkspace(treeUri: Uri) {
        if (blockedByUnsaved("membuka folder lain")) return
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            log("Izin permanen untuk folder tidak didapat: ${e.message}")
        }
        val repo = try {
            SafWorkspaceRepository(resolver, treeUri)
        } catch (e: Exception) {
            message("Folder tidak bisa dibuka: ${e.message}")
            return
        }
        connectJob?.cancel()
        prefs.edit().putString(KEY_WORKSPACE, repo.location).apply()
        setRepository(repo, profileId = null)
    }

    private fun restoreWorkspace() {
        val saved = prefs.getString(KEY_WORKSPACE, null) ?: return
        if (saved.startsWith(SftpWorkspaceRepository.LOCATION_PREFIX)) {
            val id = saved.removePrefix(SftpWorkspaceRepository.LOCATION_PREFIX)
            _state.value.profiles.firstOrNull { it.id == id }?.let(::connect)
            return
        }
        val uri = Uri.parse(saved)
        val resolver = getApplication<Application>().contentResolver
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return
        try {
            setRepository(SafWorkspaceRepository(resolver, uri), profileId = null)
        } catch (e: Exception) {
            log("Workspace terakhir tidak bisa dibuka lagi: ${e.message}")
        }
    }

    private fun setRepository(repo: WorkspaceRepository, profileId: String?) {
        repository?.takeIf { it !== repo }?.close()
        repository = repo
        children.clear(); parentOf.clear(); expanded.clear(); loading.clear()
        documents.clear(); editVersions.clear(); opening.clear()
        _state.update {
            it.copy(
                workspaceName = repo.root.name,
                isRemote = repo.isRemote,
                connection = repo.connection.value,
                rootExpanded = true,
                tabs = emptyList(),
                activeTabId = null,
                saveStatus = SaveStatus.Idle,
                activeProfileId = profileId,
            )
        }
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            var last: ConnectionState? = null
            repo.connection.collect { connection ->
                if (repository !== repo) return@collect
                _state.update { it.copy(connection = connection) }
                if (last != null && connection != last) logConnection(connection)
                last = connection
            }
        }
        log(if (repo.isRemote) "Terhubung, membuka ${repo.displayPath(repo.root)}" else "Membuka folder ${repo.displayPath(repo.root)}")
        loadDirectory(repo.root)
    }

    /** Menutup workspace aktif (dan memutus koneksi SFTP). */
    fun closeWorkspace() {
        if (blockedByUnsaved("menutup workspace")) return
        connectJob?.cancel()
        connectionJob?.cancel()
        repository?.close()
        repository = null
        children.clear(); parentOf.clear(); expanded.clear(); loading.clear()
        documents.clear(); editVersions.clear(); opening.clear()
        prefs.edit().remove(KEY_WORKSPACE).apply()
        _state.update {
            it.copy(
                workspaceName = null, isRemote = false, connection = ConnectionState.Local, tree = emptyList(),
                tabs = emptyList(), activeTabId = null, saveStatus = SaveStatus.Idle, activeProfileId = null,
            )
        }
        log("Workspace ditutup")
    }

    fun toggleRoot() {
        _state.update { it.copy(rootExpanded = !it.rootExpanded) }
        publishTree()
    }

    // ---------------------------------------------------------------- Profil & koneksi SFTP

    private suspend fun loadProfiles() {
        val profiles = try {
            profileStore.load()
        } catch (e: Exception) {
            log("Profil server tidak bisa dibaca: ${e.message}")
            message("Profil server tidak bisa dibaca: ${e.message}")
            emptyList()
        }
        _state.update { it.copy(profiles = profiles) }
    }

    fun saveProfile(profile: ServerProfile) {
        val list = _state.value.profiles
        val updated = if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
        persistProfiles(updated)
    }

    fun deleteProfile(id: String) = persistProfiles(_state.value.profiles.filterNot { it.id == id })

    fun duplicateProfile(id: String) {
        val source = _state.value.profiles.firstOrNull { it.id == id } ?: return
        persistProfiles(_state.value.profiles + source.duplicate())
    }

    private fun persistProfiles(profiles: List<ServerProfile>) {
        _state.update { it.copy(profiles = profiles) }
        viewModelScope.launch {
            try {
                profileStore.save(profiles)
            } catch (e: Exception) {
                log("Gagal menyimpan profil server: ${e.message}")
                message("Gagal menyimpan profil server: ${e.message}")
            }
        }
    }

    fun connect(profile: ServerProfile) {
        if (blockedByUnsaved("berpindah workspace")) return
        connectJob?.cancel()
        _state.update { it.copy(connectingTo = profile.displayName) }
        log("Menghubungkan ke ${profile.address}…")
        connectJob = viewModelScope.launch {
            val connection = SftpConnection(profile)
            try {
                val repo = SftpWorkspaceRepository.open(connection, getApplication<Application>().cacheDir)
                prefs.edit().putString(KEY_WORKSPACE, repo.location).apply()
                setRepository(repo, profile.id)
            } catch (e: HostKeyUnknownException) {
                connection.close()
                _events.trySend(WorkbenchEvent.ConfirmHostKey(profile, e.key))
            } catch (e: HostKeyChangedException) {
                connection.close()
                log("PERINGATAN: host key ${profile.address} berubah, koneksi ditolak")
                _events.trySend(WorkbenchEvent.HostKeyChanged(profile, e.key))
            } catch (e: kotlinx.coroutines.CancellationException) {
                connection.close()
                throw e
            } catch (e: Exception) {
                connection.close()
                val reason = e.message ?: e.javaClass.simpleName
                log("Gagal terhubung ke ${profile.address}: $reason")
                message("Gagal terhubung ke ${profile.displayName}: $reason")
            } finally {
                _state.update { it.copy(connectingTo = null) }
            }
        }
    }

    /** Pengguna mempercayai host key yang baru pertama kali dilihat: simpan ke profil lalu sambung lagi. */
    fun trustHostKeyAndConnect(profile: ServerProfile, key: PresentedHostKey) {
        val trusted = profile.copy(hostKey = key.entry)
        saveProfile(trusted)
        log("Host key ${profile.address} dipercaya: ${key.type} ${key.fingerprint}")
        connect(trusted)
    }

    suspend fun testConnection(profile: ServerProfile): TestOutcome {
        val connection = SftpConnection(profile)
        return try {
            val repo = SftpWorkspaceRepository.open(connection, getApplication<Application>().cacheDir)
            TestOutcome.Success(repo.root.id)
        } catch (e: HostKeyUnknownException) {
            TestOutcome.UnknownHostKey(e.key)
        } catch (e: HostKeyChangedException) {
            TestOutcome.ChangedHostKey(e.key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TestOutcome.Failure(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.close()
        }
    }

    // ---------------------------------------------------------------- Explorer

    fun onTreeRowClicked(row: TreeRow) {
        if (row.entry.isDirectory) toggleDirectory(row.entry) else openFile(row.entry)
    }

    private fun toggleDirectory(directory: FileEntry) {
        if (!expanded.remove(directory.id)) {
            expanded += directory.id
            if (directory.id !in children) loadDirectory(directory) else publishTree()
        } else {
            publishTree()
        }
    }

    fun refresh(directory: FileEntry?) {
        val repo = repository ?: return
        loadDirectory(directory ?: repo.root)
    }

    private fun loadDirectory(directory: FileEntry) {
        val repo = repository ?: return
        if (!loading.add(directory.id)) return
        publishTree()
        viewModelScope.launch {
            try {
                val list = repo.list(directory)
                if (repository !== repo) return@launch
                children[directory.id]?.forEach { parentOf.remove(it.id) }
                children[directory.id] = list
                list.forEach { parentOf[it.id] = directory }
            } catch (e: Exception) {
                if (repository !== repo) return@launch
                expanded.remove(directory.id)
                log("Gagal membaca folder ${directory.path.ifEmpty { directory.name }}: ${e.message}")
                message("Gagal membaca folder: ${e.message}")
            } finally {
                loading.remove(directory.id)
                if (repository === repo) publishTree()
            }
        }
    }

    private fun publishTree() {
        val repo = repository
        val rows = if (repo == null || !_state.value.rootExpanded) {
            emptyList()
        } else {
            flattenTree(repo.root.id, children, expanded, loading)
        }
        _state.update { it.copy(tree = rows) }
    }

    /** Semua file yang sudah dimuat di explorer, untuk Quick Open (Ctrl+P). */
    fun knownFiles(): List<FileEntry> = children.values.flatten().filter { !it.isDirectory }

    fun displayPath(entry: FileEntry): String? = repository?.displayPath(entry)

    fun workspaceRoot(): FileEntry? = repository?.root

    // ---------------------------------------------------------------- Operasi file

    fun createEntry(parent: FileEntry?, name: String, directory: Boolean) {
        val repo = repository ?: return
        val target = parent ?: repo.root
        val trimmed = name.trim()
        RemotePath.invalidNameReason(trimmed)?.let { message(it); return }
        viewModelScope.launch {
            try {
                val created = if (directory) repo.createDirectory(target, trimmed) else repo.createFile(target, trimmed)
                if (repository !== repo) return@launch
                log("Dibuat: ${repo.displayPath(created)}")
                if (target.id != repo.root.id) expanded += target.id
                if (!_state.value.rootExpanded) _state.update { it.copy(rootExpanded = true) }
                loadDirectory(target)
                if (!directory) openFile(created)
            } catch (e: Exception) {
                failed("membuat $trimmed", e)
            }
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        val repo = repository ?: return
        val trimmed = newName.trim()
        if (trimmed == entry.name) return
        RemotePath.invalidNameReason(trimmed)?.let { message(it); return }
        if (entry.isDirectory && _state.value.tabs.any { isSameOrInside(it.entry, entry) }) {
            message("Tutup dulu tab file yang ada di dalam folder ${entry.name}.")
            return
        }
        if (_state.value.tabs.any { it.id == entry.id && it.saving }) {
            message("Tunggu sampai ${entry.name} selesai disimpan.")
            return
        }
        viewModelScope.launch {
            try {
                val renamed = repo.rename(entry, trimmed)
                if (repository !== repo) return@launch
                log("Diganti nama: ${repo.displayPath(entry)} → ${renamed.name}")
                if (entry.isDirectory) forgetSubtree(entry)
                rekeyTab(entry.id, renamed)
                loadDirectory(parentOf[entry.id] ?: repo.root)
            } catch (e: Exception) {
                failed("mengganti nama ${entry.name}", e)
            }
        }
    }

    fun delete(entry: FileEntry) {
        val repo = repository ?: return
        if (entry.id == repo.root.id) return
        viewModelScope.launch {
            try {
                repo.delete(entry)
                if (repository !== repo) return@launch
                log("Dihapus: ${repo.displayPath(entry)}")
                _state.value.tabs.filter { isSameOrInside(it.entry, entry) }.forEach { closeTab(it.id, force = true) }
                if (entry.isDirectory) forgetSubtree(entry)
                loadDirectory(parentOf[entry.id] ?: repo.root)
            } catch (e: Exception) {
                failed("menghapus ${entry.name}", e)
            }
        }
    }

    private fun forgetSubtree(directory: FileEntry) {
        val ids = children.keys.filter { id ->
            id == directory.id || children.values.flatten().any { it.id == id && isSameOrInside(it, directory) }
        }
        ids.forEach { children.remove(it); expanded.remove(it) }
    }

    private fun rekeyTab(oldId: String, entry: FileEntry) {
        val tab = _state.value.tabs.firstOrNull { it.id == oldId } ?: return
        documents.remove(oldId)?.let { documents[entry.id] = it }
        editVersions.remove(oldId)?.let { editVersions[entry.id] = it }
        val updated = tab.copy(id = entry.id, entry = entry, language = LanguageResolver.resolve(entry.name))
        _state.update { s ->
            s.copy(
                tabs = s.tabs.map { if (it.id == oldId) updated else it },
                activeTabId = if (s.activeTabId == oldId) entry.id else s.activeTabId,
            )
        }
    }

    // ---------------------------------------------------------------- Tabs

    fun openFile(entry: FileEntry) {
        val repo = repository ?: return
        if (_state.value.tabs.any { it.id == entry.id }) {
            activateTab(entry.id)
            return
        }
        if (entry.size > MAX_FILE_BYTES) {
            message("${entry.name} terlalu besar untuk dibuka (${entry.size / 1024 / 1024} MB).")
            return
        }
        if (!opening.add(entry.id)) return
        viewModelScope.launch {
            try {
                val unsynced = repo.hasUnsyncedChanges(entry)
                val text = repo.readText(entry)
                if (text.indexOf('\u0000') >= 0) {
                    message("${entry.name} terlihat seperti file biner dan tidak dibuka.")
                    return@launch
                }
                val content = withContext(Dispatchers.Default) { Content(text) }
                if (repository !== repo) return@launch
                documents[entry.id] = content
                editVersions[entry.id] = 0
                val tab = TabState(entry.id, entry, LanguageResolver.resolve(entry.name), dirty = unsynced)
                _state.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
                if (unsynced) {
                    log("Memuat versi cache ${entry.path} yang belum ter-upload")
                    message("${entry.name}: versi lokal belum ter-upload. Tekan Ctrl+S untuk mengunggah.")
                }
            } catch (e: Exception) {
                log("Gagal membuka ${entry.path}: ${e.message}")
                message("Gagal membuka ${entry.name}: ${e.message}")
            } finally {
                opening.remove(entry.id)
            }
        }
    }

    fun document(tabId: String): Content? = documents[tabId]

    fun activateTab(tabId: String) {
        if (_state.value.tabs.any { it.id == tabId }) _state.update { it.copy(activeTabId = tabId) }
    }

    fun activateRelative(offset: Int) {
        val s = _state.value
        if (s.tabs.size < 2) return
        val index = s.tabs.indexOfFirst { it.id == s.activeTabId }.coerceAtLeast(0)
        val next = Math.floorMod(index + offset, s.tabs.size)
        _state.update { it.copy(activeTabId = s.tabs[next].id) }
    }

    fun closeActiveTab() {
        _state.value.activeTabId?.let { closeTab(it) }
    }

    fun closeTab(tabId: String, force: Boolean = false) {
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab.dirty && !force) {
            _events.trySend(WorkbenchEvent.ConfirmClose(tab.id, tab.entry.name))
            return
        }
        documents.remove(tabId)
        editVersions.remove(tabId)
        _state.update { s ->
            val index = s.tabs.indexOfFirst { it.id == tabId }
            val remaining = s.tabs.filterNot { it.id == tabId }
            val active = if (s.activeTabId != tabId) s.activeTabId
            else remaining.getOrNull(index.coerceAtMost(remaining.size - 1))?.id
            s.copy(tabs = remaining, activeTabId = active)
        }
    }

    fun closeOtherTabs(keepId: String) {
        _state.value.tabs.filter { it.id != keepId }.forEach { closeTab(it.id) }
    }

    /** Dipanggil Activity setiap kali isi editor berubah karena diketik/undo/replace. */
    fun onDocumentEdited(tabId: String) {
        editVersions[tabId] = (editVersions[tabId] ?: 0) + 1
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        if (!tab.dirty) updateTab(tabId) { it.copy(dirty = true) }
    }

    // ---------------------------------------------------------------- Save

    fun saveActive() {
        _state.value.activeTabId?.let { save(it) }
    }

    fun save(tabId: String, closeAfter: Boolean = false) {
        val repo = repository ?: return
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        val content = documents[tabId] ?: return
        if (tab.saving) return
        // Snapshot di main thread: Content tidak thread-safe.
        val text = content.toString()
        val version = editVersions[tabId] ?: 0
        val entry = tab.entry
        updateTab(tabId) { it.copy(saving = true) }
        _state.update { it.copy(saveStatus = SaveStatus.Saving(entry.name, repo.isRemote)) }
        viewModelScope.launch {
            try {
                repo.writeText(entry, text)
                val unchanged = (editVersions[tabId] ?: 0) == version
                updateTab(tabId) { it.copy(saving = false, dirty = it.dirty && !unchanged) }
                _state.update { it.copy(saveStatus = SaveStatus.Saved(entry.name, repo.isRemote)) }
                log(if (repo.isRemote) "Uploaded: ${repo.displayPath(entry)}" else "Tersimpan: ${entry.path}")
                if (closeAfter && unchanged) closeTab(tabId, force = true)
            } catch (e: Exception) {
                val cached = e is UploadFailedException
                val reason = (if (cached) e.cause?.message else null) ?: e.message ?: e.javaClass.simpleName
                updateTab(tabId) { it.copy(saving = false) }
                _state.update { it.copy(saveStatus = SaveStatus.Failed(entry.name, reason, cached)) }
                if (cached) {
                    log("Upload ${repo.displayPath(entry)} gagal ($reason); perubahan aman di cache lokal")
                    message("Upload ${entry.name} gagal: $reason. Perubahan aman di cache, tekan Ctrl+S untuk mencoba lagi.")
                } else {
                    log("Gagal menyimpan ${entry.path}: $reason")
                    message("Gagal menyimpan ${entry.name}: $reason")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Layout

    fun toggleSidebar() = _state.update { it.copy(sidebarVisible = !it.sidebarVisible) }

    fun togglePanel() = _state.update { it.copy(panelVisible = !it.panelVisible) }

    fun setPanelVisible(visible: Boolean) = _state.update { it.copy(panelVisible = visible) }

    // ---------------------------------------------------------------- Helpers

    private fun blockedByUnsaved(action: String): Boolean {
        if (_state.value.tabs.none { it.dirty }) return false
        message("Simpan atau tutup file yang belum disimpan sebelum $action.")
        return true
    }

    private fun updateTab(tabId: String, change: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.id == tabId) change(it) else it }) }
    }

    private fun failed(action: String, e: Exception) {
        val reason = e.message ?: e.javaClass.simpleName
        log("Gagal $action: $reason")
        message("Gagal $action: $reason")
    }

    private fun logConnection(connection: ConnectionState) {
        when (connection) {
            is ConnectionState.Connecting -> log(if (connection.reconnect) "Koneksi terputus, menyambung ulang ke ${connection.address}…" else "Menghubungkan ke ${connection.address}…")
            is ConnectionState.Connected -> log("Terhubung: ${connection.address}")
            is ConnectionState.Disconnected -> connection.reason?.let { log("Terputus dari ${connection.address}: $it") }
            ConnectionState.Local -> Unit
        }
    }

    private fun message(text: String) {
        _events.trySend(WorkbenchEvent.Message(text))
    }

    private fun log(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _state.update { it.copy(log = (it.log + "[$time] $line").takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val KEY_WORKSPACE = "workspace_uri"
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
        const val MAX_LOG_LINES = 500
    }
}
