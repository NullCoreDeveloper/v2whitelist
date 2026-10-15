package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Palette
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
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun Sidebar(
    currentTab: DesktopTab,
    serverCount: Int,
    coreState: DesktopCoreState,
    onTabSelected: (DesktopTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(230.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // App Branding (оригинальный "пробел" с цветным бейджем)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 20.dp, top = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "ПроБел",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "v2whitelist Desktop",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 16.dp))

            // 1. Меню (Главная)
            NavItem(
                icon = Icons.Default.Home,
                label = strings.menu,
                badge = if (serverCount > 0) serverCount.toString() else null,
                selected = currentTab == DesktopTab.MAIN,
                onClick = { onTabSelected(DesktopTab.MAIN) }
            )

            Spacer(Modifier.height(8.dp))

            // 2. Управление подписками
            NavItem(
                icon = Icons.Default.CloudDownload,
                label = strings.subscriptions,
                selected = currentTab == DesktopTab.SUBSCRIPTIONS,
                onClick = { onTabSelected(DesktopTab.SUBSCRIPTIONS) }
            )

            Spacer(Modifier.height(8.dp))

            // 3. Geek Mode
            NavItem(
                icon = Icons.Default.Terminal,
                label = strings.geekMode,
                selected = currentTab == DesktopTab.GEEK_MODE,
                onClick = { onTabSelected(DesktopTab.GEEK_MODE) }
            )

            Spacer(Modifier.height(8.dp))

            // 4. Оформление
            NavItem(
                icon = Icons.Default.Palette,
                label = strings.appearance,
                selected = currentTab == DesktopTab.APPEARANCE,
                onClick = { onTabSelected(DesktopTab.APPEARANCE) }
            )

            Spacer(Modifier.height(8.dp))

            // 5. Настройки
            NavItem(
                icon = Icons.Default.Settings,
                label = strings.settings,
                selected = currentTab == DesktopTab.SETTINGS,
                onClick = { onTabSelected(DesktopTab.SETTINGS) }
            )
        }

        // Bottom status badge
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (color, text) = when (coreState) {
                    DesktopCoreState.RUNNING -> AccentMint to "Подключено"
                    DesktopCoreState.STARTING -> AccentAmber to "Подключение..."
                    DesktopCoreState.ERROR -> AccentRed to "Ошибка"
                    DesktopCoreState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant to "Отключено"
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
                    color = MaterialTheme.colorScheme.onSurface,
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
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = fontWeight,
                modifier = Modifier.weight(1f)
            )
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = badge,
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
