package com.needleassistant.app.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceController(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val _speechOutputState = MutableStateFlow(SpeechOutputState.INITIALIZING)
    val speechOutputState: StateFlow<SpeechOutputState> = _speechOutputState.asStateFlow()
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    private var pendingSpeech: String? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            _speechOutputState.value = SpeechOutputState.UNAVAILABLE
            pendingSpeech = null
            return
        }

        val uzbekVoices = engine.voices.orEmpty()
            .filter { it.locale.language.equals("uz", ignoreCase = true) }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.locale.country.equals("UZ", ignoreCase = true) }
            )
        val selectedVoice = uzbekVoices.firstOrNull()
        val languageResult = if (selectedVoice != null) {
            engine.voice = selectedVoice
            TextToSpeech.LANG_AVAILABLE
        } else {
            val uzbekLocale = chooseUzbekLocale(engine.availableLanguages.orEmpty())
            if (uzbekLocale == null) TextToSpeech.LANG_NOT_SUPPORTED else engine.setLanguage(uzbekLocale)
        }

        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            _speechOutputState.value = SpeechOutputState.UNAVAILABLE
            pendingSpeech = null
            return
        }

        engine.setSpeechRate(0.95f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }
        })
        _speechOutputState.value = SpeechOutputState.READY
        pendingSpeech?.let {
            pendingSpeech = null
            speak(it)
        }
    }

    fun speak(text: String): Boolean {
        if (_speechOutputState.value == SpeechOutputState.INITIALIZING) {
            pendingSpeech = text
            return true
        }
        if (_speechOutputState.value != SpeechOutputState.READY) return false
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "needle_tts")
        if (result != TextToSpeech.SUCCESS) {
            _isSpeaking.value = false
            return false
        }
        return true
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun installUzbekVoiceDataIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    fun refreshSpeechOutput() {
        _speechOutputState.value = SpeechOutputState.INITIALIZING
        _isSpeaking.value = false
        pendingSpeech = null
        tts?.shutdown()
        tts = TextToSpeech(context, this)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isSpeaking.value = false
        pendingSpeech = null
    }

    private fun flashlight(isOn: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], isOn)
            if (isOn) "Fonar yoqildi ✅" else "Fonar o'chirildi ✅"
        } catch (e: Exception) {
            "Fonarni boshqarib bo'lmadi ❌"
        }
    }

    private fun getBattery(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Batareya: $level% 🔋"
    }

    private fun openWifiSettings(): String {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return "Wi-Fi sozlamalari ochildi 📶"
    }

    private fun openBluetoothSettings(): String {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return "Bluetooth sozlamalari ochildi 📡"
    }

    private fun setVolume(up: Boolean): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return if (up) "Ovoz ko'tarildi 🔊" else "Ovoz pasaytirildi 🔉"
    }

    // Tries multiple package names for the same app
    private fun openApp(packages: List<String>, appName: String): String {
        var launchFailed = false
        val packageManager = context.packageManager
        val launchIntents = packages.mapNotNull(packageManager::getLaunchIntentForPackage) +
            listOfNotNull(findLauncherIntentByLabel(appName))
        for (intent in launchIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "$appName ochildi ✅"
            } catch (_: ActivityNotFoundException) {
                launchFailed = true
            } catch (_: SecurityException) {
                launchFailed = true
            }
        }
        if (launchFailed) return "$appName ilovasini ochib bo'lmadi ❌"

        return "$appName topilmadi ❌\n(Ilova telefoningizda o'rnatilmagan bo'lishi mumkin)"
    }

    private fun findLauncherIntentByLabel(appName: String): Intent? {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { isMatchingLauncherLabel(it.loadLabel(packageManager).toString(), appName) }
        val matchingActivity = matches.firstOrNull {
            normalizeAppLabel(it.loadLabel(packageManager).toString()) == normalizeAppLabel(appName)
        } ?: matches.singleOrNull()
        return matchingActivity?.let {
            packageManager.getLaunchIntentForPackage(it.activityInfo.packageName)
        }
    }

    fun processCommand(command: String): String {
        val c = command.lowercase(Locale.getDefault()).trim()
        parseMediaAction(c)?.let { return controlMedia(it) }
        return when {
            // Fonar
            (c.contains("fonar") || c.contains("chiroq") || c.contains("flashlight")) &&
                (c.contains("yoq") || c.contains("yond") || c.contains("on")) -> flashlight(true)
            (c.contains("fonar") || c.contains("chiroq") || c.contains("flashlight")) &&
                (c.contains("o'chir") || c.contains("ochir") || c.contains("off")) -> flashlight(false)

            // Batareya
            c.contains("batareya") || c.contains("quvvat") || c.contains("zaryad") || c.contains("foiz") -> getBattery()

            // Wi-Fi
            c.contains("wi-fi") || c.contains("wifi") || c.contains("vaifi") -> openWifiSettings()

            // Bluetooth
            c.contains("bluetooth") || c.contains("blutus") -> openBluetoothSettings()

            // Ovoz
            c.contains("ovoz") && (c.contains("ko'tar") || c.contains("baland") || c.contains("oshir") || c.contains("katta")) -> setVolume(true)
            c.contains("ovoz") && (c.contains("past") || c.contains("kamayt") || c.contains("tushir") || c.contains("kichik")) -> setVolume(false)

            // Kamera
            c.contains("kamera") || c.contains("camera") || c.contains("kamer") -> openApp(
                listOf(
                    "com.android.camera2",
                    "com.android.camera",
                    "com.samsung.android.app.camera",
                    "com.huawei.camera",
                    "com.xiaomi.camera",
                    "com.miui.camera",
                    "com.oppo.camera",
                    "com.vivo.camera",
                    "com.oneplus.camera"
                ), "Kamera"
            )

            // Kalkulyator
            c.contains("kalkulyator") || c.contains("hisoblash") || c.contains("hisobla") || c.contains("calculator") -> openApp(
                listOf(
                    "com.google.android.calculator",
                    "com.android.calculator2",
                    "com.samsung.android.calculator",
                    "com.miui.calculator",
                    "com.huawei.calculator",
                    "com.coloros.calculator",
                    "com.vivo.calculator"
                ), "Kalkulyator"
            )

            // Telegram
            c.contains("telegram") -> openApp(
                listOf(
                    "org.telegram.messenger",
                    "org.telegram.messenger.web",
                    "org.thunderdog.challegram"  // Telegram X
                ), "Telegram"
            )

            // YouTube
            c.contains("youtube") || c.contains("yutub") -> openApp(
                listOf(
                    "com.google.android.youtube",
                    "com.vanced.android.youtube"
                ), "YouTube"
            )

            // Instagram
            c.contains("instagram") || c.contains("insta") -> openApp(
                listOf("com.instagram.android"), "Instagram"
            )

            // Chrome
            c.contains("chrome") || c.contains("brauzer") || c.contains("internet") -> openApp(
                listOf(
                    "com.android.chrome",
                    "com.sec.android.app.sbrowser",
                    "com.mi.globalbrowser",
                    "com.huawei.browser"
                ), "Brauzer"
            )

            // Sozlamalar
            c.contains("sozlama") || c.contains("settings") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                "Sozlamalar ochildi ⚙️"
            }

            // Salom
            c.contains("salom") || c.contains("assalomu alaykum") || c.contains("hay") -> "Va alaykum assalom! 👋 Qanday yordam bera olaman?"

            // Rahmat
            c.contains("rahmat") || c.contains("tashakkur") -> "Arzimaydi! Yana yordam kerak bo'lsa aytavering 😊"

            // Yordam
            c.contains("yordam") || c.contains("buyruq") || c.contains("nima qila olasan") || c.contains("help") -> getCommandList()

            else -> {
                val requestedApp = extractAppNameToOpen(c)
                    ?: c.takeIf { findLauncherIntentByLabel(it) != null }
                requestedApp?.let(::openAppByLabel)
                    ?: "Kechirasiz, bu buyruqni hali tushunmayman 🤔\n\n\"yordam\" deb yozing."
            }
        }
    }

    private fun controlMedia(action: MediaAction): String {
        val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = try {
            sessionManager.getActiveSessions(NeedleNotificationListenerService.componentName(context))
        } catch (_: SecurityException) {
            return "Media boshqarish uchun Sozlamalar > Notification Access bo'limida Needle ruxsatini yoqing."
        } catch (_: IllegalStateException) {
            return "Media seanslarni o'qib bo'lmadi. Notification Access ruxsatini tekshiring."
        }

        val supportedSession = sessions.firstOrNull { controller -> controller.supports(action) }
            ?: return "Faol media topilmadi yoki ochiq ilova bu amalni qo'llamaydi. Musiqa/video ilovasini ishga tushiring va Notification Access'ni yoqing."
        return try {
            when (action) {
                MediaAction.PLAY -> supportedSession.transportControls.play()
                MediaAction.PAUSE -> supportedSession.transportControls.pause()
                MediaAction.NEXT -> supportedSession.transportControls.skipToNext()
                MediaAction.PREVIOUS -> supportedSession.transportControls.skipToPrevious()
            }
            when (action) {
                MediaAction.PLAY -> "Media ijrosi boshlandi ▶️"
                MediaAction.PAUSE -> "Media pauza qilindi ⏸️"
                MediaAction.NEXT -> "Keyingi trekka o'tildi ⏭️"
                MediaAction.PREVIOUS -> "Oldingi trekka o'tildi ⏮️"
            }
        } catch (_: SecurityException) {
            "Media ilovasi bu amalni bajarishga ruxsat bermadi."
        } catch (_: IllegalStateException) {
            "Media ilovasi hozir boshqaruv buyruqlarini qabul qilmayapti."
        }
    }

    private fun openAppByLabel(appName: String): String {
        val intent = findLauncherIntentByLabel(appName)
            ?: return "\"$appName\" nomli ilova topilmadi ❌\nIlova nomini launcherda qanday ko'rinsa shunday yozib ko'ring."
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "$appName ochildi ✅"
        } catch (_: ActivityNotFoundException) {
            "$appName ilovasini ochib bo'lmadi ❌"
        } catch (_: SecurityException) {
            "$appName ilovasini ochishga ruxsat berilmadi ❌"
        }
    }

    fun getCommandList(): String {
        return """
📋 Buyruqlar ro'yxati:

🔦 "fonarni yoq" / "fonarni o'chir"
🔋 "batareya necha foiz"
📶 "wifi ochiq"
📡 "bluetoothni och"
🔊 "ovozni ko'tar" / "ovozni past qil"
🎵 "musiqani qo'y" / "musiqani to'xtat"
⏭ "keyingi qo'shiq" / "oldingi trek"
📷 "kamerani och"
🧮 "kalkulyatorni och"
📱 "telegramni och"
▶️ "youtubeni och"
📸 "instagramni och"
🌐 "brauzerni och"
⚙️ "sozlamalarni och"
        """.trimIndent()
    }
}

