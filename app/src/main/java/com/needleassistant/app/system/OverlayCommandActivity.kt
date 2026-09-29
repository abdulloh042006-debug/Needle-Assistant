package com.needleassistant.app.system

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.needleassistant.app.theme.NeedleAssistantTheme
import kotlinx.coroutines.launch

class OverlayCommandActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_VOICE = "start_voice"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            NeedleAssistantTheme {
                val controller = remember { DeviceController(applicationContext) }
                val groqAssistant = remember { GroqAssistant(applicationContext) }
                DisposableEffect(controller) {
                    onDispose { controller.shutdown() }
                }
                val speechState by controller.speechOutputState.collectAsState()
                OverlayCommandPanel(
                    speechState = speechState,
                    onClose = ::finish,
                    onCommand = { command ->
                        val localResponse = controller.processCommand(command)
                        val response = if (localResponse.startsWith("Kechirasiz, bu buyruqni hali tushunmayman")) {
                            if (!groqAssistant.hasApiKey) {
                                "AI savollariga javob olish uchun avval asosiy oynada Groq API kalitini sozlang."
                            } else {
                                groqAssistant.ask(listOf(ChatTurn("user", command)))
                            }
                        } else {
                            localResponse
                        }
                        controller.speak(response)
                        if (response.endsWith("ochildi ✅")) finish()
                        response
                    }
                )
            }
        }
    }
}

@Composable
private fun OverlayCommandPanel(
    speechState: SpeechOutputState,
    onClose: () -> Unit,
    onCommand: suspend (String) -> String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    fun submitCommand(value: String) {
        if (isThinking || value.isBlank()) return
        isThinking = true
        response = "Javob tayyorlanmoqda..."
        coroutineScope.launch {
            response = try {
                onCommand(value)
            } catch (exception: GroqException) {
                exception.message ?: "AI javobini olishda xatolik yuz berdi."
            }
            isThinking = false
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val recognized = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !recognized.isNullOrBlank()) {
            command = recognized
            submitCommand(recognized)
        } else {
            response = "Ovoz tanilmadi. Buyruqni yozib ko'ring."
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (!launchUzbekRecognition(speechLauncher)) {
                response = "Nutqni tanish xizmati topilmadi. Buyruqni yozing."
            }
        }
        else response = "Ovozli buyruq uchun mikrofon ruxsati kerak."
    }

    LaunchedEffect(Unit) {
        if ((context as? Activity)?.intent?.getBooleanExtra(OverlayCommandActivity.EXTRA_START_VOICE, false) == true) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
                if (!launchUzbekRecognition(speechLauncher)) {
                    response = "Nutqni tanish xizmati topilmadi. Buyruqni yozing."
                }
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Needle yordamchi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (speechState == SpeechOutputState.UNAVAILABLE) {
                    Text("Matn", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ilova nomi yoki buyruq") },
                placeholder = { Text("Masalan: Telegramni och") }
            )
            if (response.isNotBlank()) {
                Text(response, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) {
                        if (!launchUzbekRecognition(speechLauncher)) {
                            response = "Nutqni tanish xizmati topilmadi. Buyruqni yozing."
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Text("🎤 Ovoz") }
                Button(
                    onClick = {
                        submitCommand(command)
                    },
                    enabled = command.isNotBlank() && !isThinking
                ) { Text("Bajarish") }
                TextButton(onClick = onClose) { Text("Yopish") }
            }
        }
    }
}

private fun launchUzbekRecognition(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
): Boolean {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Gapiring...")
    }
    try {
        launcher.launch(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    }
}
