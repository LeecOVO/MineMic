package com.aiaixyz.minemic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext stays outside backups; the non-exportable key stays in Android Keystore. */
internal class ConnectionStateStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "minemic-connection-recovery-v1"))
    private val alias = "MineMic.connection-recovery.v1"
    private fun key(create: Boolean): SecretKey? {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized fun load(): String? = runCatching {
        if (!file.baseFile.exists() || file.baseFile.length() !in 29..4096) return null
        val bytes = file.readFully(); val secret = key(false) ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString(throwOnInvalidSequence = true)
                .takeIf { it.length <= 2048 }
        }
    }.getOrNull()
    @Synchronized fun save(state: String?) {
        if (state == null) { file.delete(); return }
        require(state.length <= 2048)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val bytes = cipher.iv + cipher.doFinal(state.encodeToByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
