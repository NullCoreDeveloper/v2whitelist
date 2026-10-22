package com.kiktor.v2whitelist.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.composenativetray.tray.api.Tray
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import com.kiktor.v2whitelist.desktop.ui.components.Sidebar
import com.kiktor.v2whitelist.desktop.ui.screens.AppearanceScreen
import com.kiktor.v2whitelist.desktop.ui.screens.GeekModeScreen
import com.kiktor.v2whitelist.desktop.ui.screens.MainScreen
import com.kiktor.v2whitelist.desktop.ui.screens.SettingsScreen
import com.kiktor.v2whitelist.desktop.ui.screens.SubscriptionsScreen
import com.kiktor.v2whitelist.desktop.ui.i18n.I18n
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.DesktopTheme
import com.kiktor.v2whitelist.desktop.ui.theme.MeshGradientBackground
import com.kiktor.v2whitelist.desktop.ui.theme.getAccentColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.NewReleases
import com.kiktor.v2whitelist.core.model.update.CheckUpdateResult
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Загружает оригинальную иконку приложения из ресурсов (icon.png).
 * Если файл не найден — рисует fallback иконку с буквами "V2".
 */
private fun loadAppIcon(connected: Boolean): BitmapPainter {
    val stream = object {}::class.java.classLoader?.getResourceAsStream("icon.png")
    if (stream != null) {
        try {
            val img = ImageIO.read(stream)
            if (img != null) {
                val result = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
                val g = result.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(img, 0, 0, null)
                if (connected) {
                    val r = img.width / 5
                    val x = img.width - r - img.width / 12
                    val y = img.height - r - img.height / 12
                    g.color = Color(0, 230, 120)
                    g.fillOval(x, y, r, r)
                    g.color = Color(0, 0, 0, 180)
                    g.drawOval(x, y, r, r)
                }
                g.dispose()
                return BitmapPainter(result.toComposeImageBitmap())
            }
        } catch (_: Exception) {}
    }

    val size = 64
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = if (connected) Color(0, 200, 100) else Color(20, 20, 20)
    g.fillRoundRect(2, 2, size - 4, size - 4, 14, 14)
    g.color = Color.WHITE
    g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 22)
    val fm = g.fontMetrics
    val sw = fm.stringWidth("V2")
    g.drawString("V2", (size - sw) / 2, size / 2 + fm.ascent / 2 - 2)
    g.dispose()
    return BitmapPainter(image.toComposeImageBitmap())
}

@Composable
fun App(state: DesktopAppState) {
    val currentTab by state.activeTab.collectAsState()
    val servers by state.servers.collectAsState()
    val coreState by state.coreState.collectAsState()
    val themeMode by state.themeMode.collectAsState()
    val themePalette by state.themePalette.collectAsState()
    val isAmoled by state.pureBlackAmoled.collectAsState()
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val meshMode by state.meshGradientMode.collectAsState()
    val appLanguage by state.appLanguage.collectAsState()

    val strings = remember(appLanguage) { I18n.get(appLanguage) }

    CompositionLocalProvider(LocalAppStrings provides strings) {
        DesktopTheme(
            themeMode = themeMode,
            palette = themePalette,
            isAmoled = isAmoled
        ) {
            MeshGradientBackground(
                enabled = meshEnabled,
                mode = meshMode,
                connected = coreState == DesktopCoreState.RUNNING,
                accentColor = getAccentColor(themePalette)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(
                        currentTab = currentTab,
                        serverCount = servers.size,
                        coreState = coreState,
                        onTabSelected = { state.promptTabChange(it) }
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (currentTab) {
                            DesktopTab.MAIN -> MainScreen(state = state)
                            DesktopTab.SUBSCRIPTIONS -> SubscriptionsScreen(state = state)
                            DesktopTab.GEEK_MODE -> GeekModeScreen(state = state)
                            DesktopTab.APPEARANCE -> AppearanceScreen(state = state)
                            DesktopTab.SETTINGS -> SettingsScreen(state = state)
                        }
                    }
                }

                // Встроенный диалог ввода пароля sudo для TUN на Linux
                SudoPasswordDialog(state = state)

                // Предупреждение о несохраненных изменениях в настройках
                UnsavedSettingsDialog(state = state)

                // Диалог уведомления о новой версии приложения
                val availableUpdate by state.availableAppUpdate.collectAsState()
                val showUpdateDialog by state.showAppUpdateDialog.collectAsState()
                if (showUpdateDialog && availableUpdate != null) {
                    AppUpdateDialog(state = state, update = availableUpdate!!)
                }
            }
        }
    }
}

