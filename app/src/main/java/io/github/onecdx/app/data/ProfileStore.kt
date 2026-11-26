package io.github.onecdx.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.onecdx.app.domain.ProfileCodec
import io.github.onecdx.app.domain.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan profil server (termasuk password, private key, passphrase, host key) dalam satu file
 * terenkripsi AES-256-GCM. Kuncinya dibuat dan disimpan di Android Keystore sehingga tidak pernah
 * keluar dari perangkat dan tidak ada kredensial yang tersimpan sebagai plain text.
 *
 * Format file: `[versi:1][panjang IV:1][IV][ciphertext+tag]`.
 */
class ProfileStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()

    suspend fun load(): List<ServerProfile> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = try {
                file.readFully()
            } catch (e: java.io.FileNotFoundException) {
                return@withContext emptyList()
            }
            ProfileCodec.decode(decrypt(bytes).toString(Charsets.UTF_8))
        }
    }

    suspend fun save(profiles: List<ServerProfile>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val encrypted = encrypt(ProfileCodec.encode(profiles).toByteArray(Charsets.UTF_8))
            val out = file.startWrite()
            try {
                out.write(encrypted)
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
                throw e
            }
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(FORMAT_VERSION).put(iv.size.toByte()).put(iv).put(sealed)
            .array()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(data)
        if (data.size < 2 || buffer.get() != FORMAT_VERSION) throw IOException("Format file profil tidak dikenal")
        val iv = ByteArray(buffer.get().toInt() and 0xFF)
        if (iv.isEmpty() || buffer.remaining() < iv.size) throw IOException("File profil rusak")
        buffer.get(iv)
        val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "servers.enc"
        const val KEY_ALIAS = "1cdx_server_profiles"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}
