package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.engine.DesktopProxyManager
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun ProxyModeCard(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val httpPort by state.httpPort.collectAsState()
    val socksPort by state.socksPort.collectAsState()
    val isSystemProxyActive by state.isSystemProxyActive.collectAsState()
    val allowLan by state.allowLan.collectAsState()

    val localIp = remember(allowLan) {
        if (allowLan) DesktopProxyManager.getLocalIpAddress() else null
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = BorderStroke(1.dp, if (isSystemProxyActive) AccentMint.copy(alpha = 0.5f) else DarkBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row: Title & System Proxy Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Режим чистого прокси (Standalone Proxy)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (allowLan) "Работает без TUN: локальные порты открыты для LAN" else "Работает без TUN адаптера и прав root",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                // System Proxy Toggle Switch
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSystemProxyActive) AccentMint.copy(alpha = 0.15f) else DarkSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSystemProxyActive) AccentMint.copy(alpha = 0.4f) else DarkBorder),
                    modifier = Modifier.clickable { state.toggleSystemProxy() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isSystemProxyActive) AccentMint else TextTertiary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isSystemProxyActive) "Системный прокси ОС: ВКЛ" else "Системный прокси ОС: ВЫКЛ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSystemProxyActive) AccentMint else TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Inbound Endpoints Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val host = if (allowLan) "0.0.0.0" else "127.0.0.1"
                EndpointChip(
                    protocol = "SOCKS5",
                    endpoint = "$host:$socksPort",
                    onClick = {
                        state.copyToClipboard(
                            DesktopProxyManager.getSocks5Url(socksPort),
                            "SOCKS5 адрес $host:$socksPort"
                        )
                    }
                )
                EndpointChip(
                    protocol = "HTTP",
                    endpoint = "$host:$httpPort",
                    onClick = {
                        state.copyToClipboard(
                            DesktopProxyManager.getHttpUrl(httpPort),
                            "HTTP адрес $host:$httpPort"
                        )
                    }
                )

                if (allowLan && localIp != null) {
                    EndpointChip(
                        protocol = "LAN IP",
                        endpoint = "$localIp:$httpPort",
                        onClick = {
                            state.copyToClipboard(
                                "http://$localIp:$httpPort",
                                "Адрес для устройств в локальной сети http://$localIp:$httpPort"
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            // Quick App Export Buttons
            Text(
                text = "Быстрая настройка приложений в 1 клик:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickExportButton(
                    title = "Telegram",
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = {
                        val tgLink = DesktopProxyManager.getTelegramLink(socksPort)
                        state.copyToClipboard(tgLink, "Ссылка Telegram Proxy")
                        state.openUri(tgLink)
                    }
                )

                QuickExportButton(
                    title = "Терминал (Bash)",
                    icon = Icons.Default.Terminal,
                    onClick = {
                        val cmd = DesktopProxyManager.getTerminalExport(socksPort, httpPort)
                        state.copyToClipboard(cmd, "Команда экспорта для терминала")
                    }
                )

                QuickExportButton(
                    title = "PowerShell",
                    icon = Icons.Default.Code,
                    onClick = {
                        val cmd = DesktopProxyManager.getPowerShellExport(socksPort, httpPort)
                        state.copyToClipboard(cmd, "Команда экспорта для PowerShell")
                    }
                )

                QuickExportButton(
                    title = "Браузер",
                    icon = Icons.Default.Web,
                    onClick = {
                        val flag = DesktopProxyManager.getBrowserFlag(socksPort)
                        state.copyToClipboard(flag, "Параметр запуска браузера")
                    }
                )

                QuickExportButton(
                    title = "Git Proxy",
                    icon = Icons.Default.Commit,
                    onClick = {
                        val gitCmd = DesktopProxyManager.getGitConfig(socksPort)
                        state.copyToClipboard(gitCmd, "Настройка Git proxy")
                    }
                )
            }
        }
    }
}

@Composable
private fun EndpointChip(
    protocol: String,
    endpoint: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceElevated,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = protocol,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentCyan
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = endpoint,
                fontSize = 12.sp,
                color = TextPrimary
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun QuickExportButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceElevated,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}
