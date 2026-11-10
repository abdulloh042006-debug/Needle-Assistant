package com.needleassistant.app.system

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
 * Stores the DeepSeek API key encrypted with an AES key protected by Android Keystore.
 * The encrypted preference is excluded from Android backup because the Keystore key
 * is device-bound and cannot be restored onto another device.
 */
class ApiCredentialManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun hasApiKey(): Boolean = getApiKey() != null

    fun saveApiKey(value: String) {
        val apiKey = value.trim()
        require(apiKey.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH) {
            "API token uzunligi noto'g'ri."
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)

        check(
            preferences.edit()
                .putString(KEY_ENCRYPTED_API_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) { "API tokenni saqlab bo'lmadi." }
    }

    fun getApiKey(): String? {
        val encoded = preferences.getString(KEY_ENCRYPTED_API_KEY, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_LENGTH)
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.getOrNull()
    }

    fun clearApiKey() {
        preferences.edit().remove(KEY_ENCRYPTED_API_KEY).commit()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "needle_api_credentials"
        const val KEY_ENCRYPTED_API_KEY = "deepseek_api_key"
        const val KEY_ALIAS = "needle.deepseek.api.aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val MIN_KEY_LENGTH = 10
        const val MAX_KEY_LENGTH = 4096
    }
}
