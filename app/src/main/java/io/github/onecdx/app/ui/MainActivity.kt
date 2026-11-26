package io.github.onecdx.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.onecdx.app.R
import io.github.onecdx.app.data.sftp.PresentedHostKey
import io.github.onecdx.app.databinding.ActivityMainBinding
import io.github.onecdx.app.databinding.DialogInputBinding
import io.github.onecdx.app.domain.ConnectionState
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.ServerProfile
import io.github.onecdx.app.domain.TreeRow
import io.github.onecdx.app.editor.TextMateSetup
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.subscribeAlways
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: WorkbenchViewModel by viewModels()
    private val layoutPrefs by lazy { getSharedPreferences("layout", MODE_PRIVATE) }

    private lateinit var treeAdapter: FileTreeAdapter
    private lateinit var tabAdapter: TabAdapter
    private lateinit var find: FindReplaceController

    private var textMateReady = false
    private var editorReady = false
    private var shownTabId: String? = null
    private val scrollPositions = HashMap<String, Pair<Int, Int>>()
    private var lastTree: List<TreeRow>? = null
    private var lastLog: List<String>? = null
    private var lastScrolledTab: String? = null

    private var serversDialog: ConnectionsDialog? = null
    private var keyPicked: ((String, String) -> Unit)? = null

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openWorkspace(uri)
    }

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = keyPicked
        keyPicked = null
        if (uri != null && callback != null) readPrivateKey(uri, callback)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // targetSdk 35: aplikasi digambar edge-to-edge, jadi beri padding sesuai system bar & keyboard
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupExplorer()
        setupTabs()
        setupLayout()
        find = FindReplaceController(b)
        onBackPressedDispatcher.addCallback(this, backHandler)

        lifecycleScope.launch {
            textMateReady = try {
                TextMateSetup.awaitReady()
                true
            } catch (e: Exception) {
                Log.e(TAG, "TextMate gagal dimuat", e)
                false
            }
            setupEditor()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect(::render) }
                launch { vm.events.collect(::handleEvent) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        b.editor.release()
    }

    // ---------------------------------------------------------------- Setup

    private fun setupExplorer() {
        treeAdapter = FileTreeAdapter(
            onClick = vm::onTreeRowClicked,
            onContextMenu = ::showTreeMenu,
        )
        b.fileTree.layoutManager = LinearLayoutManager(this)
        b.fileTree.adapter = treeAdapter
        b.fileTree.itemAnimator = null
        b.workspaceTitle.setOnClickListener { vm.toggleRoot() }
        b.workspaceTitle.setOnContextClickListener { showRootMenu(it); true }
        b.workspaceTitle.setOnLongClickListener { showRootMenu(it); true }
        b.explorerOpenFolder.setOnClickListener { openFolder.launch(null) }
        b.welcomeOpenFolder.setOnClickListener { openFolder.launch(null) }
        b.explorerConnect.setOnClickListener { showServers() }
        b.welcomeConnect.setOnClickListener { showServers() }
        b.explorerRemote.setOnClickListener { showServers() }
        b.explorerNewFile.setOnClickListener { promptCreate(null, directory = false) }
        b.explorerNewFolder.setOnClickListener { promptCreate(null, directory = true) }
        b.explorerRefresh.setOnClickListener { vm.refresh(null) }
    }

    private fun setupTabs() {
        tabAdapter = TabAdapter(
            onSelect = { vm.activateTab(it.id) },
            onClose = { vm.closeTab(it.id) },
            onContextMenu = ::showTabMenu,
        )
        b.tabBar.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.tabBar.adapter = tabAdapter
        b.tabBar.itemAnimator = null
    }

    private fun setupLayout() {
        val density = resources.displayMetrics.density
        layoutPrefs.getInt(KEY_SIDEBAR_WIDTH, 0).takeIf { it > 0 }?.let { w -> b.sidebar.updateLayoutParams { width = w } }
        layoutPrefs.getInt(KEY_PANEL_HEIGHT, 0).takeIf { it > 0 }?.let { h -> b.panel.updateLayoutParams { height = h } }

        DragResizer(
            handle = b.sidebarDivider,
            target = b.sidebar,
            horizontal = true,
            inverted = false,
            minSize = { (160 * density).toInt() },
            maxSize = { (b.workbench.width * 0.6f).toInt() },
            onResized = { layoutPrefs.edit().putInt(KEY_SIDEBAR_WIDTH, it).apply() },
        ).attach()
        DragResizer(
            handle = b.panelDivider,
            target = b.panel,
            horizontal = false,
            inverted = true,
            minSize = { (80 * density).toInt() },
            maxSize = { (b.workbench.height * 0.7f).toInt() },
            onResized = { layoutPrefs.edit().putInt(KEY_PANEL_HEIGHT, it).apply() },
        ).attach()
        b.panelClose.setOnClickListener { vm.setPanelVisible(false) }
    }

    private fun setupEditor() {
        b.editor.apply {
            setTypefaceText(Typeface.MONOSPACE)
            setTextSize(14f)
            setTabWidth(4)
            setLineNumberEnabled(true)
            setPinLineNumber(true)
            setHighlightCurrentLine(true)
            props.stickyScroll = true
            colorScheme = if (textMateReady) TextMateSetup.createColorScheme() else SchemeDarcula()
            subscribeAlways<ContentChangeEvent> { event ->
                if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                    shownTabId?.let(vm::onDocumentEdited)
                }
            }
            subscribeAlways<SelectionChangeEvent> { updateCursorStatus() }
        }
        editorReady = true
    }

    // ---------------------------------------------------------------- Render

    private fun render(s: WorkbenchState) {
        val hasWorkspace = s.workspaceName != null
        b.sidebar.isVisible = s.sidebarVisible
        b.sidebarDivider.isVisible = s.sidebarVisible
        b.workspaceTitle.isVisible = hasWorkspace
        b.workspaceTitle.text = (if (s.rootExpanded) "▾ " else "▸ ") + s.workspaceName.orEmpty().uppercase()
        b.explorerEmpty.isVisible = !hasWorkspace
        b.fileTree.isVisible = hasWorkspace
        b.explorerNewFile.isVisible = hasWorkspace
        b.explorerNewFolder.isVisible = hasWorkspace
        b.explorerRefresh.isVisible = hasWorkspace
        b.explorerConnecting.isVisible = s.connectingTo != null
        b.explorerConnecting.text = s.connectingTo?.let { "Menghubungkan ke $it…" }
        b.explorerConnect.isEnabled = s.connectingTo == null
        b.welcomeConnect.isEnabled = s.connectingTo == null
        serversDialog?.submit(s.profiles, s.activeProfileId)
        if (s.tree !== lastTree) {
            lastTree = s.tree
            treeAdapter.submitList(s.tree)
        }
        treeAdapter.activeId = s.activeTabId

        val activeId = s.activeTabId
        tabAdapter.submitList(s.tabs.map { TabItem(it, it.id == activeId) }) {
            if (activeId != null && activeId != lastScrolledTab) {
                lastScrolledTab = activeId
                val index = s.tabs.indexOfFirst { it.id == activeId }
                if (index >= 0) b.tabBar.smoothScrollToPosition(index)
            }
        }
        scrollPositions.keys.retainAll(s.tabs.map { it.id }.toSet())

        b.panel.isVisible = s.panelVisible
        b.panelDivider.isVisible = s.panelVisible
        if (s.log !== lastLog) {
            lastLog = s.log
            b.logText.text = s.log.joinToString("\n")
            b.logScroll.post { b.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        bindEditor(s.activeTab)
        renderStatus(s)
    }

    private fun bindEditor(tab: TabState?) {
        if (!editorReady || tab?.id == shownTabId) return
        shownTabId?.let { scrollPositions[it] = b.editor.offsetX to b.editor.offsetY }

        if (tab == null) {
            shownTabId = null
            find.close()
            b.editor.setText("")
            b.editor.isVisible = false
            b.welcome.isVisible = true
            updateCursorStatus()
            return
        }
        val content = vm.document(tab.id) ?: return
        shownTabId = tab.id
        b.welcome.isVisible = false
        b.editor.isVisible = true
        b.editor.setEditorLanguage(if (textMateReady) TextMateSetup.createLanguage(tab.language) else EmptyLanguage())
        // reuseContentObject = true: riwayat undo dan posisi kursor tiap tab tetap terjaga
        b.editor.setText(content, true, null)
        find.onDocumentChanged()

        val saved = scrollPositions[tab.id]
        b.editor.post {
            if (saved != null) {
                b.editor.scroller.forceFinished(true)
                b.editor.scroller.startScroll(saved.first, saved.second, 0, 0, 0)
                b.editor.invalidate()
            } else {
                b.editor.ensureSelectionVisible()
            }
        }
        if (!find.isOpen) b.editor.requestFocus()
        updateCursorStatus()
    }

    private fun renderStatus(s: WorkbenchState) {
        val hasWorkspace = s.workspaceName != null
        b.statusBar.setBackgroundColor(getColor(if (hasWorkspace) R.color.statusbar else R.color.statusbar_empty))
        // Indikator koneksi ala "remote indicator" VS Code
        val (workspaceText, workspaceColor) = when (val c = s.connection) {
            ConnectionState.Local -> when {
                hasWorkspace -> "⌂ ${s.workspaceName}" to null
                s.connectingTo != null -> "⟳ Menghubungkan ke ${s.connectingTo}…" to R.color.statusbar_remote
                else -> "Tidak ada folder" to null
            }
            is ConnectionState.Connecting ->
                (if (c.reconnect) "⟳ Menyambung ulang ${c.address}…" else "⟳ Menghubungkan ${c.address}…") to R.color.statusbar_remote
            is ConnectionState.Connected -> "⇅ Connected: ${c.address}" to R.color.statusbar_remote
            is ConnectionState.Disconnected -> "⚠ Terputus: ${c.address}" to R.color.statusbar_error
        }
        b.statusWorkspace.text = workspaceText
        b.statusWorkspace.setBackgroundColor(workspaceColor?.let(::getColor) ?: Color.TRANSPARENT)
        val status = s.saveStatus
        b.statusSave.text = when (status) {
            SaveStatus.Idle -> ""
            is SaveStatus.Saving -> if (status.remote) "⟳ Syncing ${status.name}…" else "Menyimpan ${status.name}…"
            is SaveStatus.Saved -> if (status.remote) "Uploaded ✓  ${status.name}" else "Tersimpan ✓  ${status.name}"
            is SaveStatus.Failed ->
                if (status.cachedLocally) "⚠ Upload gagal: ${status.name} (aman di cache, Ctrl+S untuk coba lagi)"
                else "⚠ Gagal menyimpan ${status.name}"
        }
        b.statusSave.setBackgroundColor(if (status is SaveStatus.Failed) getColor(R.color.statusbar_error) else Color.TRANSPARENT)
        val tab = s.activeTab
        b.statusLanguage.text = tab?.language?.displayName.orEmpty()
        b.statusEncoding.text = if (tab != null) "UTF-8" else ""
        b.statusIndent.text = if (tab != null) "Spasi: 4" else ""
    }

    private fun updateCursorStatus() {
        if (shownTabId == null) {
            b.statusCursor.text = ""
            return
        }
        val cursor = b.editor.cursor
        val position = "Ln ${cursor.leftLine + 1}, Col ${cursor.leftColumn + 1}"
        val selected = cursor.right - cursor.left
        b.statusCursor.text = if (selected > 0) "$position ($selected dipilih)" else position
    }

    // ---------------------------------------------------------------- Events

    private fun handleEvent(event: WorkbenchEvent) {
        when (event) {
            is WorkbenchEvent.Message -> Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            is WorkbenchEvent.ConfirmClose -> AlertDialog.Builder(this)
                .setTitle(R.string.unsaved_title)
                .setMessage(getString(R.string.unsaved_message, event.name))
                .setPositiveButton(R.string.save) { _, _ -> vm.save(event.tabId, closeAfter = true) }
                .setNegativeButton(R.string.dont_save) { _, _ -> vm.closeTab(event.tabId, force = true) }
                .setNeutralButton(R.string.cancel, null)
                .show()
            is WorkbenchEvent.ConfirmHostKey -> confirmHostKey(event.profile, event.key) {
                vm.trustHostKeyAndConnect(event.profile, event.key)
            }
            is WorkbenchEvent.HostKeyChanged -> showHostKeyChanged(event.profile, event.key)
        }
    }

    private val backHandler = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                find.isOpen -> find.close()
                vm.state.value.tabs.any { it.dirty } -> AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.unsaved_title)
                    .setMessage("Ada file yang belum disimpan. Keluar tanpa menyimpan?")
                    .setPositiveButton("Keluar") { _, _ -> finish() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                else -> finish()
            }
        }
    }

    // ---------------------------------------------------------------- Keyboard

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && find.isOpen) {
                find.close()
                return true
            }
            val command = Shortcuts.resolve(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)
            if (command != null) {
                if (event.repeatCount == 0) execute(command)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun execute(command: Command) {
        when (command) {
            Command.SAVE -> vm.saveActive()
            Command.TOGGLE_SIDEBAR -> vm.toggleSidebar()
            Command.CLOSE_TAB -> vm.closeActiveTab()
            Command.QUICK_OPEN -> showQuickOpen()
            Command.FIND -> if (shownTabId != null) find.open(withReplace = false)
            Command.FIND_REPLACE -> if (shownTabId != null) find.open(withReplace = true)
            Command.TOGGLE_PANEL -> vm.togglePanel()
            Command.OPEN_FOLDER -> openFolder.launch(null)
            Command.SERVERS -> showServers()
            Command.NEXT_TAB -> vm.activateRelative(+1)
            Command.PREVIOUS_TAB -> vm.activateRelative(-1)
        }
    }

    private fun showQuickOpen() {
        if (vm.state.value.workspaceName == null) {
            openFolder.launch(null)
            return
        }
        QuickOpenDialog(this, vm.knownFiles(), vm::openFile).show()
    }

    // ---------------------------------------------------------------- Context menus

    private fun showTreeMenu(anchor: View, row: TreeRow) {
        val entry = row.entry
        PopupMenu(this, anchor).apply {
            if (entry.isDirectory) {
                menu.add(R.string.new_file).setOnMenuItemClickListener { promptCreate(entry, directory = false); true }
                menu.add(R.string.new_folder).setOnMenuItemClickListener { promptCreate(entry, directory = true); true }
                menu.add(R.string.menu_refresh).setOnMenuItemClickListener { vm.refresh(entry); true }
            } else {
                menu.add(R.string.menu_open).setOnMenuItemClickListener { vm.openFile(entry); true }
            }
            menu.add(R.string.menu_rename).setOnMenuItemClickListener { promptRename(entry); true }
            menu.add(R.string.menu_delete).setOnMenuItemClickListener { confirmDelete(entry); true }
            menu.add(R.string.menu_copy_path).setOnMenuItemClickListener { copyPath(entry); true }
            show()
        }
    }

    private fun showRootMenu(anchor: View) {
        val remote = vm.state.value.isRemote
        PopupMenu(this, anchor).apply {
            menu.add(R.string.new_file).setOnMenuItemClickListener { promptCreate(null, directory = false); true }
            menu.add(R.string.new_folder).setOnMenuItemClickListener { promptCreate(null, directory = true); true }
            menu.add(R.string.menu_refresh).setOnMenuItemClickListener { vm.refresh(null); true }
            vm.workspaceRoot()?.let { root ->
                menu.add(R.string.menu_copy_path).setOnMenuItemClickListener { copyPath(root); true }
            }
            menu.add(R.string.open_folder).setOnMenuItemClickListener { openFolder.launch(null); true }
            menu.add(R.string.servers).setOnMenuItemClickListener { showServers(); true }
            menu.add(if (remote) R.string.menu_disconnect else R.string.menu_close_workspace)
                .setOnMenuItemClickListener { vm.closeWorkspace(); true }
            show()
        }
    }

    private fun showTabMenu(anchor: View, tab: TabState) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.close).setOnMenuItemClickListener { vm.closeTab(tab.id); true }
            menu.add(R.string.menu_close_others).setOnMenuItemClickListener { vm.closeOtherTabs(tab.id); true }
            menu.add(R.string.menu_copy_path).setOnMenuItemClickListener { copyPath(tab.entry); true }
            show()
        }
    }

    // ---------------------------------------------------------------- Operasi file

    private fun promptCreate(parent: FileEntry?, directory: Boolean) {
        if (vm.workspaceRoot() == null) return
        promptName(getString(if (directory) R.string.new_folder else R.string.new_file), "", R.string.create) { name ->
            vm.createEntry(parent, name, directory)
        }
    }

    private fun promptRename(entry: FileEntry) {
        promptName(getString(R.string.menu_rename), entry.name, R.string.menu_rename) { name -> vm.rename(entry, name) }
    }

    private fun promptName(title: String, initial: String, positive: Int, onOk: (String) -> Unit) {
        val view = DialogInputBinding.inflate(layoutInflater)
        view.input.setText(initial)
        // Seperti VS Code: saat rename, pilih nama tanpa ekstensi
        val dot = initial.lastIndexOf('.')
        view.input.setSelection(0, if (dot > 0) dot else initial.length)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view.root)
            .setPositiveButton(positive) { _, _ -> onOk(view.input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        view.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onOk(view.input.text.toString())
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        view.input.requestFocus()
    }

    private fun confirmDelete(entry: FileEntry) {
        val where = if (vm.state.value.isRemote) "server" else "perangkat"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, entry.name))
            .setMessage(getString(if (entry.isDirectory) R.string.delete_folder_message else R.string.delete_file_message, where))
            .setPositiveButton(R.string.menu_delete) { _, _ -> vm.delete(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- Server SFTP

    private fun showServers() {
        serversDialog?.dismiss()
        val dialog = ConnectionsDialog(
            activity = this,
            onConnect = vm::connect,
            onAdd = { editProfile(null) },
            onEdit = ::editProfile,
            onDuplicate = { vm.duplicateProfile(it.id) },
            onDelete = ::confirmDeleteProfile,
        )
        dialog.setOnDismissListener { if (serversDialog === dialog) serversDialog = null }
        dialog.submit(vm.state.value.profiles, vm.state.value.activeProfileId)
        serversDialog = dialog
        dialog.show()
    }

    private fun editProfile(profile: ServerProfile?) {
        serversDialog?.dismiss()
        ProfileEditorDialog(this, profile, vm, onClosed = ::showServers).show()
    }

    private fun confirmDeleteProfile(profile: ServerProfile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, profile.displayName))
            .setMessage(R.string.delete_server_message)
            .setPositiveButton(R.string.menu_delete) { _, _ -> vm.deleteProfile(profile.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Konfirmasi fingerprint saat pertama kali terhubung ke server (trust on first use). */
    fun confirmHostKey(profile: ServerProfile, key: PresentedHostKey, onTrust: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trust_host_title)
            .setMessage(getString(R.string.trust_host_message, profile.address, key.type, key.fingerprint))
            .setPositiveButton(R.string.trust) { _, _ -> onTrust() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showHostKeyChanged(profile: ServerProfile, key: PresentedHostKey?) {
        val presented = key?.let { "${it.type} ${it.fingerprint}" } ?: "(tidak diketahui)"
        AlertDialog.Builder(this)
            .setTitle(R.string.host_changed_title)
            .setMessage(getString(R.string.host_changed_message, profile.address, presented))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Memilih file private key; isinya dibaca ke memori lalu disimpan terenkripsi bersama profil. */
    fun pickPrivateKey(onPicked: (name: String, text: String) -> Unit) {
        keyPicked = onPicked
        pickKeyFile.launch(arrayOf("*/*"))
    }

    private fun readPrivateKey(uri: Uri, onPicked: (String, String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "private key"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_KEY_BYTES + 1) }
                        ?: error("File tidak bisa dibaca")
                    require(bytes.size <= MAX_KEY_BYTES) { "File terlalu besar untuk private key" }
                    val text = bytes.toString(Charsets.UTF_8)
                    require("PRIVATE KEY" in text || text.startsWith("PuTTY-User-Key-File")) {
                        "Bukan file private key (cari file seperti id_ed25519 atau .pem)"
                    }
                    name to text
                }
            }
            result.fold(
                onSuccess = { (name, text) -> onPicked(name, text) },
                onFailure = { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() },
            )
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun copyPath(entry: FileEntry) {
        val path = vm.displayPath(entry) ?: return
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("path", path))
        Toast.makeText(this, R.string.path_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "1cdx"
        const val KEY_SIDEBAR_WIDTH = "sidebar_width"
        const val KEY_PANEL_HEIGHT = "panel_height"
        const val MAX_KEY_BYTES = 64 * 1024
    }
}
