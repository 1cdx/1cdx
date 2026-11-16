package io.github.onecdx.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.onecdx.app.R
import io.github.onecdx.app.databinding.ItemTabBinding
import io.github.onecdx.app.domain.FileIcons

data class TabItem(val tab: TabState, val active: Boolean)

class TabAdapter(
    private val onSelect: (TabState) -> Unit,
    private val onClose: (TabState) -> Unit,
    private val onContextMenu: (View, TabState) -> Unit,
) : ListAdapter<TabItem, TabAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTabBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val b: ItemTabBinding) : RecyclerView.ViewHolder(b.root) {
        private var item: TabItem? = null

        init {
            b.root.setOnClickListener { item?.let { onSelect(it.tab) } }
            b.tabClose.setOnClickListener { item?.let { onClose(it.tab) } }
            b.root.setOnContextClickListener { v -> item?.let { onContextMenu(v, it.tab) }; true }
            b.root.setOnLongClickListener { v -> item?.let { onContextMenu(v, it.tab) }; true }
        }

        fun bind(item: TabItem) {
            this.item = item
            val tab = item.tab
            val icon = FileIcons.forName(tab.entry.name)
            b.tabIcon.text = icon.label
            b.tabIcon.setTextColor(icon.color.toInt())
            b.tabName.text = tab.entry.name
            val color = if (item.active) R.color.text_bright else R.color.text_muted
            b.tabName.setTextColor(b.root.context.getColor(color))
            // Perubahan belum disimpan ditandai titik, seperti VS Code
            b.tabClose.text = if (tab.dirty) "●" else "✕"
            b.root.isActivated = item.active
            b.root.tooltipText = tab.entry.path
        }
    }

    private object Diff : DiffUtil.ItemCallback<TabItem>() {
        override fun areItemsTheSame(a: TabItem, b: TabItem) = a.tab.id == b.tab.id
        override fun areContentsTheSame(a: TabItem, b: TabItem) = a == b
    }
}
