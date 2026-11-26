package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.kiktor.v2whitelist.desktop.data.Argon2Params
import com.kiktor.v2whitelist.desktop.data.DesktopStorageManager
import com.kiktor.v2whitelist.desktop.ui.theme.AccentMint
import com.kiktor.v2whitelist.desktop.ui.theme.DesktopTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DesktopStorageUnlockWindow(
    onUnlocked: () -> Unit,
    onExit: () -> Unit
) {
    val windowState = rememberWindowState(width = 440.dp, height = 330.dp)
    Window(
        onCloseRequest = onExit,
        state = windowState,
        resizable = false,
        title = "ПроБел — Разблокировка хранилища"
    ) {
        DesktopTheme {
            val scope = rememberCoroutineScope()
            var password by remember { mutableStateOf("") }
            var passwordVisible by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var errorMsg by remember { mutableStateOf<String?>(null) }

            fun attemptUnlock() {
                if (busy) return
                val chars = password.toCharArray()
                if (chars.isEmpty()) {
                    errorMsg = "Введите мастер-пароль"
                    return
                }
                busy = true
                errorMsg = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            DesktopStorageManager.unlockStorage(chars)
                        } finally {
                            chars.fill('\u0000')
                        }
                    }
                    busy = false
                    result.fold(
                        onSuccess = {
                            password = ""
                            onUnlocked()
                        },
                        onFailure = { e ->
                            errorMsg = e.message ?: "Неверный мастер-пароль"
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Хранилище зашифровано (Argon2id)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Введите мастер-пароль для расшифровки серверов и настроек.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMsg = null
                        },
                        label = { Text("Мастер-пароль") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { attemptUnlock() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    errorMsg?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onExit, enabled = !busy) {
                            Text("Выход")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { attemptUnlock() },
                            enabled = !busy,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Разблокировать")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopStorageEncryptionCard() {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(DesktopStorageManager.isEncryptionEnabled()) }
    val initialParams = remember { DesktopStorageManager.currentArgon2Params() }
    var memoryMbText by remember { mutableStateOf((initialParams.memoryKiB / 1024).toString()) }
    var iterationsText by remember { mutableStateOf(initialParams.iterations.toString()) }
    var parallelismText by remember { mutableStateOf(initialParams.parallelism.toString()) }

    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun applyParamsPreset(preset: Argon2Params) {
        memoryMbText = (preset.memoryKiB / 1024).toString()
        iterationsText = preset.iterations.toString()
        parallelismText = preset.parallelism.toString()
    }

    fun buildParamsFromInputs(): Argon2Params {
        val memMb = (memoryMbText.toIntOrNull() ?: 64).coerceIn(16, 512)
        val iter = (iterationsText.toIntOrNull() ?: 3).coerceIn(1, 10)
        val par = (parallelismText.toIntOrNull() ?: 1).coerceIn(1, 4)
        return Argon2Params(memoryKiB = memMb * 1024, iterations = iter, parallelism = par).validated()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (enabled) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (enabled) AccentMint else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Шифрование локального хранилища (Argon2id)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (enabled) {
                            val p = DesktopStorageManager.currentArgon2Params()
                            "Включено (AES-256-GCM + Argon2id: ${p.memoryKiB / 1024} МБ, ${p.iterations} итер., ${p.parallelism} пот.)"
                        } else {
                            "Выключено — опциональная защита servers.json и settings.json мастер-паролем"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Text("Профиль Argon2id", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { applyParamsPreset(Argon2Params.FAST) },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Быстрый (32 МБ)", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyParamsPreset(Argon2Params.BALANCED) },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Баланс (64 МБ)", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyParamsPreset(Argon2Params.STRONG) },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Усиленный (128 МБ)", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = memoryMbText,
                    onValueChange = { memoryMbText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Память (МБ, 16-512)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = iterationsText,
                    onValueChange = { iterationsText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Итерации (1-10)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = parallelismText,
                    onValueChange = { parallelismText = it.filter(Char::isDigit).take(1) },
                    label = { Text("Потоки (1-4)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (enabled) "Новый мастер-пароль" else "Мастер-пароль") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Подтверждение пароля") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (password.isEmpty()) {
                            feedback = false to "Введите мастер-пароль"
                            return@Button
                        }
                        if (password != confirmPassword) {
                            feedback = false to "Пароли не совпадают"
                            return@Button
                        }
                        val params = buildParamsFromInputs()
                        val pwdChars = password.toCharArray()
                        busy = true
                        feedback = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) {
                                try {
                                    DesktopStorageManager.enableOrUpdateEncryption(pwdChars, params)
                                } finally {
                                    pwdChars.fill('\u0000')
                                }
                            }
                            busy = false
                            res.fold(
                                onSuccess = {
                                    enabled = true
                                    password = ""
                                    confirmPassword = ""
                                    memoryMbText = (params.memoryKiB / 1024).toString()
                                    iterationsText = params.iterations.toString()
                                    parallelismText = params.parallelism.toString()
                                    feedback = true to "Шифрование хранилища включено (Argon2id)"
                                },
                                onFailure = { e ->
                                    feedback = false to "Ошибка шифрования: ${e.message}"
                                }
                            )
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (enabled) "Обновить пароль / Argon2id" else "Включить шифрование")
                }

                if (enabled) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            feedback = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    DesktopStorageManager.disableEncryption()
                                }
                                busy = false
                                res.fold(
                                    onSuccess = {
                                        enabled = false
                                        password = ""
                                        confirmPassword = ""
                                        feedback = true to "Шифрование отключено, файлы пересохранены в открытом виде"
                                    },
                                    onFailure = { e ->
                                        feedback = false to "Ошибка расшифровки: ${e.message}"
                                    }
                                )
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Отключить шифрование")
                    }
                }
            }

            feedback?.let { (ok, msg) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    color = if (ok) AccentMint else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
