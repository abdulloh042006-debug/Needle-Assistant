package com.needleassistant.app.ui.main

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import com.needleassistant.app.R
import com.needleassistant.app.system.ChatTurn
import com.needleassistant.app.system.DeviceController
import com.needleassistant.app.system.FloatingAssistantService
import com.needleassistant.app.system.GroqAssistant
import com.needleassistant.app.system.GroqException
import com.needleassistant.app.system.SpeechOutputState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceController = remember { DeviceController(context) }
    val groqAssistant = remember { GroqAssistant(context) }
    val speechOutputState by deviceController.speechOutputState.collectAsState()
    val isSpeaking by deviceController.isSpeaking.collectAsState()
    val messages = remember {
        mutableStateListOf(
            Message("Assalomu alaykum! Men Needle Assistant'man. 👋\n\n\"yordam\" yozing — qila oladigan ishlarimni ko'rasiz.\n\n🎤 tugmasini bosib ovozdan ham gapira olasiz!", isUser = false)
        )
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var isListening by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(true) }
    var floatingAssistantEnabled by remember { mutableStateOf(FloatingAssistantService.isEnabled(context)) }
    var showGroqSettings by remember { mutableStateOf(false) }
    var groqConfigured by remember { mutableStateOf(groqAssistant.hasApiKey) }
    var isAiThinking by remember { mutableStateOf(false) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            try {
                val serviceIntent = Intent(context, FloatingAssistantService::class.java)
                    .setAction(FloatingAssistantService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
                else context.startService(serviceIntent)
                floatingAssistantEnabled = true
                messages.add(Message("Suzuvchi Needle tugmasi yoqildi. Boshqa ilovada ham ekrandagi N tugmasini bosing.", isUser = false))
            } catch (_: SecurityException) {
                messages.add(Message("Suzuvchi tugmani yoqib bo'lmadi. Ruxsatlar va bildirishnoma sozlamalarini tekshiring.", isUser = false))
            }
        } else {
            messages.add(Message("Suzuvchi tugma uchun boshqa ilovalar ustida ko'rsatish ruxsati kerak.", isUser = false))
        }
    }

    DisposableEffect(deviceController) {
        onDispose { deviceController.shutdown() }
    }

    fun submitCommand(text: String) {
        if (isAiThinking || text.isBlank()) return
        showQuickActions = false
        messages.add(Message(text, isUser = true))
        val response = deviceController.processCommand(text)
        if (response.startsWith("Kechirasiz, bu buyruqni hali tushunmayman")) {
            if (!groqConfigured) {
                messages.add(
                    Message(
                        "Savollarga AI javob berishi uchun Groq API kalitini sozlang. Yuqoridagi ⚙ tugmasini bosing.",
                        isUser = false
                    )
                )
            } else {
                isAiThinking = true
                val answerIndex = messages.size
                messages.add(Message("AI javob tayyorlamoqda...", isUser = false))
                val conversation = messages.dropLast(1).map {
                    ChatTurn(if (it.isUser) "user" else "assistant", it.text)
                }
                coroutineScope.launch {
                    val answer = try {
                        groqAssistant.ask(conversation)
                    } catch (exception: GroqException) {
                        exception.message ?: "AI javobini olishda xatolik yuz berdi."
                    }
                    messages[answerIndex] = Message(answer, isUser = false)
                    isAiThinking = false
                    deviceController.speak(answer)
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
        } else {
            messages.add(Message(response, isUser = false))
            deviceController.speak(response)
        }
        coroutineScope.launch {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            messages.add(Message("Mikrofon ruxsati berildi! Endi 🎤 tugmasini bosing.", isUser = false))
        } else {
            messages.add(Message("Mikrofon ruxsati berilmadi. Iltimos, Sozlamalar > Ilova > Needle Assistant > Ruxsatlar dan Mikrofon ruxsatini bering.", isUser = false))
        }
    }

    val ttsDataLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        deviceController.refreshSpeechOutput()
    }

    // Speech recognition launcher
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        val spokenText = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (result.resultCode == Activity.RESULT_OK && !spokenText.isNullOrBlank()) {
            submitCommand(spokenText)
        } else {
            messages.add(
                Message(
                    "Ovoz tanilmadi yoki so'rov bekor qilindi. Nutqni tanish xizmati o'zbek tilini qo'llab-quvvatlashini tekshiring yoki buyruqni yozing.",
                    isUser = false
                )
            )
        }
    }

    fun startListening() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                isListening = true
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Gapiring... 🎤")
                }
                try {
                    speechLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    isListening = false
                    messages.add(
                        Message(
                            "Nutqni tanish xizmati topilmadi. Buyruqni yozing yoki telefonga nutqni tanish xizmatini o'rnating.",
                            isUser = false
                        )
                    )
                }
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "Logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Needle Assistant",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = when {
                                    isListening -> "🔴 Tinglamoqda..."
                                    speechOutputState == SpeechOutputState.READY -> "O'zbek AI Yordamchi"
                                    speechOutputState == SpeechOutputState.INITIALIZING -> "Ovoz xizmati ulanmoqda..."
                                    else -> "Matnli yordamchi"
                                },
                                fontSize = 12.sp,
                                color = if (isListening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showGroqSettings = true }) {
                        Text(if (groqConfigured) "⚙✓" else "⚙", fontSize = 20.sp)
                    }
                    IconButton(onClick = {
                        if (floatingAssistantEnabled) {
                            context.stopService(Intent(context, FloatingAssistantService::class.java))
                            floatingAssistantEnabled = false
                            messages.add(Message("Suzuvchi Needle tugmasi o'chirildi.", isUser = false))
                        } else if (Settings.canDrawOverlays(context)) {
                            try {
                                val serviceIntent = Intent(context, FloatingAssistantService::class.java)
                                    .setAction(FloatingAssistantService.ACTION_START)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
                                else context.startService(serviceIntent)
                                floatingAssistantEnabled = true
                                messages.add(Message("Suzuvchi Needle tugmasi yoqildi. Boshqa ilovada ham ekrandagi N tugmasini bosing.", isUser = false))
                            } catch (_: SecurityException) {
                                messages.add(Message("Suzuvchi tugmani yoqib bo'lmadi. Ruxsatlar va bildirishnoma sozlamalarini tekshiring.", isUser = false))
                            }
                        } else {
                            val settingsIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(settingsIntent)
                        }
                    }) {
                        Text(if (floatingAssistantEnabled) "N●" else "N+", fontSize = 16.sp)
                    }
                    if (isSpeaking) {
                        IconButton(onClick = {
                            deviceController.stopSpeaking()
                        }) {
                            Text("⏹", fontSize = 22.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ChatBottomBar(
                isAiThinking = isAiThinking,
                isListening = isListening,
                showQuickActions = showQuickActions,
                speechOutputState = speechOutputState,
                onQuickAction = ::submitCommand,
                onInstallUzbekVoice = {
                    try {
                        ttsDataLauncher.launch(deviceController.installUzbekVoiceDataIntent())
                    } catch (_: ActivityNotFoundException) {
                        messages.add(Message("Ovoz ma'lumotlarini o'rnatish oynasi ochilmadi. Qurilma sozlamalarida matnni ovozga aylantirish xizmatini tekshiring.", isUser = false))
                    }
                },
                onSendMessage = ::submitCommand,
                onMicClick = { startListening() }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { message ->
                ChatBubble(message)
            }
        }
    }

    if (showGroqSettings) {
        GroqSettingsDialog(
            isConfigured = groqConfigured,
            onDismiss = { showGroqSettings = false },
            onSave = { apiKey ->
                groqAssistant.saveApiKey(apiKey)
                groqConfigured = true
                showGroqSettings = false
                messages.add(Message("Groq AI sozlandi. Endi istalgan savolingizni yozishingiz mumkin.", isUser = false))
            },
            onRemove = {
                groqAssistant.removeApiKey()
                groqConfigured = false
                showGroqSettings = false
                messages.add(Message("Groq API kaliti qurilmadan o'chirildi.", isUser = false))
            }
        )
    }
}

@Composable
private fun GroqSettingsDialog(
    isConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq AI sozlamalari") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Yangi API kalitni Groq hisobingizdan kiriting. Kalit qurilmada Android Keystore bilan shifrlanadi. Groq ishlatish hisobingizdagi limitlarga bog'liq."
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim(); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (isConfigured) "Yangi API kalit" else "Groq API kalit") },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Yashir" else "Ko'rsat")
                        }
                    }
                )
                if (isConfigured) Text("Hozir kalit sozlangan.", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onSave(apiKey)
                } catch (exception: Exception) {
                    error = exception.message ?: "Kalitni saqlab bo'lmadi."
                }
            }, enabled = apiKey.isNotBlank()) { Text("Saqlash") }
        },
        dismissButton = {
            Row {
                if (isConfigured) {
                    TextButton(onClick = onRemove) { Text("O'chirish") }
                }
                TextButton(onClick = onDismiss) { Text("Bekor") }
            }
        }
    )
}

