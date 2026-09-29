package com.needleassistant.app.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.needleassistant.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingAssistantService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isTucked = false
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private val tuckRunnable = Runnable { tuckBubble() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        showBubble()
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
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
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
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = getPreferences().getInt(KEY_X, max(0, screenWidth() - dp(56)))
                .coerceIn(0, max(0, screenWidth() - dp(56)))
            y = getPreferences().getInt(KEY_Y, dp(180))
                .coerceIn(0, max(0, screenHeight() - dp(56)))
        }
        try {
            manager.addView(button, params)
            windowManager = manager
            bubble = button
            layoutParams = params
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
        val params = layoutParams ?: return false
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
                    scheduleTuck()
                } else if (isTucked) {
                    expandBubble()
                    scheduleTuck()
                } else {
                    tuckBubble()
                    openAssistant()
                }
                return true
            }
        }
        return true
    }

    private fun snapToNearestEdge() {
        val params = layoutParams ?: return
        val rightX = screenWidth() - dp(56)
        val right = params.x + dp(28) >= screenWidth() / 2
        isTucked = false
        params.x = if (right) rightX else 0
        params.y = params.y.coerceIn(0, max(0, screenHeight() - dp(56)))
        updateBubble()
    }

    private fun expandBubble() {
        val params = layoutParams ?: return
        val right = getPreferences().getBoolean(KEY_RIGHT, params.x >= screenWidth() / 2)
        isTucked = false
        params.x = if (right) screenWidth() - params.width else 0
        updateBubble()
    }

    private fun tuckBubble() {
        val params = layoutParams ?: return
        val wasOnRight = getPreferences().getBoolean(KEY_RIGHT, params.x > screenWidth() / 2)
        isTucked = true
        params.x = if (wasOnRight) screenWidth() - dp(12) else -params.width + dp(12)
        params.y = params.y.coerceIn(0, max(0, screenHeight() - dp(56)))
        updateBubble()
    }

    private fun scheduleTuck() {
        handler.removeCallbacks(tuckRunnable)
        handler.postDelayed(tuckRunnable, TUCK_DELAY_MILLIS)
    }

    private fun savePosition() {
        val params = layoutParams ?: return
        getPreferences().edit()
            .putInt(KEY_X, params.x.coerceIn(0, max(0, screenWidth() - params.width)))
            .putInt(KEY_Y, params.y)
            .putBoolean(KEY_RIGHT, params.x + params.width / 2 >= screenWidth() / 2)
            .apply()
    }

    private fun updateBubble() {
        val view = bubble ?: return
        val params = layoutParams ?: return
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            stopSelf()
        }
    }

    private fun openAssistant() {
        val launchIntent = Intent(this, OverlayCommandActivity::class.java)
            .putExtra(OverlayCommandActivity.EXTRA_START_VOICE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.overlay_stop), stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bubble?.let { windowManager?.removeView(it) }
        bubble = null
        windowManager = null
        layoutParams = null
        getPreferences().edit().putBoolean(KEY_ENABLED, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        private const val TUCK_DELAY_MILLIS = 4_000L

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }
}
