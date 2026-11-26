package io.github.onecdx.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.PopupMenu
import androidx.core.view.isVisible
import io.github.onecdx.app.R
import io.github.onecdx.app.databinding.DialogConnectionsBinding
import io.github.onecdx.app.databinding.ItemConnectionBinding
import io.github.onecdx.app.domain.ServerProfile

/** Connection Manager (README §4.A): daftar profil SFTP. */
class ConnectionsDialog(
    private val activity: Activity,
    private val onConnect: (ServerProfile) -> Unit,
    private val onAdd: () -> Unit,
    private val onEdit: (ServerProfile) -> Unit,
    private val onDuplicate: (ServerProfile) -> Unit,
    private val onDelete: (ServerProfile) -> Unit,
) {
    private val binding = DialogConnectionsBinding.inflate(activity.layoutInflater)
    private var profiles: List<ServerProfile> = emptyList()
    private var activeId: String? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = profiles.size
        override fun getItem(position: Int) = profiles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = convertView?.tag as? ItemConnectionBinding
                ?: ItemConnectionBinding.inflate(activity.layoutInflater, parent, false).also { it.root.tag = it }
            val profile = profiles[position]
            val connected = profile.id == activeId
            item.connectionName.text = if (connected) "${profile.displayName}  ● terhubung" else profile.displayName
            item.connectionAddress.text = buildString {
                append(profile.address)
                if (profile.remotePath.isNotEmpty()) append(':').append(profile.remotePath)
                append(if (profile.auth == io.github.onecdx.app.domain.AuthMethod.KEY) "  · key" else "  · password")
            }
            item.root.setOnClickListener {
                dialog.dismiss()
                onConnect(profile)
            }
            item.root.setOnLongClickListener { showMenu(it, profile); true }
            item.root.setOnContextClickListener { showMenu(it, profile); true }
            item.connectionMenu.setOnClickListener { showMenu(it, profile) }
            return item.root
        }
    }

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(R.string.servers)
        .setView(binding.root)
        .setPositiveButton(R.string.add_server, null)
        .setNegativeButton(R.string.close, null)
        .create()

    init {
        binding.connectionsList.adapter = adapter
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onAdd()
            }
        }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun setOnDismissListener(listener: () -> Unit) = dialog.setOnDismissListener { listener() }

    fun submit(list: List<ServerProfile>, connectedId: String?) {
        profiles = list
        activeId = connectedId
        binding.connectionsEmpty.isVisible = list.isEmpty()
        binding.connectionsList.isVisible = list.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    fun show() = dialog.show()

    fun dismiss() = dialog.dismiss()

    private fun showMenu(anchor: View, profile: ServerProfile) {
        PopupMenu(activity, anchor).apply {
            menu.add(R.string.menu_connect).setOnMenuItemClickListener { dialog.dismiss(); onConnect(profile); true }
            menu.add(R.string.menu_edit).setOnMenuItemClickListener { dialog.dismiss(); onEdit(profile); true }
            menu.add(R.string.menu_duplicate).setOnMenuItemClickListener { onDuplicate(profile); true }
            menu.add(R.string.menu_delete).setOnMenuItemClickListener { onDelete(profile); true }
            show()
        }
    }
}
