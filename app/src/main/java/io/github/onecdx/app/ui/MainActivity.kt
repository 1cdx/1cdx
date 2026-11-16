package io.github.onecdx.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
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
import io.github.onecdx.app.databinding.ActivityMainBinding
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.TreeRow
import io.github.onecdx.app.editor.TextMateSetup
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.subscribeAlways
import kotlinx.coroutines.launch

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

    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openWorkspace(uri)
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
        b.statusWorkspace.text = if (hasWorkspace) "⌂ ${s.workspaceName}" else "Tidak ada folder"
        b.statusSave.text = when (val status = s.saveStatus) {
            SaveStatus.Idle -> ""
            is SaveStatus.Saving -> "Menyimpan ${status.name}…"
            is SaveStatus.Saved -> "Tersimpan ✓  ${status.name}"
            is SaveStatus.Failed -> "⚠ Gagal menyimpan ${status.name}"
        }
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
                menu.add(R.string.menu_refresh).setOnMenuItemClickListener { vm.refresh(entry); true }
            } else {
                menu.add(R.string.menu_open).setOnMenuItemClickListener { vm.openFile(entry); true }
            }
            menu.add(R.string.menu_copy_path).setOnMenuItemClickListener { copyPath(entry); true }
            show()
        }
    }

    private fun showRootMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.menu_refresh).setOnMenuItemClickListener { vm.refresh(null); true }
            menu.add(R.string.open_folder).setOnMenuItemClickListener { openFolder.launch(null); true }
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

    private fun copyPath(entry: FileEntry) {
        val path = vm.displayPath(entry) ?: return
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("path", path))
        Toast.makeText(this, R.string.path_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "1cdx"
        const val KEY_SIDEBAR_WIDTH = "sidebar_width"
        const val KEY_PANEL_HEIGHT = "panel_height"
    }
}
