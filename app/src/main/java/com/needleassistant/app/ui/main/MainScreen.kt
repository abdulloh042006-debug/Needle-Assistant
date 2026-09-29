package com.needleassistant.app.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavKey
import com.needleassistant.app.R
import com.needleassistant.app.system.DeviceController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceController = remember { DeviceController(context) }
    val messages = remember {
        mutableStateListOf(
            Message("Assalomu alaykum! Men Needle Assistant'man. 👋\n\n\"yordam\" yozing — qila oladigan ishlarimni ko'rasiz.\n\n🎤 tugmasini bosib ovozdan ham gapira olasiz!", isUser = false)
        )
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

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

    // Speech recognition launcher
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        val spokenText = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()

        if (!spokenText.isNullOrBlank()) {
            messages.add(Message(spokenText, isUser = true))
            val response = deviceController.processCommand(spokenText)
            messages.add(Message(response, isUser = false))
            isSpeaking = true
            deviceController.speak(response)
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        } else {
            messages.add(Message("Ovoz tanib olinmadi. Qayta urinib ko'ring 🎤", isUser = false))
        }
    }

    fun startListening() {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                isListening = true
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "uz-UZ")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Gapiring... 🎤")
                }
                speechLauncher.launch(intent)
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
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
                                text = if (isListening) "🔴 Tinglamoqda..." else "O'zbek AI Yordamchi",
                                fontSize = 12.sp,
                                color = if (isListening) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                },
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = {
                            deviceController.stopSpeaking()
                            isSpeaking = false
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
                isListening = isListening,
                onSendMessage = { text ->
                    messages.add(Message(text, isUser = true))
                    val response = deviceController.processCommand(text)
                    messages.add(Message(response, isUser = false))
                    isSpeaking = true
                    deviceController.speak(response)
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                },
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
}

@Composable
fun ChatBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (message.isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (message.isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSecondaryContainer

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Text(
            text = message.text,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp
                    )
                )
                .background(bgColor)
                .padding(12.dp),
            color = textColor,
            fontSize = 15.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomBar(
    isListening: Boolean,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(shadowElevation = 8.dp) {
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
                placeholder = { Text("Buyruq yozing...") },
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
                        onSendMessage(text.trim())
                        text = ""
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

data class Message(val text: String, val isUser: Boolean)
