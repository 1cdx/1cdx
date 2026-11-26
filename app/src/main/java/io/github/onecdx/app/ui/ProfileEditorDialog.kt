package io.github.onecdx.app.ui

import android.app.AlertDialog
import android.content.DialogInterface
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.onecdx.app.R
import io.github.onecdx.app.data.sftp.fingerprintOf
import io.github.onecdx.app.databinding.DialogProfileBinding
import io.github.onecdx.app.domain.AuthMethod
import io.github.onecdx.app.domain.ServerProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tambah/edit profil SFTP dengan tombol Test Connection (README §4.A).
 * Host key yang dipercaya saat test ikut tersimpan bersama profil.
 */
class ProfileEditorDialog(
    private val activity: MainActivity,
    private val original: ServerProfile?,
    private val vm: WorkbenchViewModel,
    private val onClosed: () -> Unit,
) {
    private val b = DialogProfileBinding.inflate(activity.layoutInflater)
    private var privateKey: String? = original?.privateKey
    private var privateKeyName: String? = original?.privateKeyName
    private var hostKey: String? = original?.hostKey
    private var testJob: Job? = null

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(if (original == null) R.string.add_server else R.string.edit_server)
        .setView(b.root)
        .setPositiveButton(R.string.save, null)
        .setNeutralButton(R.string.test_connection, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    init {
        original?.let { p ->
            b.profileName.setText(p.name)
            b.profileHost.setText(p.host)
            b.profilePort.setText(p.port.toString())
            b.profileUsername.setText(p.username)
            b.profilePassword.setText(p.password.orEmpty())
            b.profilePassphrase.setText(p.passphrase.orEmpty())
            b.profileRemotePath.setText(p.remotePath)
        }
        b.profileAuth.check(if (original?.auth == AuthMethod.KEY) R.id.profile_auth_key else R.id.profile_auth_password)
        b.profileAuth.setOnCheckedChangeListener { _, _ -> renderAuth() }
        b.profilePickKey.setOnClickListener {
            activity.pickPrivateKey { name, text ->
                privateKey = text
                privateKeyName = name
                renderAuth()
            }
        }
        b.profileForgetHostKey.setOnClickListener {
            hostKey = null
            renderHostKey()
        }
        renderAuth()
        renderHostKey()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { save() }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { test() }
        }
        dialog.setOnDismissListener {
            testJob?.cancel()
            onClosed()
        }
    }

    fun show() {
        dialog.show()
        (if (original == null) b.profileHost else b.profileName).requestFocus()
    }

    private val selectedAuth: AuthMethod
        get() = if (b.profileAuth.checkedRadioButtonId == R.id.profile_auth_key) AuthMethod.KEY else AuthMethod.PASSWORD

    private fun renderAuth() {
        val key = selectedAuth == AuthMethod.KEY
        b.profilePasswordGroup.isVisible = !key
        b.profileKeyGroup.isVisible = key
        b.profileKeyName.text = privateKeyName ?: activity.getString(
            if (privateKey != null) R.string.field_private_key else R.string.no_key_selected,
        )
    }

    private fun renderHostKey() {
        val entry = hostKey
        b.profileHostKeyGroup.isVisible = entry != null
        b.profileHostKey.text = entry?.let { "Host key dipercaya:\n" + (fingerprintOf(it) ?: it.take(40)) }
    }

    /** Isian form sebagai profil; null (dengan pesan di status) bila port tidak valid. */
    private fun draft(): ServerProfile? {
        val portText = b.profilePort.text.toString().trim()
        val port = if (portText.isEmpty()) ServerProfile.DEFAULT_PORT else portText.toIntOrNull()
        if (port == null || port !in 1..65535) {
            showStatus("Port harus angka 1–65535", error = true)
            return null
        }
        val auth = selectedAuth
        var profile = (original ?: ServerProfile()).copy(
            name = b.profileName.text.toString(),
            host = b.profileHost.text.toString(),
            port = port,
            username = b.profileUsername.text.toString(),
            auth = auth,
            // Simpan hanya kredensial untuk metode yang dipilih
            password = if (auth == AuthMethod.PASSWORD) b.profilePassword.text.toString() else null,
            privateKey = if (auth == AuthMethod.KEY) privateKey else null,
            privateKeyName = if (auth == AuthMethod.KEY) privateKeyName else null,
            passphrase = if (auth == AuthMethod.KEY) b.profilePassphrase.text.toString().ifEmpty { null } else null,
            remotePath = b.profileRemotePath.text.toString(),
            hostKey = hostKey,
        ).normalized()
        // Host/port berubah: host key lama milik server lain, jangan dipakai lagi
        if (original != null && profile.targetChangedFrom(original) && profile.hostKey == original.hostKey) {
            profile = profile.copy(hostKey = null)
        }
        val errors = profile.validate()
        if (errors.isNotEmpty()) {
            showStatus(errors.joinToString("\n"), error = true)
            return null
        }
        return profile
    }

    private fun save() {
        val profile = draft() ?: return
        vm.saveProfile(profile)
        dialog.dismiss()
    }

    private fun test() {
        val profile = draft() ?: return
        testJob?.cancel()
        val testButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        testButton.isEnabled = false
        showStatus("Menguji koneksi ke ${profile.address}…", error = false)
        testJob = activity.lifecycleScope.launch {
            try {
                when (val outcome = vm.testConnection(profile)) {
                    is TestOutcome.Success -> showStatus("✓ Berhasil terhubung. Folder root: ${outcome.rootPath}", ok = true)
                    is TestOutcome.UnknownHostKey -> {
                        showStatus("Menunggu konfirmasi host key…", error = false)
                        activity.confirmHostKey(profile, outcome.key) {
                            hostKey = outcome.key.entry
                            renderHostKey()
                            test()
                        }
                    }
                    is TestOutcome.ChangedHostKey -> {
                        showStatus("⚠ Host key server berubah, koneksi ditolak.", error = true)
                        activity.showHostKeyChanged(profile, outcome.key)
                    }
                    is TestOutcome.Failure -> showStatus("⚠ ${outcome.reason}", error = true)
                }
            } finally {
                testButton.isEnabled = true
            }
        }
    }

    private fun showStatus(text: String, error: Boolean = false, ok: Boolean = false) {
        b.profileStatus.isVisible = true
        b.profileStatus.text = text
        b.profileStatus.setTextColor(
            activity.getColor(
                when {
                    error -> R.color.error
                    ok -> R.color.ok
                    else -> R.color.text_muted
                },
            ),
        )
    }
}
