package com.needleassistant.app.system

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class NeedleAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
