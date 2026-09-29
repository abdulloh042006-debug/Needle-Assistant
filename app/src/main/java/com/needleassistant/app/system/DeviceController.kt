package com.needleassistant.app.system

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale

class DeviceController(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isSpeaking = false
        private set

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val uzbek = Locale("uz", "UZ")
            val result = tts?.setLanguage(uzbek)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("ru"))
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "needle_tts")
    }

    fun stopSpeaking() {
        tts?.stop()
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
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "$appName ochildi ✅"
            }
        }
        // Last resort: try to open via app name search in Play Store
        return "$appName topilmadi ❌\n(Ilova telefoningizda o'rnatilmagan bo'lishi mumkin)"
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
