package com.needleassistant.app.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.needleassistant.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the floating Needle control above the current app.
 *
 * The command panel is a WindowManager overlay, not an Activity. This is
 * important: launching an Activity here would move the user's current app
 * (for example Instagram) out of the foreground before Accessibility actions
 * such as scrolling can target it.
 */
class FloatingAssistantService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var commandInput: EditText? = null
    private var statusText: TextView? = null

    private var deviceController: DeviceController? = null
    private val deepSeekAssistant by lazy(LazyThreadSafetyMode.NONE) { DeepSeekAssistant(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isThinking = false

    private val handler = Handler(Looper.getMainLooper())
    private var isTucked = false
    private var isDragging = false
    private var overlayHiddenForGesture = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private val tuckRunnable = Runnable { tuckBubble() }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        startAsForegroundService()
        deviceController = DeviceController(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }


    private fun startAsForegroundService() {
        val notification = createNotification()
        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (hasMicrophonePermission) {
                    serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicrophonePermission -> {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showBubble()
        getPreferences().edit().putBoolean(KEY_ENABLED, bubble != null).apply()
        return START_STICKY
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val button = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF10141D.toInt())
                setStroke(dp(2), 0xFF8EA7FF.toInt())
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            contentDescription = getString(R.string.quick_settings_tile_label)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val params = WindowManager.LayoutParams(
            dp(BUBBLE_SIZE_DP),
            dp(BUBBLE_SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = getPreferences().getInt(KEY_X, max(0, screenWidth() - dp(BUBBLE_SIZE_DP)))
                .coerceIn(0, max(0, screenWidth() - dp(BUBBLE_SIZE_DP)))
            y = getPreferences().getInt(KEY_Y, dp(180))
                .coerceIn(0, max(0, screenHeight() - dp(BUBBLE_SIZE_DP)))
        }
        try {
            windowManager?.addView(button, params)
            bubble = button
            bubbleParams = params
            tuckBubble()
            scheduleTuck()
            button.setOnTouchListener { _, event -> handleBubbleTouch(event) }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: WindowManager.BadTokenException) {
            stopSelf()
        }
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(tuckRunnable)
                isDragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downX = params.x
                downY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - downRawX).toInt()
                val deltaY = (event.rawY - downRawY).toInt()
                if (!isDragging && (abs(deltaX) > dp(6) || abs(deltaY) > dp(6))) isDragging = true
                if (isDragging) {
                    params.x = (downX + deltaX).coerceIn(-params.width + dp(12), screenWidth() - dp(12))
                    params.y = (downY + deltaY).coerceIn(0, screenHeight() - dp(12))
                    updateBubble()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    snapToNearestEdge()
                    savePosition()
                    if (panel == null) scheduleTuck()
                } else {
                    togglePanel()
                }
                return true
            }
        }
        return true
    }

    private fun togglePanel() {
        if (panel != null) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun showPanel() {
        if (panel != null) return
        val params = bubbleParams ?: return
        handler.removeCallbacks(tuckRunnable)
        expandBubble()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xF21B2634.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF7188B8.toInt())
            }
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Needle Assistant"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            contentDescription = "Panelni yopish"
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { hidePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(42)))

        val input = EditText(this).apply {
            hint = "Buyruq: pastga aylantir..."
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFB7C0CF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF263445.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitCommand(text.toString())
                    true
                } else false
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val voice = Button(this).apply {
            text = "Ovoz"
            isAllCaps = false
            setOnClickListener { startVoiceInput() }
        }
        val run = Button(this).apply {
            text = "Bajarish"
            isAllCaps = false
            setOnClickListener { submitCommand(input.text.toString()) }
        }
        actions.addView(voice, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(6)
        })
        actions.addView(run, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })

        val status = TextView(this).apply {
            text = "Buyruq yozing yoki ovoz tugmasini bosing."
            textSize = 13f
            setTextColor(0xFFE0E7F1.toInt())
            setPadding(dp(2), dp(8), dp(2), 0)
        }

        card.addView(header)
        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(4) })
        card.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(6) })
        card.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Leave a clear strip beside the panel so the floating N button stays tappable.
        val panelWidth = min(
            dp(PANEL_WIDTH_DP),
            screenWidth() - dp(BUBBLE_SIZE_DP + 24)
        ).coerceAtLeast(dp(240))
        val onRight = getPreferences().getBoolean(KEY_RIGHT, params.x + params.width / 2 >= screenWidth() / 2)
        val overlayParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (onRight) dp(12) else max(dp(12), screenWidth() - panelWidth - dp(12))
            y = (params.y - dp(90)).coerceIn(dp(24), max(dp(24), screenHeight() - dp(300)))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }

        try {
            windowManager?.addView(card, overlayParams)
            panel = card
            panelParams = overlayParams
            commandInput = input
            statusText = status
        } catch (_: SecurityException) {
            updateStatus("Needle oynasini ko'rsatish uchun overlay ruxsati kerak.")
        } catch (_: WindowManager.BadTokenException) {
            updateStatus("Needle oynasini hozir ko'rsatib bo'lmadi.")
        }
    }

    private fun hidePanel() {
        stopVoiceInput()
        panel?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        panel = null
        panelParams = null
        commandInput = null
        statusText = null
        scheduleTuck()
    }

    private fun updateStatus(message: String) {
        statusText?.text = message
    }

    private fun submitCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isBlank() || isThinking) return
        isThinking = true
        updateStatus("Buyruq bajarilmoqda...")
        val controller = deviceController ?: run {
            isThinking = false
            updateStatus("Needle xizmati tayyor emas.")
            return
        }

        val localResponse = controller.processCommand(command)
        if (!localResponse.startsWith("Kechirasiz, bu buyruqni hali tushunmayman")) {
            isThinking = false
            updateStatus(localResponse)
            controller.speak(localResponse)
            return
        }

        serviceScope.launch {
            val response = try {
                val decision = deepSeekAssistant.process(listOf(ChatTurn("user", command)))
                decision.command?.let { normalizedCommand ->
                    controller.processCommand(normalizedCommand).takeUnless {
                        it.startsWith("Kechirasiz, bu buyruqni hali tushunmayman")
                    } ?: "Bu buyruqni qurilmada bajarib bo'lmadi. Boshqacha ifodalab ko'ring."
                } ?: decision.answer.orEmpty()
            } catch (exception: DeepSeekException) {
                exception.message ?: "DeepSeek javobini olishda xatolik yuz berdi."
            } catch (_: Exception) {
                "Buyruqni bajarishda noma'lum xatolik yuz berdi."
            }
            isThinking = false
            updateStatus(response)
            controller.speak(response)
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            updateStatus("Mikrofon ruxsati yo'q. Asosiy Needle ilovasiga qaytib, Mikrofon ruxsatini yoqing.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Telefonda nutqni tanish xizmati topilmadi. Buyruqni yozib kiriting.")
            return
        }

        stopVoiceInput()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    isListening = true
                    updateStatus("Tinglayapman... Gapiring.")
                }

                override fun onBeginningOfSpeech() {
                    updateStatus("Ovozingiz eshitildi...")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    isListening = false
                    updateStatus("Ovoz qayta ishlanmoqda...")
                }

                override fun onError(error: Int) {
                    isListening = false
                    updateStatus(when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon ovozini olishda xatolik."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon ruxsati yetarli emas."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Nutqni tanish uchun internet aloqasini tekshiring."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "Ovoz aniqlanmadi. Yana urinib ko'ring."
                        else -> "Ovozli buyruqda xatolik ($error). Qayta urinib ko'ring."
                    })
                    destroySpeechRecognizer()
                }

                override fun onResults(results: android.os.Bundle?) {
                    isListening = false
                    val recognized = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    destroySpeechRecognizer()
                    if (!recognized.isNullOrBlank()) {
                        commandInput?.setText(recognized)
                        submitCommand(recognized)
                    } else {
                        updateStatus("Ovoz aniqlanmadi. Buyruqni yozib kiriting.")
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) commandInput?.setText(partial)
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer.startListening(intent)
        } catch (_: Exception) {
            isListening = false
            destroySpeechRecognizer()
            updateStatus("Ovozli xizmatni ishga tushirib bo'lmadi. Buyruqni yozib kiriting.")
        }
    }

    private fun stopVoiceInput() {
        if (isListening) {
            runCatching { speechRecognizer?.cancel() }
        }
        isListening = false
        destroySpeechRecognizer()
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.let { recognizer ->
            runCatching { recognizer.destroy() }
        }
        speechRecognizer = null
    }


    private fun hideOverlaysForScrollGesture() {
        if (overlayHiddenForGesture) return
        overlayHiddenForGesture = true
        panel?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubble?.let { view -> runCatching { windowManager?.removeView(view) } }
    }

    private fun restoreOverlaysAfterScrollGesture() {
        if (!overlayHiddenForGesture) return
        overlayHiddenForGesture = false
        val manager = windowManager ?: return
        try {
            bubble?.let { view ->
                if (!view.isAttachedToWindow) {
                    bubbleParams?.let { manager.addView(view, it) }
                }
            }
            panel?.let { view ->
                if (!view.isAttachedToWindow) {
                    panelParams?.let { manager.addView(view, it) }
                }
            }
        } catch (_: Exception) {
            // Keep the foreground app usable even if Android rejects restoration.
        }
    }

    private fun snapToNearestEdge() {
        val params = bubbleParams ?: return
        val right = params.x + dp(BUBBLE_SIZE_DP / 2) >= screenWidth() / 2
        isTucked = false
        params.x = if (right) screenWidth() - dp(BUBBLE_SIZE_DP) else 0
        params.y = params.y.coerceIn(0, max(0, screenHeight() - dp(BUBBLE_SIZE_DP)))
        updateBubble()
    }

    private fun expandBubble() {
        val params = bubbleParams ?: return
        val right = getPreferences().getBoolean(KEY_RIGHT, params.x >= screenWidth() / 2)
        isTucked = false
        params.x = if (right) screenWidth() - params.width else 0
        updateBubble()
    }

    private fun tuckBubble() {
        if (panel != null) return
        val params = bubbleParams ?: return
        val wasOnRight = getPreferences().getBoolean(KEY_RIGHT, params.x > screenWidth() / 2)
        isTucked = true
        params.x = if (wasOnRight) screenWidth() - dp(12) else -params.width + dp(12)
        updateBubble()
    }

    private fun scheduleTuck() {
        handler.removeCallbacks(tuckRunnable)
        if (panel == null) handler.postDelayed(tuckRunnable, TUCK_DELAY_MILLIS)
    }

    private fun savePosition() {
        val params = bubbleParams ?: return
        getPreferences().edit()
            .putInt(KEY_X, params.x.coerceIn(0, max(0, screenWidth() - params.width)))
            .putInt(KEY_Y, params.y)
            .putBoolean(KEY_RIGHT, params.x + params.width / 2 >= screenWidth() / 2)
            .apply()
    }

    private fun updateBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            stopSelf()
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, FloatingAssistantService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.overlay_stop), stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopVoiceInput()
        panel?.let { runCatching { windowManager?.removeView(it) } }
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        panel = null
        bubble = null
        windowManager = null
        panelParams = null
        bubbleParams = null
        deviceController?.shutdown()
        deviceController = null
        serviceScope.cancel()
        getPreferences().edit().putBoolean(KEY_ENABLED, false).apply()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getPreferences() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_STOP = "com.needleassistant.app.action.STOP_FLOATING_ASSISTANT"
        const val ACTION_START = "com.needleassistant.app.action.START_FLOATING_ASSISTANT"
        private const val PREFERENCES = "floating_assistant"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_RIGHT = "right"
        private const val CHANNEL_ID = "floating_assistant"
        private const val NOTIFICATION_ID = 7001
        @Volatile
        private var activeInstance: FloatingAssistantService? = null

        fun hideOverlayForAccessibilityGesture() {
            activeInstance?.hideOverlaysForScrollGesture()
        }

        fun restoreOverlayAfterAccessibilityGesture() {
            activeInstance?.restoreOverlaysAfterScrollGesture()
        }

        private const val TUCK_DELAY_MILLIS = 4_000L
        private const val BUBBLE_SIZE_DP = 56
        private const val PANEL_WIDTH_DP = 320

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }
}