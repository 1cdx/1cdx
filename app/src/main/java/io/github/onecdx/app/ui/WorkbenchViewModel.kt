package io.github.onecdx.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onecdx.app.data.SafWorkspaceRepository
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.LanguageInfo
import io.github.onecdx.app.domain.LanguageResolver
import io.github.onecdx.app.domain.TreeRow
import io.github.onecdx.app.domain.WorkspaceRepository
import io.github.onecdx.app.domain.flattenTree
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Dispatchers
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
    data class Saving(val name: String) : SaveStatus
    data class Saved(val name: String) : SaveStatus
    data class Failed(val name: String, val reason: String) : SaveStatus
}

data class WorkbenchState(
    val workspaceName: String? = null,
    val rootExpanded: Boolean = true,
    val tree: List<TreeRow> = emptyList(),
    val tabs: List<TabState> = emptyList(),
    val activeTabId: String? = null,
    val sidebarVisible: Boolean = true,
    val panelVisible: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val log: List<String> = emptyList(),
) {
    val activeTab: TabState? get() = tabs.firstOrNull { it.id == activeTabId }
}

sealed interface WorkbenchEvent {
    data class Message(val text: String) : WorkbenchEvent
    data class ConfirmClose(val tabId: String, val name: String) : WorkbenchEvent
}

/**
 * State utama workbench: workspace, pohon file, tab, dan isi dokumen.
 *
 * Isi dokumen ([Content] milik sora-editor, termasuk riwayat undo dan posisi kursor)
 * disimpan di sini, bukan di Activity, supaya tetap ada saat pindah tab.
 */
class WorkbenchViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("workbench", Context.MODE_PRIVATE)
    private var repository: WorkspaceRepository? = null

    private val children = HashMap<String, List<FileEntry>>()
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
        restoreWorkspace()
    }

    // ---------------------------------------------------------------- Workspace

    fun openWorkspace(treeUri: Uri) {
        if (_state.value.tabs.any { it.dirty }) {
            message("Simpan atau tutup file yang belum disimpan sebelum membuka folder lain.")
            return
        }
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
        prefs.edit().putString(KEY_WORKSPACE, repo.location).apply()
        setRepository(repo)
    }

    private fun restoreWorkspace() {
        val saved = prefs.getString(KEY_WORKSPACE, null) ?: return
        val uri = Uri.parse(saved)
        val resolver = getApplication<Application>().contentResolver
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return
        try {
            setRepository(SafWorkspaceRepository(resolver, uri))
        } catch (e: Exception) {
            log("Workspace terakhir tidak bisa dibuka lagi: ${e.message}")
        }
    }

    private fun setRepository(repo: WorkspaceRepository) {
        repository = repo
        children.clear(); expanded.clear(); loading.clear()
        documents.clear(); editVersions.clear(); opening.clear()
        _state.update {
            it.copy(
                workspaceName = repo.root.name,
                rootExpanded = true,
                tabs = emptyList(),
                activeTabId = null,
                saveStatus = SaveStatus.Idle,
            )
        }
        log("Membuka folder ${repo.displayPath(repo.root)}")
        loadDirectory(repo.root)
    }

    fun toggleRoot() {
        _state.update { it.copy(rootExpanded = !it.rootExpanded) }
        publishTree()
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
                children[directory.id] = list
            } catch (e: Exception) {
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
                val text = repo.readText(entry)
                if (text.indexOf('\u0000') >= 0) {
                    message("${entry.name} terlihat seperti file biner dan tidak dibuka.")
                    return@launch
                }
                val content = withContext(Dispatchers.Default) { Content(text) }
                if (repository !== repo) return@launch
                documents[entry.id] = content
                editVersions[entry.id] = 0
                val tab = TabState(entry.id, entry, LanguageResolver.resolve(entry.name))
                _state.update { it.copy(tabs = it.tabs + tab, activeTabId = tab.id) }
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
        updateTab(tabId) { it.copy(saving = true) }
        _state.update { it.copy(saveStatus = SaveStatus.Saving(tab.entry.name)) }
        viewModelScope.launch {
            try {
                repo.writeText(tab.entry, text)
                val unchanged = (editVersions[tabId] ?: 0) == version
                updateTab(tabId) { it.copy(saving = false, dirty = it.dirty && !unchanged) }
                _state.update { it.copy(saveStatus = SaveStatus.Saved(tab.entry.name)) }
                log("Tersimpan: ${tab.entry.path}")
                if (closeAfter && unchanged) closeTab(tabId, force = true)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                updateTab(tabId) { it.copy(saving = false) }
                _state.update { it.copy(saveStatus = SaveStatus.Failed(tab.entry.name, reason)) }
                log("Gagal menyimpan ${tab.entry.path}: $reason")
                message("Gagal menyimpan ${tab.entry.name}: $reason")
            }
        }
    }

    // ---------------------------------------------------------------- Layout

    fun toggleSidebar() = _state.update { it.copy(sidebarVisible = !it.sidebarVisible) }

    fun togglePanel() = _state.update { it.copy(panelVisible = !it.panelVisible) }

    fun setPanelVisible(visible: Boolean) = _state.update { it.copy(panelVisible = visible) }

    // ---------------------------------------------------------------- Helpers

    private fun updateTab(tabId: String, change: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.id == tabId) change(it) else it }) }
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
