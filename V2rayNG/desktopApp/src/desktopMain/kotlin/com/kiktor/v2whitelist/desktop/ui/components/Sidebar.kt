package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.engine.DesktopCoreState
import com.kiktor.v2whitelist.desktop.state.DesktopTab
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun Sidebar(
    currentTab: DesktopTab,
    serverCount: Int,
    coreState: DesktopCoreState,
    onTabSelected: (DesktopTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(DarkSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // App Branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp, top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AccentCyan, AccentPurple)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "V2",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "v2whitelist",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Desktop Beta",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(bottom = 16.dp))

            // Navigation items
            NavItem(
                icon = Icons.Default.Dns,
                label = "Серверы",
                badge = if (serverCount > 0) serverCount.toString() else null,
                selected = currentTab == DesktopTab.SERVERS,
                onClick = { onTabSelected(DesktopTab.SERVERS) }
            )

            Spacer(Modifier.height(8.dp))

            NavItem(
                icon = Icons.Default.Terminal,
                label = "Консоль / Логи",
                selected = currentTab == DesktopTab.LOGS,
                onClick = { onTabSelected(DesktopTab.LOGS) }
            )

            Spacer(Modifier.height(8.dp))

            NavItem(
                icon = Icons.Default.Settings,
                label = "Настройки",
                selected = currentTab == DesktopTab.SETTINGS,
                onClick = { onTabSelected(DesktopTab.SETTINGS) }
            )
        }

        // Bottom status badge
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DarkSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (color, text) = when (coreState) {
                    DesktopCoreState.RUNNING -> AccentMint to "Подключено"
                    DesktopCoreState.STARTING -> AccentAmber to "Запуск..."
                    DesktopCoreState.ERROR -> AccentRed to "Ошибка"
                    DesktopCoreState.STOPPED -> TextTertiary to "Остановлено"
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    badge: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgModifier = if (selected) {
        Modifier.background(
            color = AccentCyan.copy(alpha = 0.15f),
            shape = RoundedCornerShape(10.dp)
        )
    } else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(bgModifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) AccentCyan else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                color = if (selected) TextPrimary else TextSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }

        badge?.let {
            Surface(
                shape = CircleShape,
                color = if (selected) AccentCyan else DarkSurfaceElevated,
                contentColor = if (selected) Color.Black else TextSecondary
            ) {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
