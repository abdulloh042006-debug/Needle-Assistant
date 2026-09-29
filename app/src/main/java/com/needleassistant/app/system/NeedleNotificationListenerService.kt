package com.needleassistant.app.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NeedleNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    companion object {
        fun componentName(context: android.content.Context) =
            android.content.ComponentName(context, NeedleNotificationListenerService::class.java)
    }
}