@Composable
fun ChatBubble(message: Message) {
    val bgColor = if (message.isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.isUser) {
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(30.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("N", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start) {
            Text(
                text = if (message.isUser) "Siz" else "Needle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Surface(
                modifier = Modifier.widthIn(max = 290.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (message.isUser) 16.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 16.dp
                ),
                color = bgColor,
                tonalElevation = 1.dp
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(12.dp),
                    color = textColor,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomBar(
    isAiThinking: Boolean,
    isListening: Boolean,
    showQuickActions: Boolean,
    speechOutputState: SpeechOutputState,
    onQuickAction: (String) -> Unit,
    onInstallUzbekVoice: () -> Unit,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(shadowElevation = 8.dp) {
        Column {
            if (showQuickActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Telegram" to "Telegramni och", "Instagram" to "Instagramni och", "YouTube" to "Youtubeni och", "Batareya" to "Batareya necha foiz").forEach { (label, command) ->
                        AssistChip(onClick = { onQuickAction(command) }, label = { Text(label) })
                    }
                }
            }
            if (speechOutputState == SpeechOutputState.UNAVAILABLE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "O'zbekcha ovoz topilmadi",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onInstallUzbekVoice) {
                        Text("Sozlash")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    placeholder = { Text(if (isAiThinking) "AI javob bermoqda..." else "Buyruq yoki savol yozing...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray
                    )
                )

                if (text.isNotBlank()) {
                    FloatingActionButton(
                        onClick = {
                            if (!isAiThinking) {
                                onSendMessage(text.trim())
                                text = ""
                            }
                        },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Text("➤", color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp)
                    }
                } else {
                    FloatingActionButton(
                        onClick = onMicClick,
                        shape = CircleShape,
                        containerColor = if (isListening) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Text(if (isListening) "⏹" else "🎤", fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

data class Message(val text: String, val isUser: Boolean)