@Composable
fun SudoPasswordDialog(state: DesktopAppState) {
    val showDialog by state.showSudoDialog.collectAsState()
    val errorText by state.sudoError.collectAsState()
    val isVerifying by state.isVerifyingSudo.collectAsState()

    if (!showDialog) return

    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isVerifying) {
                state.dismissSudoDialog()
            }
        },
        icon = {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Активация режима TUN",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Для работы режима TUN (полная переадресация системного трафика) на Linux требуются привилегии суперпользователя (sudo).\n\nПароль вводится один раз и сохраняется только в оперативной памяти до закрытия приложения.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Пароль sudo", fontSize = 12.sp) },
                    placeholder = { Text("Введите пароль суперпользователя...", fontSize = 12.sp) },
                    singleLine = true,
                    enabled = !isVerifying,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        state.submitSudoPassword(passwordInput)
                    }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isVerifying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Проверка прав доступа sudo...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { state.enableTunWithoutSudo() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🚀 Запустить без sudo (через setcap)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (passwordInput.isNotBlank() && !isVerifying) {
                        state.submitSudoPassword(passwordInput)
                    }
                },
                enabled = passwordInput.isNotBlank() && !isVerifying
            ) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { state.dismissSudoDialog() },
                enabled = !isVerifying
            ) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun UnsavedSettingsDialog(state: DesktopAppState) {
    val showDialog by state.showUnsavedSettingsDialog.collectAsState()
    if (!showDialog) return

    AlertDialog(
        onDismissRequest = {
            state.cancelUnsavedDialog()
        },
        icon = {
            Icon(
                Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Несохранённые изменения",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "В настройках есть несохранённые изменения. Сохранить их перед переходом в другой раздел?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = { state.confirmSaveAndNavigate() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = androidx.compose.ui.graphics.Color.Black
                )
            ) {
                Text("Сохранить", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { state.cancelUnsavedDialog() },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Отмена")
                }
                OutlinedButton(
                    onClick = { state.confirmDiscardAndNavigate() },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text("Не сохранять")
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
fun AppUpdateDialog(state: DesktopAppState, update: CheckUpdateResult) {
    AlertDialog(
        onDismissRequest = {
            state.showAppUpdateDialog.value = false
        },
        icon = {
            Icon(
                Icons.Default.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Доступно обновление",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                if (update.isPreRelease) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "BETA",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Текущая версия",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "v${DesktopAppState.APP_VERSION}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text("➔", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Новая версия",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "v${update.latestVersion ?: "—"}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (!update.releaseNotes.isNullOrBlank()) {
                    Text(
                        text = "Что нового:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = update.releaseNotes.orEmpty().trim(),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (!update.fileName.isNullOrBlank()) {
                    Text(
                        text = "Файл: ${update.fileName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = update.downloadUrl ?: DesktopAppState.GITHUB_RELEASES_WEB
                    state.openWebUrl(url)
                    state.showAppUpdateDialog.value = false
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = androidx.compose.ui.graphics.Color.Black
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (update.downloadUrl != null) "Скачать обновление" else "Перейти к релизу",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        state.openWebUrl(DesktopAppState.GITHUB_RELEASES_WEB)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Все релизы")
                }
                TextButton(
                    onClick = {
                        state.showAppUpdateDialog.value = false
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Позже")
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

fun main() = application {
    val state = remember { DesktopAppState() }
    var isWindowVisible by remember { mutableStateOf(true) }
    val coreState by state.coreState.collectAsState()
    val isSystemProxyActive by state.isSystemProxyActive.collectAsState()
    val routingMode by state.routingMode.collectAsState()
    val minimizeToTray by state.minimizeToTray.collectAsState()

    val trayIcon = remember(coreState) {
        loadAppIcon(connected = coreState == DesktopCoreState.RUNNING)
    }

    val windowState = rememberWindowState(
        width = 1100.dp,
        height = 760.dp
    )

    Tray(
        icon = trayIcon,
        tooltip = "ПроБел — ${when (coreState) {
            DesktopCoreState.RUNNING -> "Подключено"
            DesktopCoreState.STARTING -> "Подключение..."
            DesktopCoreState.ERROR -> "Ошибка"
            DesktopCoreState.STOPPED -> "Отключено"
        }}",
        primaryAction = {
            isWindowVisible = !isWindowVisible
            if (isWindowVisible) {
                windowState.isMinimized = false
            }
        }
    ) {
        Item(
            label = if (isWindowVisible) "Свернуть в трей" else "Открыть ПроБел",
            onClick = {
                isWindowVisible = !isWindowVisible
                if (isWindowVisible) {
                    windowState.isMinimized = false
                }
            }
        )
        Divider()
        Item(
            label = when (coreState) {
                DesktopCoreState.RUNNING -> "⏹ Отключить VPN"
                DesktopCoreState.STARTING -> "⏳ Подключение..."
                DesktopCoreState.ERROR -> "🔄 Повторить подключение"
                DesktopCoreState.STOPPED -> "▶ Подключить VPN"
            },
            isEnabled = coreState != DesktopCoreState.STARTING,
            onClick = { state.toggleConnect() }
        )
        Item(
            label = if (routingMode == "TUN") "Режим: TUN → переключить на Прокси" else "Режим: Прокси → переключить на TUN",
            onClick = { state.toggleRoutingMode() }
        )
        Item(
            label = if (isSystemProxyActive) "✔ Системный прокси ОС: ВКЛ (нажать — выкл)" else "Системный прокси ОС: ВЫКЛ (нажать — вкл)",
            onClick = { state.toggleSystemProxy() }
        )
        Divider()
        Item(
            label = "Выход",
            onClick = {
                state.stopCore()
                exitApplication()
            }
        )
    }

    Window(
        onCloseRequest = {
            if (minimizeToTray) {
                isWindowVisible = false
            } else {
                state.stopCore()
                exitApplication()
            }
        },
        state = windowState,
        visible = isWindowVisible,
        title = "ПроБел — v2whitelist",
        icon = trayIcon
    ) {
        LaunchedEffect(isWindowVisible) {
            if (isWindowVisible) {
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        App(state = state)
    }
}
