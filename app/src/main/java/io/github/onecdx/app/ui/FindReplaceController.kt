package io.github.onecdx.app.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.github.onecdx.app.databinding.ActivityMainBinding
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.subscribeAlways
import java.util.regex.PatternSyntaxException

/** Bar Find & Replace (Ctrl+F / Ctrl+H) di atas editor, memakai EditorSearcher milik sora-editor. */
class FindReplaceController(private val b: ActivityMainBinding) {

    private val editor get() = b.editor
    private var caseSensitive = false
    private var regex = false
    private var invalidPattern = false

    val isOpen: Boolean get() = b.findBar.isVisible

    init {
        b.findInput.doAfterTextChanged { search() }
        b.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { next(); true } else false
        }
        b.findInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (event.isShiftPressed) previous() else next()
                true
            } else {
                false
            }
        }
        b.replaceInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                replaceOne(); true
            } else {
                false
            }
        }
        b.findPrev.setOnClickListener { previous() }
        b.findNext.setOnClickListener { next() }
        b.findClose.setOnClickListener { close() }
        b.findToggleReplace.setOnClickListener { setReplaceVisible(!b.replaceRow.isVisible) }
        b.findCase.setOnClickListener {
            caseSensitive = !caseSensitive
            it.isActivated = caseSensitive
            search()
        }
        b.findRegex.setOnClickListener {
            regex = !regex
            it.isActivated = regex
            search()
        }
        b.replaceOne.setOnClickListener { replaceOne() }
        b.replaceAll.setOnClickListener { replaceAll() }
        editor.subscribeAlways<PublishSearchResultEvent> { updateCount() }
    }

    fun open(withReplace: Boolean) {
        b.findBar.isVisible = true
        if (withReplace) setReplaceVisible(true)
        val cursor = editor.cursor
        if (cursor.isSelected) {
            val selected = editor.text.subSequence(cursor.left, cursor.right).toString()
            if (selected.isNotEmpty() && '\n' !in selected) b.findInput.setText(selected)
        }
        val focus = if (withReplace && b.findInput.text.isNotEmpty()) b.replaceInput else b.findInput
        focus.requestFocus()
        focus.selectAll()
        search()
    }

    fun close() {
        if (!isOpen) return
        b.findBar.isVisible = false
        editor.searcher.stopSearch()
        editor.requestFocus()
    }

    /** Dipanggil setelah editor berganti dokumen (pindah tab). */
    fun onDocumentChanged() {
        if (isOpen) search()
    }

    private fun setReplaceVisible(visible: Boolean) {
        b.replaceRow.isVisible = visible
        b.findToggleReplace.text = if (visible) "⌄" else "›"
    }

    private fun search() {
        val query = b.findInput.text.toString()
        invalidPattern = false
        if (query.isEmpty()) {
            editor.searcher.stopSearch()
            updateCount()
            return
        }
        val type = if (regex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL
        try {
            editor.searcher.search(query, SearchOptions(type, !caseSensitive))
        } catch (e: PatternSyntaxException) {
            invalidPattern = true
            editor.searcher.stopSearch()
        }
        updateCount()
    }

    private fun next() = guarded { if (editor.searcher.hasQuery()) editor.searcher.gotoNext() }

    private fun previous() = guarded { if (editor.searcher.hasQuery()) editor.searcher.gotoPrevious() }

    private fun replaceOne() = guarded {
        if (editor.searcher.hasQuery()) editor.searcher.replaceCurrentMatch(b.replaceInput.text.toString())
    }

    private fun replaceAll() = guarded {
        if (editor.searcher.hasQuery()) editor.searcher.replaceAll(b.replaceInput.text.toString())
    }

    /** EditorSearcher melempar IllegalStateException jika hasil pencarian belum siap. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            // Hasil pencarian masih dihitung di background; abaikan.
        }
        updateCount()
    }

    private fun updateCount() {
        val searcher = editor.searcher
        b.findCount.text = when {
            invalidPattern -> "Regex tidak valid"
            !searcher.hasQuery() -> ""
            else -> {
                val count = searcher.matchedPositionCount
                val index = searcher.currentMatchedPositionIndex
                when {
                    count == 0 -> "Tidak ada hasil"
                    index >= 0 -> "${index + 1} dari $count"
                    else -> "$count hasil"
                }
            }
        }
    }
}
