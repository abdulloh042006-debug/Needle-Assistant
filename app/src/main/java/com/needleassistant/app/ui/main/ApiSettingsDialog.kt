package com.needleassistant.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.needleassistant.app.system.ApiCredentialManager
import com.needleassistant.app.system.DeepSeekAssistant
import kotlinx.coroutines.launch

@Composable
internal fun ApiSettingsDialog(
    deepSeekAssistant: DeepSeekAssistant,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { ApiCredentialManager(context) }
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var hasSavedToken by remember { mutableStateOf(credentialManager.hasApiKey()) }
    var isTesting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DeepSeek API sozlamalari") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "API token faqat ushbu qurilmada Android Keystore bilan shifrlanib saqlanadi. " +
                        "Tokenni chatga yubormang."
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        status = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("DeepSeek API token") },
                    placeholder = { Text(if (hasSavedToken) "Saqlangan token bor — yangisini kiriting" else "API tokenni kiriting") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Text(
                    if (hasSavedToken) "Holat: token saqlangan" else "Holat: token kiritilmagan",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Ulandi") || it.contains("saqlandi") || it.contains("o'chirildi")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (isTesting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("API ulanishi tekshirilmoqda...")
                    }
                }
                if (hasSavedToken) {
                    TextButton(
                        onClick = {
                            credentialManager.clearApiKey()
                            hasSavedToken = false
                            token = ""
                            status = "Token o'chirildi."
                        },
                        enabled = !isTesting
                    ) {
                        Text("Saqlangan tokenni o'chirish")
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !isTesting) {
                    Text("Yopish")
                }
                TextButton(
                    onClick = {
                        runCatching { credentialManager.saveApiKey(token) }
                            .onSuccess {
                                hasSavedToken = true
                                token = ""
                                status = "Token xavfsiz saqlandi."
                            }
                            .onFailure {
                                status = it.message ?: "Tokenni saqlashda xatolik."
                            }
                    },
                    enabled = token.isNotBlank() && !isTesting
                ) {
                    Text("Saqlash")
                }
                Button(
                    onClick = {
                        if (token.isNotBlank()) {
                            runCatching { credentialManager.saveApiKey(token) }
                                .onSuccess {
                                    hasSavedToken = true
                                    token = ""
                                }
                                .onFailure {
                                    status = it.message ?: "Tokenni saqlashda xatolik."
                                    return@Button
                                }
                        }
                        isTesting = true
                        status = null
                        scope.launch {
                            status = try {
                                deepSeekAssistant.testConnection()
                                "Ulandi: DeepSeek API javob qaytardi."
                            } catch (exception: Exception) {
                                exception.message ?: "API ulanishida xatolik."
                            }
                            isTesting = false
                        }
                    },
                    enabled = (token.isNotBlank() || hasSavedToken) && !isTesting
                ) {
                    Text("Tekshirish")
                }
            }
        }
    )
}
