package io.github.onecdx.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.onecdx.app.databinding.ItemTreeBinding
import io.github.onecdx.app.domain.FileIcons
import io.github.onecdx.app.domain.TreeRow

class FileTreeAdapter(
    private val onClick: (TreeRow) -> Unit,
    private val onContextMenu: (View, TreeRow) -> Unit,
) : ListAdapter<TreeRow, FileTreeAdapter.Holder>(Diff) {

    var activeId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            notifyRowChanged(old)
            notifyRowChanged(value)
        }

    private fun notifyRowChanged(id: String?) {
        if (id == null) return
        val index = currentList.indexOfFirst { it.entry.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTreeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemTreeBinding) : RecyclerView.ViewHolder(b.root) {
        private var row: TreeRow? = null

        init {
            b.root.setOnClickListener { row?.let(onClick) }
            // Klik kanan mouse (DeX) dan long-press membuka menu konteks yang sama
            b.root.setOnContextClickListener { v -> row?.let { onContextMenu(v, it) }; true }
            b.root.setOnLongClickListener { v -> row?.let { onContextMenu(v, it) }; true }
        }

        fun bind(row: TreeRow) {
            this.row = row
            val density = b.root.resources.displayMetrics.density
            b.root.setPaddingRelative(((8 + row.depth * 12) * density).toInt(), 0, b.root.paddingEnd, 0)
            val entry = row.entry
            b.treeChevron.text = when {
                !entry.isDirectory -> ""
                row.loading -> "…"
                row.expanded -> "▾"
                else -> "▸"
            }
            val icon = FileIcons.forEntry(entry)
            b.treeIcon.text = if (entry.isDirectory) "▬" else icon.label
            b.treeIcon.setTextColor(icon.color.toInt())
            b.treeName.text = entry.name
            b.root.isActivated = entry.id == activeId
        }
    }

    private object Diff : DiffUtil.ItemCallback<TreeRow>() {
        override fun areItemsTheSame(a: TreeRow, b: TreeRow) = a.entry.id == b.entry.id
        override fun areContentsTheSame(a: TreeRow, b: TreeRow) = a == b
    }
}
