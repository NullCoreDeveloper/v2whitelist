package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun AppearanceScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val themeMode by state.themeMode.collectAsState()
    val themePalette by state.themePalette.collectAsState()
    val isAmoled by state.pureBlackAmoled.collectAsState()
    val appLanguage by state.appLanguage.collectAsState()
    val meshEnabled by state.meshGradientEnabled.collectAsState()
    val meshMode by state.meshGradientMode.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (meshEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState)
    ) {
        // Заголовок
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = strings.appearanceTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = strings.appearanceSubtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(Modifier.height(20.dp))

        // 1. Тема интерфейса: Как в системе (авто), светлая, темная
        AppearanceCard(title = strings.themeCardTitle, meshEnabled = meshEnabled) {
            Text(
                text = strings.themeCardDesc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(
                    label = strings.themeSystem,
                    selected = themeMode.equals("SYSTEM", ignoreCase = true),
                    onClick = { state.setThemeMode("SYSTEM") }
                )
                OptionChip(
                    label = strings.themeLight,
                    selected = themeMode.equals("LIGHT", ignoreCase = true),
                    onClick = { state.setThemeMode("LIGHT") }
                )
                OptionChip(
                    label = strings.themeDark,
                    selected = themeMode.equals("DARK", ignoreCase = true),
                    onClick = { state.setThemeMode("DARK") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 2. Цветовая палитра: настраиваемый акцент вместо Material You
        AppearanceCard(title = strings.paletteCardTitle, meshEnabled = meshEnabled) {
            Text(
                text = strings.paletteCardDesc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ColorPaletteOption(name = "Cyan", color = AccentCyan, selected = themePalette == "CYAN", onClick = { state.setThemePalette("CYAN") })
                ColorPaletteOption(name = "Blue", color = AccentBlue, selected = themePalette == "BLUE", onClick = { state.setThemePalette("BLUE") })
                ColorPaletteOption(name = "Mint", color = AccentMint, selected = themePalette == "MINT", onClick = { state.setThemePalette("MINT") })
                ColorPaletteOption(name = "Purple", color = AccentPurple, selected = themePalette == "PURPLE", onClick = { state.setThemePalette("PURPLE") })
                ColorPaletteOption(name = "Amber", color = AccentAmber, selected = themePalette == "AMBER", onClick = { state.setThemePalette("AMBER") })
                ColorPaletteOption(name = "Rose", color = AccentRose, selected = themePalette == "ROSE", onClick = { state.setThemePalette("ROSE") })
            }
        }

        Spacer(Modifier.height(16.dp))

        // 3. Чистый черный (AMOLED)
        AppearanceCard(title = strings.amoledCardTitle, meshEnabled = meshEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = strings.amoledSwitchTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = strings.amoledSwitchDesc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = isAmoled,
                    onCheckedChange = { state.setPureBlackAmoled(it) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 4. Язык
        AppearanceCard(title = strings.languageCardTitle, meshEnabled = meshEnabled) {
            Text(
                text = strings.languageCardDesc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(label = strings.langSystem, selected = appLanguage == "SYSTEM", onClick = { state.setAppLanguage("SYSTEM") })
                OptionChip(label = "Русский", selected = appLanguage == "RU", onClick = { state.setAppLanguage("RU") })
                OptionChip(label = "English", selected = appLanguage == "EN", onClick = { state.setAppLanguage("EN") })
                OptionChip(label = "فارسی (Farsi)", selected = appLanguage == "FA", onClick = { state.setAppLanguage("FA") })
                OptionChip(label = "简体中文", selected = appLanguage == "ZH", onClick = { state.setAppLanguage("ZH") })
            }
        }

        Spacer(Modifier.height(16.dp))

        // 5. Градиентные фоны (Mesh)
        AppearanceCard(title = strings.meshCardTitle, meshEnabled = meshEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = strings.meshSwitchTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = strings.meshSwitchDesc,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = meshEnabled,
                    onCheckedChange = { state.setMeshGradientEnabled(it) }
                )
            }

            if (meshEnabled) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.meshModeLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip(
                        label = strings.meshModeStatic,
                        selected = meshMode.equals("STATIC", ignoreCase = true),
                        onClick = { state.setMeshGradientMode("STATIC") }
                    )
                    OptionChip(
                        label = strings.meshModeBreathing,
                        selected = meshMode.equals("BREATHING", ignoreCase = true),
                        onClick = { state.setMeshGradientMode("BREATHING") }
                    )
                    OptionChip(
                        label = strings.meshModeReactive,
                        selected = meshMode.equals("REACTIVE", ignoreCase = true),
                        onClick = { state.setMeshGradientMode("REACTIVE") }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    title: String,
    meshEnabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (meshEnabled) 0.82f else 1f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ColorPaletteOption(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