enum class SpeechOutputState {
    INITIALIZING,
    READY,
    UNAVAILABLE
}

internal enum class MediaAction {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS
}

internal fun parseMediaAction(command: String): MediaAction? {
    val normalized = normalizeAppLabel(command)
    return when {
        listOf("keyingi", "navbatdagi", "next trek", "next qoshiq").any(normalized::contains) ->
            MediaAction.NEXT
        listOf("oldingi", "avvalgi", "previous trek", "previous qoshiq").any(normalized::contains) ->
            MediaAction.PREVIOUS
        listOf("pauza", "pause", "toxtat", "stop", "musiqani ochir", "qoshiqni toxtat").any(normalized::contains) ->
            MediaAction.PAUSE
        listOf(
            "musiqa qoy",
            "musiqani qoy",
            "qoshiq qoy",
            "qoshiqni qoy",
            "play",
            "davom et",
            "ijroni davom"
        ).any(normalized::contains) -> MediaAction.PLAY
        else -> null
    }
}

internal fun chooseUzbekLocale(locales: Collection<Locale>): Locale? =
    locales
        .filter { it.language.equals("uz", ignoreCase = true) }
        .sortedByDescending { it.country.equals("UZ", ignoreCase = true) }
        .firstOrNull()

internal fun isMatchingLauncherLabel(label: String, appName: String): Boolean {
    val normalizedLabel = normalizeAppLabel(label)
    val normalizedName = normalizeAppLabel(appName)
    return normalizedLabel == normalizedName ||
        (normalizedName == "telegram" && normalizedLabel == "telegram x") ||
        normalizedLabel.endsWith(" $normalizedName")
}

