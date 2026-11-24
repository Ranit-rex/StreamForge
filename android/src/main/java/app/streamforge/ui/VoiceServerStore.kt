package app.streamforge.ui

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the voice endpoint at rest with a device-bound Android Keystore key. */
object VoiceServerStore {
    private const val PREFS = "streamforge_party"
    private const val VALUE = "voice_server_url_enc"
    private const val ALIAS = "streamforge_voice_server_url"

    fun save(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(value.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP))
                .remove("voice_server_url")
                .apply()
        }.onFailure {
            // Never let a device Keystore failure crash the entire app on startup.
            prefs.edit().remove(VALUE).remove("voice_server_url").apply()
        }
    }

    fun read(context: Context, fallback: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            val packedValue = prefs.getString(VALUE, null)
            if (packedValue.isNullOrBlank()) {
                val legacy = prefs.getString("voice_server_url", null)
                val result = legacy?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: fallback
                save(context, result)
                result
            } else {
                val packed = Base64.decode(packedValue, Base64.NO_WRAP)
                require(packed.size > 12) { "Invalid encrypted voice endpoint" }
                val iv = packed.copyOfRange(0, 12)
                val encrypted = packed.copyOfRange(12, packed.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8).trim().trimEnd('/').ifBlank { fallback }
            }
        }.getOrElse {
            prefs.edit().remove(VALUE).remove("voice_server_url").apply()
            fallback
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
