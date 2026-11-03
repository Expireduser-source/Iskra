package ru.iskra.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the owner's API key encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available, non-exportable). The plaintext key is never logged or shown.
 */
class KeyVault(context: Context, private val slot: String = "api_key") {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs.contains(slot)

    fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) { clear(); return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val packed = b64(cipher.iv) + ":" + b64(ct)
        prefs.edit().putString(slot, packed).apply()
    }

    /** Returns the key or null if absent / undecryptable (e.g. keystore reset). */
    fun load(): String? {
        val packed = prefs.getString(slot, null) ?: return null
        return try {
            val (iv, ct) = packed.split(":").let { unb64(it[0]) to unb64(it[1]) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = prefs.edit().remove(slot).apply()

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "iskra_api_key_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
