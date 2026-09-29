package com.needleassistant.app.system

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

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
            val root = activeService?.rootInActiveWindow ?: return false
            return performScroll(root, forward, 0)
        }

        private fun performScroll(node: AccessibilityNodeInfo, forward: Boolean, depth: Int): Boolean {
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

        private const val MAX_SCROLL_TREE_DEPTH = 40
    }
}
