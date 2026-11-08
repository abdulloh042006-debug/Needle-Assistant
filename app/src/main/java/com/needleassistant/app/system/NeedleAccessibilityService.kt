package com.needleassistant.app.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Performs only the accessibility actions explicitly requested by the user.
 * The command surface is drawn as a separate overlay, so scrolling must target
 * the underlying foreground application window rather than Needle's own panel.
 */
class NeedleAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        if (activeService === this) activeService = null
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeService: NeedleAccessibilityService? = null

        fun scroll(forward: Boolean): Boolean {
            val service = activeService ?: return false
            val root = service.findForegroundAppRoot() ?: return false
            if (performScroll(root, forward, 0)) return true
            return service.dispatchScrollGesture(forward)
        }

        private fun NeedleAccessibilityService.findForegroundAppRoot(): AccessibilityNodeInfo? {
            // With flagRetrieveInteractiveWindows enabled, this skips Needle's
            // own overlay and finds the app that is still visible underneath.
            val appWindows = runCatching { windows }.getOrNull().orEmpty()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

            val focusedAppWindow = appWindows.firstOrNull { window ->
                window.isFocused && window.root?.packageName?.toString() != packageName
            }
            if (focusedAppWindow != null) return focusedAppWindow.root

            val visibleAppWindow = appWindows.firstOrNull { window ->
                window.root?.packageName?.toString() != packageName
            }
            if (visibleAppWindow != null) return visibleAppWindow.root

            return rootInActiveWindow?.takeIf {
                it.packageName?.toString() != packageName
            }
        }

        private fun performScroll(
            node: AccessibilityNodeInfo,
            forward: Boolean,
            depth: Int
        ): Boolean {
            if (depth > MAX_SCROLL_TREE_DEPTH) return false
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (node.isScrollable && node.performAction(action)) return true

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (performScroll(child, forward, depth + 1)) return true
            }
            return false
        }

        private fun NeedleAccessibilityService.dispatchScrollGesture(forward: Boolean): Boolean {
            val width = resources.displayMetrics.widthPixels.toFloat()
            val height = resources.displayMetrics.heightPixels.toFloat()
            if (width <= 0f || height <= 0f) return false

            val centerX = width * 0.5f
            val startY = if (forward) height * 0.82f else height * 0.24f
            val endY = if (forward) height * 0.24f else height * 0.82f
            val path = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, SCROLL_GESTURE_MILLIS))
                .build()

            // The panel is a touchable overlay. Hide it while the system injects
            // the swipe so the gesture reaches the app underneath, then restore it.
            FloatingAssistantService.hideOverlayForAccessibilityGesture()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    FloatingAssistantService.restoreOverlayAfterAccessibilityGesture()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    FloatingAssistantService.restoreOverlayAfterAccessibilityGesture()
                }
            }
            val accepted = runCatching {
                dispatchGesture(gesture, callback, null)
            }.getOrDefault(false)
            if (!accepted) FloatingAssistantService.restoreOverlayAfterAccessibilityGesture()
            return accepted
        }

        private const val MAX_SCROLL_TREE_DEPTH = 40
        private const val SCROLL_GESTURE_MILLIS = 360L
    }
}
