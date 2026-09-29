package com.needleassistant.app.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
        val matchingActivity = packageManager.queryIntentActivities(launcherIntent, 0)
            .firstOrNull { isMatchingLauncherLabel(it.loadLabel(packageManager).toString(), appName) }
        return matchingActivity?.let {
            packageManager.getLaunchIntentForPackage(it.activityInfo.packageName)
        }
    }

    fun processCommand(command: String): String {
        val c = command.lowercase(Locale.getDefault()).trim()
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

            else -> "Kechirasiz, bu buyruqni hali tushunmayman 🤔\n\n\"yordam\" deb yozing."
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

internal fun chooseUzbekLocale(locales: Collection<Locale>): Locale? =
    locales
        .filter { it.language.equals("uz", ignoreCase = true) }
        .sortedByDescending { it.country.equals("UZ", ignoreCase = true) }
        .firstOrNull()

internal fun isMatchingLauncherLabel(label: String, appName: String): Boolean {
    val normalizedLabel = label.trim().lowercase(Locale.ROOT)
    val normalizedName = appName.trim().lowercase(Locale.ROOT)
    return normalizedLabel == normalizedName ||
        (normalizedName == "telegram" && normalizedLabel == "telegram x")
}
