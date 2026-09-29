package com.needleassistant.app.ui.main

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Settings.Secure
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.needleassistant.app.system.NeedleAccessibilityService

internal enum class SetupCapability {
    OVERLAY,
    MICROPHONE,
    ACCESSIBILITY,
    NOTIFICATION_ACCESS,
    DEFAULT_ASSISTANT,
    DEFAULT_DIALER,
    DEFAULT_SMS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSetupScreen(
    capabilityRefresh: Int,
    onEnableCapability: (SetupCapability) -> Unit,
    onComplete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageName = context.packageName
    val accessibilityComponent = remember {
        ComponentName(context, NeedleAccessibilityService::class.java).flattenToString()
    }
    val overlayGranted = Settings.canDrawOverlays(context)
    val microphoneGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    val accessibilityEnabled = Secure.getString(
        context.contentResolver,
        Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty().split(':').any { it.equals(accessibilityComponent, ignoreCase = true) }
    val notificationAccessEnabled = Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ).orEmpty().split(':').any { it.startsWith(packageName, ignoreCase = true) }
    val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)
    } else {
        null
    }
    val assistantRoleReady = roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
    val assistantRoleHeld = roleManager?.let {
        it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } == true
    val dialerRoleReady = roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true
    val dialerRoleHeld = roleManager?.let {
        it.isRoleAvailable(RoleManager.ROLE_DIALER) && it.isRoleHeld(RoleManager.ROLE_DIALER)
    } == true
    val smsRoleReady = roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true
    val smsRoleHeld = roleManager?.let {
        it.isRoleAvailable(RoleManager.ROLE_SMS) && it.isRoleHeld(RoleManager.ROLE_SMS)
    } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Assistant sozlamalari")
                        Text("Ruxsatlarni o'zingiz tanlang", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onComplete) { Text("←", style = MaterialTheme.typography.titleLarge) }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) { Text("Tayyor") }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Faqat ishlatmoqchi bo'lgan funksiyalaringiz uchun ruxsat bering. Sozlamalardan qaytganda holat avtomatik yangilanadi.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Holat tekshiruvi: $capabilityRefresh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SetupCapabilityRow(
                "Mikrofon",
                "Ovozli buyruqlarni tanish uchun.",
                microphoneGranted,
                if (microphoneGranted) null else "Yoqish",
                { onEnableCapability(SetupCapability.MICROPHONE) }
            )
            SetupCapabilityRow(
                "Boshqa ilovalar ustida ko'rsatish",
                "Suzuvchi Needle tugmasi uchun.",
                overlayGranted,
                if (overlayGranted) null else "Yoqish",
                { onEnableCapability(SetupCapability.OVERLAY) }
            )
            SetupCapabilityRow(
                "Accessibility",
                "Xizmat hozircha ilovalarni bosish yoki matn kiritishni bajarmaydi; buni yoqish majburiy emas.",
                accessibilityEnabled,
                if (accessibilityEnabled) null else "Sozlamalar",
                { onEnableCapability(SetupCapability.ACCESSIBILITY) }
            )
            SetupCapabilityRow(
                "Notification Access",
                "Faol media seansini topish va musiqa/video ijrosini boshqarish uchun. Yoqish ixtiyoriy.",
                notificationAccessEnabled,
                if (notificationAccessEnabled) null else "Sozlamalar",
                { onEnableCapability(SetupCapability.NOTIFICATION_ACCESS) }
            )
            SetupCapabilityRow(
                "Default Assistant",
                if (assistantRoleHeld) "Needle standart assistant sifatida tanlangan."
                else "VoiceInteractionService hali tayyor emas; Android Needle'ni assistant sifatida tanlashga ruxsat bermasligi mumkin.",
                assistantRoleHeld,
                if (assistantRoleReady && !assistantRoleHeld) "Rolni so'rash" else null,
                { onEnableCapability(SetupCapability.DEFAULT_ASSISTANT) }
            )
            SetupCapabilityRow(
                "Default Dialer / Telecom",
                if (dialerRoleHeld) "Needle standart telefon ilovasi."
                else "InCallService va dialer ekrani hali yo'q; qo'ng'iroqni qabul/rad etish ishlamaydi.",
                dialerRoleHeld,
                if (dialerRoleReady && !dialerRoleHeld) "Rolni so'rash" else null,
                { onEnableCapability(SetupCapability.DEFAULT_DIALER) }
            )
            SetupCapabilityRow(
                "Default SMS",
                if (smsRoleHeld) "Needle standart SMS ilovasi."
                else "SMS ilovasi/provider to'liq amalga oshirilmagan; SMS yuborish hozircha ishlamaydi.",
                smsRoleHeld,
                if (smsRoleReady && !smsRoleHeld) "Rolni so'rash" else null,
                { onEnableCapability(SetupCapability.DEFAULT_SMS) }
            )
            HorizontalDivider()
            Text(
                "Contacts, Call va SMS permissions faqat tegishli amallar amalga oshirilganda so'raladi. Device Admin bu vazifalar uchun kerak emas va so'ralmaydi. Device Owner oddiy permission emas: enterprise provisioning, ko'pincha factory reset talab qiladi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SetupCapabilityRow(
    title: String,
    description: String,
    enabled: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (enabled) "✓" else "○",
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (enabled) "Yoqilgan" else "O'chiq", style = MaterialTheme.typography.labelSmall)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!actionLabel.isNullOrBlank()) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

internal fun requestAssistantRole(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    role: String
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return
    if (roleManager.isRoleAvailable(role)) {
        launcher.launch(roleManager.createRequestRoleIntent(role))
    }
}