internal fun extractAppNameToOpen(command: String): String? {
    val words = normalizeAppLabel(command).split(' ').filter(String::isNotBlank)
    val actions = listOf("och", "oching", "ochib", "open", "start", "run", "ishga tushir", "ishga tushiring")
        .map { it.split(' ') }
    val actionStart = actions
        .mapNotNull { action ->
            val index = words.windowed(action.size).indexOfFirst { it == action }
            if (index >= 0) index to action.size else null
        }
        .minByOrNull { it.first } ?: return null
    val (index, actionSize) = actionStart
    val nameWords = if (index > 0) words.take(index) else words.drop(index + actionSize)
    val ignoredWords = setOf("iltimos", "menga", "ilova", "ilovani", "ilovasini", "app", "appni", "dastur", "dasturni")
    val appWords = nameWords
        .filterNot { it in ignoredWords || it == "ber" }
        .toMutableList()
    val lastWord = appWords.lastOrNull() ?: return null
    appWords[appWords.lastIndex] = listOf("ni", "ga", "da").firstOrNull {
        lastWord.length > it.length + 2 && lastWord.endsWith(it)
    }?.let(lastWord::removeSuffix) ?: lastWord
    return appWords.joinToString(" ").takeIf(String::isNotBlank)
}

private fun normalizeAppLabel(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace("'", "")
        .replace("’", "")
        .replace("‘", "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
    val speechAliases = mapOf(
        "yandex aymi" to "yandex music",
        "yandeks aymi" to "yandex music",
        "yandex ime" to "yandex music",
        "yandeks ime" to "yandex music"
    )
    speechAliases[normalized]?.let { return it }
    return normalized
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word ->
            when (word) {
                "muzik", "musiqa", "musiqi", "music", "myuzik" -> "music"
                "yandeks" -> "yandex"
                "aymi", "aimi", "ime" -> "music"
                else -> word
            }
        }
        .trim()
}

private fun MediaController.supports(action: MediaAction): Boolean {
    val availableActions = playbackState?.actions ?: 0L
    val requiredAction = when (action) {
        MediaAction.PLAY -> PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
        MediaAction.PAUSE -> PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        MediaAction.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
        MediaAction.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
    return availableActions and requiredAction != 0L
}
