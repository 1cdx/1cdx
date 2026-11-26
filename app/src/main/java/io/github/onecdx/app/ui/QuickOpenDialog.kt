package io.github.onecdx.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.github.onecdx.app.R
import io.github.onecdx.app.domain.FileEntry
import io.github.onecdx.app.domain.FileIcons
import io.github.onecdx.app.domain.FuzzyMatcher

/** Ctrl+P: cari cepat nama file di antara folder yang sudah dimuat explorer. */
class QuickOpenDialog(
    private val context: Context,
    private val files: List<FileEntry>,
    private val onPick: (FileEntry) -> Unit,
) {
    private var results: List<FileEntry> = emptyList()

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_quick_open, null)
        val input = view.findViewById<EditText>(R.id.quick_input)
        val list = view.findViewById<ListView>(R.id.quick_list)
        val empty = view.findViewById<TextView>(R.id.quick_empty)
        val adapter = ResultAdapter()
        list.adapter = adapter

        val dialog = AlertDialog.Builder(context).setView(view).create()

        fun refresh() {
            results = FuzzyMatcher.filter(input.text.toString(), files)
            adapter.notifyDataSetChanged()
            empty.isVisible = results.isEmpty()
            list.isVisible = results.isNotEmpty()
            if (results.isNotEmpty()) list.setItemChecked(0, true)
        }

        fun pick(index: Int) {
            val file = results.getOrNull(index) ?: return
            dialog.dismiss()
            onPick(file)
        }

        input.doAfterTextChanged { refresh() }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> { pick(list.checkedItemPosition.coerceAtLeast(0)); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { move(list, +1); true }
                KeyEvent.KEYCODE_DPAD_UP -> { move(list, -1); true }
                else -> false
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> pick(position) }

        dialog.window?.apply {
            setGravity(Gravity.TOP)
            attributes = attributes.apply { y = (48 * context.resources.displayMetrics.density).toInt() }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        refresh()
        dialog.show()
        input.requestFocus()
    }

    private fun move(list: ListView, delta: Int) {
        if (results.isEmpty()) return
        val next = (list.checkedItemPosition + delta).coerceIn(0, results.size - 1)
        list.setItemChecked(next, true)
        list.smoothScrollToPosition(next)
    }

    private inner class ResultAdapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_quick_open, parent, false)
            val file = results[position]
            val icon = FileIcons.forName(file.name)
            view.findViewById<TextView>(R.id.quick_icon).apply {
                text = icon.label
                setTextColor(icon.color.toInt())
            }
            view.findViewById<TextView>(R.id.quick_name).text = file.name
            view.findViewById<TextView>(R.id.quick_path).text = file.path.substringBeforeLast('/', "")
            return view
        }
    }
}
