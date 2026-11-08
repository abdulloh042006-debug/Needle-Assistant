package com.needleassistant.app.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for the user-supplied DeepSeek Flash text endpoint.
 *
 * The endpoint is a GET-only text proxy, not an OpenAI-compatible API. Keep
 * provider-specific transport and response normalization isolated here.
 */
class DeepSeekAssistant {

    suspend fun process(messages: List<ChatTurn>): AssistantDecision = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(messages)
        val rawResponse = request(prompt)
        parseDecision(rawResponse)
    }

    private fun buildPrompt(messages: List<ChatTurn>): String = buildString {
        appendLine(SYSTEM_INSTRUCTIONS)
        appendLine()
        appendLine("SUHBAT:")
        messages.takeLast(MAX_CONTEXT_TURNS).forEach { turn ->
            val role = if (turn.role == "assistant") "Needle" else "Foydalanuvchi"
            appendLine("$role: ${turn.content.take(MAX_TURN_CHARS)}")
        }
        appendLine()
        append("Faqat ko'rsatilgan JSON formatlaridan birida javob ber. Markdown ishlatma.")
    }

    private fun request(prompt: String): String {
        val encodedPrompt = URLEncoder.encode(prompt, Charsets.UTF_8.name())
        val url = URL("$ENDPOINT?text=$encodedPrompt")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "Needle-Assistant/1.0")
        }

        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw DeepSeekException(
                    when (responseCode) {
                        401, 403 -> "DeepSeek API so'rovga ruxsat bermadi."
                        429 -> "DeepSeek so'rovlar limiti tugadi. Birozdan keyin qayta urinib ko'ring."
                        else -> "DeepSeek xizmatida xatolik ($responseCode)."
                    }
                )
            }
            if (body.isBlank()) throw DeepSeekException("DeepSeek bo'sh javob qaytardi.")
            if (body.length > MAX_RESPONSE_CHARS) {
                throw DeepSeekException("DeepSeek javobi kutilgan hajmdan katta.")
            }
            return extractText(body)
        } catch (exception: DeepSeekException) {
            throw exception
        } catch (exception: IOException) {
            throw DeepSeekException("Internetga ulanib bo'lmadi. Tarmoqni tekshirib, qayta urinib ko'ring.", exception)
        } catch (exception: Exception) {
            throw DeepSeekException("DeepSeek javobini olishda xatolik yuz berdi.", exception)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(body: String): String {
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return body.trim()
        return findText(json) ?: body.trim()
    }

    private fun findText(json: JSONObject): String? {
        if (json.has("type") && (json.has("command") || json.has("answer"))) {
            return json.toString()
        }
        val directKeys = listOf("response", "answer", "text", "content", "output", "result")
        directKeys.forEach { key ->
            val value = json.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is JSONObject) findText(value)?.let { return it }
        }
        json.optJSONObject("message")?.let { message ->
            message.optString("content").takeIf(String::isNotBlank)?.let { return it.trim() }
        }
        json.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
            choice.optJSONObject("message")?.optString("content")
                ?.takeIf(String::isNotBlank)?.let { return it.trim() }
            choice.optString("text").takeIf(String::isNotBlank)?.let { return it.trim() }
        }
        json.optJSONObject("data")?.let { findText(it)?.let { text -> return text } }
        return null
    }

    private fun parseDecision(response: String): AssistantDecision {
        val candidate = response
            .replace(Regex("""(?s)^\s*```(?:json)?\s*|\s*```\s*$"""), "")
            .trim()
        val jsonText = candidate.substringAfter('{', "").let { inner ->
            if (inner.isEmpty()) "" else "{${inner.substringBeforeLast('}', inner)}}"
        }
        val json = runCatching { JSONObject(jsonText) }.getOrNull()
        if (json != null) {
            when (json.optString("type").lowercase()) {
                "command" -> {
                    val command = normalizeCommand(json.optString("command"))
                    if (command != null && isSupportedCommandShape(command)) {
                        return AssistantDecision(command = command)
                    }
                    return AssistantDecision(answer = "Bu amal Needle tomonidan xavfsiz bajariladigan buyruqlar qatoriga kirmaydi. Boshqa usulda so'rab ko'ring.")
                }
                "chat" -> {
                    val answer = json.optString("answer").trim()
                    if (answer.isNotBlank()) return AssistantDecision(answer = answer)
                }
            }
        }
        return AssistantDecision(answer = response.trim().ifBlank {
            "DeepSeek tushunarli javob qaytarmadi. Qayta urinib ko'ring."
        })
    }

    private fun normalizeCommand(rawCommand: String): String? {
        val value = rawCommand.trim().lowercase()
        val aliases = mapOf(
            "flashlight_on" to "fonarni yoq",
            "turn_on_flashlight" to "fonarni yoq",
            "flashlight_off" to "fonarni o'chir",
            "turn_off_flashlight" to "fonarni o'chir",
            "get_battery" to "batareya necha foiz",
            "battery_status" to "batareya necha foiz",
            "open_wifi_settings" to "wifi sozlamalarini och",
            "open_bluetooth_settings" to "bluetooth sozlamalarini och",
            "volume_up" to "ovozni ko'tar",
            "volume_down" to "ovozni past qil",
            "media_play" to "musiqani qo'y",
            "play_music" to "musiqani qo'y",
            "media_pause" to "musiqani to'xtat",
            "pause_music" to "musiqani to'xtat",
            "media_next" to "keyingi qo'shiq",
            "next_track" to "keyingi qo'shiq",
            "media_previous" to "oldingi trek",
            "previous_track" to "oldingi trek",
            "scroll_down" to "pastga scroll",
            "scroll_up" to "yuqoriga scroll",
            "open_camera" to "kamerani och",
            "open_calculator" to "kalkulyatorni och",
            "open_telegram" to "telegramni och",
            "open_youtube" to "youtubeni och",
            "open_instagram" to "instagramni och",
            "open_settings" to "sozlamalarni och",
            "open_browser" to "brauzerni och"
        )
        aliases[value]?.let { return it }

        val appMatch = Regex("""(?i)^(?:open_app|open app|ilovani och)\s*[:=(]?\s*["']?(.+?)["']?\\)?$""")
            .matchEntire(rawCommand.trim())
        if (appMatch != null) return "${appMatch.groupValues[1].trim()} ilovasini och"

        val english = when {
            value.contains("turn on") && (value.contains("flashlight") || value.contains("torch")) -> "fonarni yoq"
            value.contains("turn off") && (value.contains("flashlight") || value.contains("torch")) -> "fonarni o'chir"
            value.contains("battery") && (value.contains("level") || value.contains("charge")) -> "batareya necha foiz"
            value.contains("open") && value.contains("camera") -> "kamerani och"
            value.contains("open") && value.contains("calculator") -> "kalkulyatorni och"
            value.contains("open") && value.contains("telegram") -> "telegramni och"
            value.contains("open") && value.contains("youtube") -> "youtubeni och"
            value.contains("open") && value.contains("instagram") -> "instagramni och"
            else -> null
        }
        return english ?: rawCommand.trim().takeIf(String::isNotBlank)
    }

    /**
     * AI may only translate intent into an existing DeviceController command.
     * It cannot request arbitrary taps, typing, permission changes, or payments.
     */
    private fun isSupportedCommandShape(command: String): Boolean {
        val value = command.lowercase().trim()
        val hasOpenVerb = listOf("och", "oching", "open", "start", "ishga tushir").any(value::contains)
        return listOf(
            "fonar", "chiroq", "flashlight", "batareya", "quvvat", "zaryad",
            "wi-fi", "wifi", "bluetooth", "blutus", "ovoz", "musiqa", "qo'shiq",
            "qoshiq", "keyingi", "oldingi", "pauza", "pause", "scroll", "pastga",
            "yuqoriga", "telegram", "sms", "xabar", "qo'ng'iroq", "qongiroq",
            "telefon qil", "raqam ter", "kamera", "kalkulyator", "youtube",
            "yutub", "instagram", "chrome", "brauzer", "sozlama", "settings"
        ).any(value::contains) || hasOpenVerb
    }

    companion object {
        private const val ENDPOINT =
            "https://bymuslim.shop/API/deepseek-v4.1-flash-1017-fast.php"
        private const val TIMEOUT_MILLIS = 30_000
        private const val MAX_CONTEXT_TURNS = 6
        private const val MAX_TURN_CHARS = 350
        private const val MAX_RESPONSE_CHARS = 100_000

        private val SYSTEM_INSTRUCTIONS = """
            Sen Needle Assistant nomli o'zbekcha Android yordamchisisan. Foydalanuvchi o'zbek,
            rus yoki ingliz tilida yozishi mumkin. Niyatni, imlo xatolarini, og'zaki iboralarni
            va kontekstni tushun. Javoblarni foydalanuvchi tilida, aniq va tabiiy ber.

            Har bir so'rov uchun quyidagi ikki JSON shaklidan faqat bittasini qaytar:
            1) Qurilmada mavjud va xavfsiz bajariladigan amal bo'lsa:
               {"type":"command","command":"DeviceController tushunadigan qisqa standart buyruq"}
            2) Oddiy savol, suhbat yoki qo'llab-quvvatlanmaydigan amal bo'lsa:
               {"type":"chat","answer":"foydalanuvchiga javob"}

            Mavjud qurilma amallari: ilovani nomi bilan ochish; kamera, kalkulyator,
            Telegram, YouTube, Instagram, brauzer va sozlamalarni ochish; fonarni yoqish/o'chirish;
            batareya foizini ko'rsatish; Wi-Fi/Bluetooth sozlamalarini ochish; media ijrosini
            play/pause, keyingi/oldingi trek; Accessibility ruxsati yoqilgan bo'lsa scroll;
            Telegram chatini ochish yoki xabar matnini tayyorlash; SMS matnini tayyorlash;
            telefon raqami uchun terish oynasini ochish; ovoz balandligini oshirish/pasaytirish.

            Buyruqni mavjud mahalliy parser taniydigan shaklda yoz: masalan "fonarni yoq",
            "batareya necha foiz", "Wi-Fi sozlamalarini och", "ovozni ko'tar",
            "musiqani to'xtat", "keyingi qo'shiq", "pastga scroll", "kamerani och",
            "Telegramni och", "telegram @username ga yoz salom",
            "sms +998901234567 salom", "qo'ng'iroq +998901234567",
            "Chrome ilovasini och".

            Xavfsizlik: hech qachon o'zingdan yangi qurilma vakolatlarini o'ylab topma.
            Boshqa ilovalarda tugma bosish, matn kiritish, ekranni kuzatish, ilova o'chirish,
            fayl o'chirish, to'lov, parol yoki maxfiy ma'lumotlarni boshqarish amallari yo'q.
            Qo'ng'iroq va SMS faqat tayyorlash/terish oynasini ochadi; yuborish yoki qo'ng'iroqni
            boshlashni foydalanuvchi o'zi tasdiqlaydi. Buyruq noaniq yoki xavfli bo'lsa,
            {"type":"chat","answer":"aniqlashtiruvchi savol yoki xavfsiz izoh"} qaytar.
            JSON tashqarisida hech qanday matn yozma.
        """.trimIndent()
    }
}

data class ChatTurn(val role: String, val content: String)

data class AssistantDecision(
    val command: String? = null,
    val answer: String? = null
)

class DeepSeekException(message: String, cause: Throwable? = null) : Exception(message, cause)
