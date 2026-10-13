package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.subscription.defaults.DefaultSubscriptions
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun SubscriptionsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val isUpdatingSub by state.isUpdatingSub.collectAsState()
    val enabledSubIds by state.enabledDefaultSubIds.collectAsState()
    val customUrl by state.subscriptionUrl.collectAsState()
    val userAgent by state.subscriptionUserAgent.collectAsState()
    val updateOnlyViaProxy by state.subscriptionUpdateOnlyViaProxy.collectAsState()
    val subThreads by state.subscriptionThreads.collectAsState()
    val autoUpdate by state.subscriptionAutoUpdate.collectAsState()

    var customUrlInput by remember(customUrl) { mutableStateOf(customUrl) }
    var userAgentInput by remember(userAgent) { mutableStateOf(userAgent) }
    var threadsInput by remember(subThreads) { mutableStateOf(subThreads.toString()) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        // Header Row with Refresh Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Подписки и Зеркала",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Управление источниками конфигураций и гонкой зеркал",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Button(
                onClick = { state.updateAllActiveSubscriptions() },
                enabled = !isUpdatingSub,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(44.dp)
            ) {
                if (isUpdatingSub) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Обновление...", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Обновить все подписки", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Prepopulated Default Subscriptions
        Text(
            text = "Встроенные проверенные подписки (Россия / Анти-блокировки):",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(Modifier.height(10.dp))

        for (sub in DefaultSubscriptions.PREPOPULATED_SUBS) {
            val isEnabled = enabledSubIds.contains(sub.id)
            DefaultSubCard(
                sub = sub,
                isEnabled = isEnabled,
                onToggle = { state.toggleDefaultSub(sub.id) }
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Custom User Subscription URL
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Кастомная подписка пользователя:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Можно указать несколько зеркал одной подписки через символ разделителя «|»",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = customUrlInput,
                    onValueChange = { customUrlInput = it },
                    placeholder = { Text("https://mirror1.com/sub.txt | https://mirror2.com/sub.txt", color = TextTertiary, fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedContainerColor = DarkSurfaceElevated,
                        unfocusedContainerColor = DarkSurfaceElevated,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Subscription Advanced Settings Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Параметры сетевой загрузки подписок:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                // User Agent Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Кастомный User-Agent HTTP:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Используется при запросе зеркал подписок (например v2rayNG/1.10.x, ClashMeta).", color = TextSecondary, fontSize = 11.sp)
                    }
                    OutlinedTextField(
                        value = userAgentInput,
                        onValueChange = { userAgentInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurfaceElevated,
                            unfocusedContainerColor = DarkSurfaceElevated,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.width(220.dp).height(50.dp)
                    )
                }

                HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))

                // Parallel Threads
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Потоков для гонки зеркал:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Количество одновременных горутин для параллельной гонки зеркал.", color = TextSecondary, fontSize = 11.sp)
                    }
                    OutlinedTextField(
                        value = threadsInput,
                        onValueChange = { threadsInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedContainerColor = DarkSurfaceElevated,
                            unfocusedContainerColor = DarkSurfaceElevated,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.width(100.dp).height(50.dp)
                    )
                }

                HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))

                // Only via VPN / Proxy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Обновлять только через VPN/прокси:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Если прямое подключение к репозиториям заблокировано оператором.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = updateOnlyViaProxy,
                        onCheckedChange = { state.saveSettings(
                            subUrl = customUrlInput,
                            xrayPath = state.xrayBinaryPath.value,
                            v2wPath = state.v2wScanPath.value,
                            httpP = state.httpPort.value,
                            socksP = state.socksPort.value,
                            subOnlyViaProxy = it
                        ) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = AccentCyan,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        )
                    )
                }

                HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))

                // Auto update toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Фоновое автообновление подписок:", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Автоматически обновлять списки каждые 12 часов в фоне.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoUpdate,
                        onCheckedChange = { state.saveSettings(
                            subUrl = customUrlInput,
                            xrayPath = state.xrayBinaryPath.value,
                            v2wPath = state.v2wScanPath.value,
                            httpP = state.httpPort.value,
                            socksP = state.socksPort.value,
                            subAutoUpdate = it
                        ) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = AccentMint,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = DarkSurfaceElevated
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultSubCard(
    sub: CustomSubData,
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    val mirrorsCount = remember(sub.url) { sub.url.split("|").size }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, if (isEnabled) AccentCyan.copy(alpha = 0.5f) else DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sub.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEnabled) TextPrimary else TextSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AccentCyan.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$mirrorsCount зеркал(а)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentCyan,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Сценарии: ${sub.targetScenarios.joinToString()}",
                    fontSize = 12.sp,
                    color = TextTertiary
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = AccentCyan,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DarkSurfaceElevated
                )
            )
        }
    }
}
