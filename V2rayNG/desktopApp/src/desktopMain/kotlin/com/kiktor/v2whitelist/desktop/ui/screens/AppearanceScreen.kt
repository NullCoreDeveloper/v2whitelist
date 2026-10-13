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
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun AppearanceScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
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
                text = "Оформление",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = "Настройка визуального стиля, темы, палитры цветов и градиентных фонов",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(Modifier.height(20.dp))

        // 1. Тема интерфейса: Как в системе (авто), светлая, темная
        AppearanceCard(title = "Тема интерфейса") {
            Text(
                text = "Выберите режим отображения интерфейса:",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(
                    label = "Как в системе (авто)",
                    selected = themeMode.equals("SYSTEM", ignoreCase = true),
                    onClick = { state.setThemeMode("SYSTEM") }
                )
                OptionChip(
                    label = "Светлая",
                    selected = themeMode.equals("LIGHT", ignoreCase = true),
                    onClick = { state.setThemeMode("LIGHT") }
                )
                OptionChip(
                    label = "Тёмная",
                    selected = themeMode.equals("DARK", ignoreCase = true),
                    onClick = { state.setThemeMode("DARK") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 2. Цветовая палитра: настраиваемый акцент вместо Material You
        AppearanceCard(title = "Цветовая палитра") {
            Text(
                text = "Настройка акцентного цвета элементов интерфейса и кнопок:",
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
        AppearanceCard(title = "Чистый чёрный (AMOLED)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Включить истинный чёрный фон #000000",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Истинный глубокий черный фон #000000 в темной теме для OLED экранов",
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
        AppearanceCard(title = "Язык приложения") {
            Text(
                text = "Выберите язык интерфейса:",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(label = "Язык системы", selected = appLanguage == "SYSTEM", onClick = { state.setAppLanguage("SYSTEM") })
                OptionChip(label = "Русский", selected = appLanguage == "RU", onClick = { state.setAppLanguage("RU") })
                OptionChip(label = "English", selected = appLanguage == "EN", onClick = { state.setAppLanguage("EN") })
                OptionChip(label = "فارسی (Farsi)", selected = appLanguage == "FA", onClick = { state.setAppLanguage("FA") })
                OptionChip(label = "简体中文", selected = appLanguage == "ZH", onClick = { state.setAppLanguage("ZH") })
            }
        }

        Spacer(Modifier.height(16.dp))

        // 5. Градиентные фоны (Mesh)
        AppearanceCard(title = "Градиентные фоны (Mesh)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Медленно переливающийся цветной фон",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "По умолчанию выключено. Добавляет кинематографический анимированный фон.",
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
                    text = "Режим анимации Mesh Gradient:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip(
                        label = "Статичный",
                        selected = meshMode.equals("STATIC", ignoreCase = true),
                        onClick = { state.setMeshGradientMode("STATIC") }
                    )
                    OptionChip(
                        label = "Дыхание (плавное перетекание)",
                        selected = meshMode.equals("BREATHING", ignoreCase = true),
                        onClick = { state.setMeshGradientMode("BREATHING") }
                    )
                    OptionChip(
                        label = "Реактивный (стягивание при старте)",
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
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
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
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) color else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = name,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) color else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
