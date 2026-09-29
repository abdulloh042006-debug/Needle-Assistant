package com.needleassistant.app.system

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GroqAssistant(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val hasApiKey: Boolean
        get() = preferences.contains(KEY_CIPHERTEXT) && preferences.contains(KEY_IV)

    fun saveApiKey(apiKey: String) {
        require(apiKey.length >= MINIMUM_KEY_LENGTH && apiKey.none(Char::isWhitespace)) {
            "API kalit noto'g'ri ko'rinadi."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
        ) { "API kalitni saqlab bo'lmadi." }
    }

    fun removeApiKey() {
        check(preferences.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()) {
            "Saqlangan API kalitni o'chirib bo'lmadi."
        }
    }

    suspend fun ask(messages: List<ChatTurn>): String = withContext(Dispatchers.IO) {
        val apiKey = readApiKey()
        val connection = (URL(CHAT_COMPLETIONS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            val requestBody = JSONObject()
                .put("model", MODEL)
                .put("temperature", 0.7)
                .put("messages", JSONArray().apply {
                    put(JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "Sen Needle Assistant nomli aqlli va foydali o'zbekcha yordamchisan. " +
                                "Savollarga aniq, tushunarli va qisqa javob ber. Qurilmada biror amalni " +
                                "bajarishni so'rashsa, uni o'zing bajarolmasligingni ayt va buyruq " +
                                "sifatida qo'llab-quvvatlanadigan imkoniyatlarni tushuntir."
                        ))
                    messages.takeLast(MAX_CONTEXT_TURNS).forEach { turn ->
                        put(JSONObject().put("role", turn.role).put("content", turn.content))
                    }
                })
                .toString()
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw toApiException(responseCode, responseBody)

            val answer = JSONObject(responseBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()
            if (answer.isBlank()) throw GroqException("AI bo'sh javob qaytardi. Yana urinib ko'ring.")
            answer
        } catch (exception: GroqException) {
            throw exception
        } catch (exception: IOException) {
            throw GroqException("Internetga ulanib bo'lmadi. Tarmoqni tekshirib, qayta urinib ko'ring.", exception)
        } catch (exception: Exception) {
            throw GroqException("AI javobini olishda xatolik yuz berdi. Qayta urinib ko'ring.", exception)
        } finally {
            connection.disconnect()
        }
    }

    private fun readApiKey(): String {
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
            ?: throw GroqException("Avval Groq API kalitini sozlang.")
        val encodedIv = preferences.getString(KEY_IV, null)
            ?: throw GroqException("Saqlangan API kalitni o'qib bo'lmadi. Kalitni qayta sozlang.")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (exception: Exception) {
            throw GroqException("API kalitni ochib bo'lmadi. Kalitni qayta sozlang.", exception)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun toApiException(statusCode: Int, body: String): GroqException {
        val providerMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        val message = when (statusCode) {
            401, 403 -> "Groq API kaliti yaroqsiz yoki ruxsat berilmagan. Kalitni tekshiring."
            429 -> "Groq so'rovlar limiti tugadi. Biroz kutib qayta urinib ko'ring."
            else -> providerMessage.takeIf(String::isNotBlank)
                ?: "Groq xizmatida xatolik ($statusCode). Keyinroq qayta urinib ko'ring."
        }
        return GroqException(message)
    }

    companion object {
        private const val PREFERENCES = "groq_credentials"
        private const val KEY_ALIAS = "needle_groq_api_key"
        private const val KEY_IV = "api_key_iv"
        private const val KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MINIMUM_KEY_LENGTH = 20
        private const val MAX_CONTEXT_TURNS = 12
        private const val TIMEOUT_MILLIS = 30_000
        private const val MODEL = "openai/gpt-oss-120b"
        private const val CHAT_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions"
    }
}

data class ChatTurn(val role: String, val content: String)

class GroqException(message: String, cause: Throwable? = null) : Exception(message, cause)
