package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.model.AppScenario
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import java.util.Locale

@Composable
fun ModeSettingsDialog(state: DesktopAppState) {
    val show by state.showModeSettingsDialog.collectAsState()
    if (!show) return

    val strings = LocalAppStrings.current
    val scenario by state.scenario.collectAsState()
    val speedTestOnCheck by state.speedTestOnCheck.collectAsState()
    val minSpeedMbps by state.minSpeedThresholdMbps.collectAsState()
    val serviceCheckEnabled by state.serviceCheckEnabled.collectAsState()
    val services by state.servicesList.collectAsState()
    val useCustomEndpoint by state.useCustomEndpoint.collectAsState()
    val customEndpointUrl by state.customEndpointUrl.collectAsState()

    val isExitNodeActive = useCustomEndpoint && customEndpointUrl.isNotBlank()
    val effectiveScenario = when (scenario) {
        AppScenario.WHITELIST -> AppScenario.WHITELIST
        AppScenario.VPN_BLACKLIST, AppScenario.YOUTUBE -> AppScenario.VPN_BLACKLIST
        else -> AppScenario.AUTO
    }

    val effectiveSpeed = if (speedTestOnCheck) minSpeedMbps else 0.0
    val isPresetValue = speedTestOnCheck && (effectiveSpeed == 1.0 || effectiveSpeed == 10.0 || effectiveSpeed == 30.0)
    var isCustomSpeedMode by remember(show) { mutableStateOf(!isPresetValue) }
    var customSpeedText by remember(show) {
        mutableStateOf(
            if (effectiveSpeed % 1.0 == 0.0) effectiveSpeed.toInt().toString()
            else String.format(Locale.US, "%.1f", effectiveSpeed)
        )
    }

    val enabledServicesCount = services.count { it.enabled }

    AlertDialog(
        onDismissRequest = { state.dismissModeSettingsDialog() },
        icon = {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = strings.modeDialogTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Режим сети: Авто | БС | ЧС
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.modeSectionNetwork,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModeOptionButton(
                            label = strings.modeOptionAuto,
                            selected = effectiveScenario == AppScenario.AUTO,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.AUTO) }
                        )
                        ModeOptionButton(
                            label = strings.modeOptionWl,
                            selected = effectiveScenario == AppScenario.WHITELIST,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.WHITELIST) }
                        )
                        ModeOptionButton(
                            label = strings.modeOptionBl,
                            selected = effectiveScenario == AppScenario.VPN_BLACKLIST,
                            modifier = Modifier.weight(1f),
                            onClick = { state.setScenario(AppScenario.VPN_BLACKLIST) }
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = when (effectiveScenario) {
                                AppScenario.WHITELIST -> strings.modeDescWl
                                AppScenario.VPN_BLACKLIST -> strings.modeDescBl
                                else -> strings.modeDescAuto
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // 2. Минимальная скорость: Мин | Баланс | Макс + кнопка Свой/Пресеты
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.modeSectionSpeed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = {
                                if (isCustomSpeedMode) {
                                    val target = when (effectiveSpeed) {
                                        1.0, 10.0, 30.0 -> effectiveSpeed
                                        else -> 10.0
                                    }
                                    state.setMinSpeedMbps(target)
                                    isCustomSpeedMode = false
                                } else {
                                    customSpeedText = if (effectiveSpeed % 1.0 == 0.0) {
                                        effectiveSpeed.toInt().toString()
                                    } else {
                                        String.format(Locale.US, "%.1f", effectiveSpeed)
                                    }
                                    isCustomSpeedMode = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isCustomSpeedMode) strings.modeSpeedToPresets else strings.modeSpeedToCustom,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (isCustomSpeedMode) {
                        OutlinedTextField(
                            value = customSpeedText,
                            onValueChange = { raw ->
                                customSpeedText = raw
                                val parsed = raw.trim().replace(',', '.').toDoubleOrNull()
                                if (parsed != null && parsed >= 0.0) {
                                    state.setMinSpeedMbps(parsed)
                                }
                            },
                            label = { Text(strings.modeSpeedCustomLabel) },
                            suffix = { Text(strings.modeSpeedUnitMbps, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SpeedPresetButton(
                                title = strings.modeSpeedPresetMin,
                                subtitle = strings.modeSpeedPresetMinSub,
                                selected = effectiveSpeed == 1.0,
                                modifier = Modifier.weight(1f),
                                onClick = { state.setMinSpeedMbps(1.0) }
                            )
                            SpeedPresetButton(
                                title = strings.modeSpeedPresetBalance,
                                subtitle = strings.modeSpeedPresetBalanceSub,
                                selected = effectiveSpeed == 10.0,
                                modifier = Modifier.weight(1f),
                                onClick = { state.setMinSpeedMbps(10.0) }
                            )
                            SpeedPresetButton(
                                title = strings.modeSpeedPresetMax,
                                subtitle = strings.modeSpeedPresetMaxSub,
                                selected = effectiveSpeed == 30.0,
                                modifier = Modifier.weight(1f),
                                onClick = { state.setMinSpeedMbps(30.0) }
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val speedDesc = if (isCustomSpeedMode) {
                            if (effectiveSpeed <= 0.0) {
                                strings.modeSpeedDescCustomDisabled
                            } else {
                                val formatted = if (effectiveSpeed % 1.0 == 0.0) {
                                    effectiveSpeed.toInt().toString()
                                } else {
                                    String.format(Locale.US, "%.1f", effectiveSpeed)
                                }
                                String.format(strings.modeSpeedDescCustomValue, formatted)
                            }
                        } else {
                            when (effectiveSpeed) {
                                1.0 -> strings.modeSpeedDescMin
                                30.0 -> strings.modeSpeedDescMax
                                else -> strings.modeSpeedDescBalance
                            }
                        }
                        Text(
                            text = speedDesc,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // 3. Проверка сервисов
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.modeSectionServices,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isExitNodeActive) 0.5f else 1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = strings.modeServicesToggleTitle,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = strings.modeServicesToggleDesc,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = serviceCheckEnabled && !isExitNodeActive,
                                    enabled = !isExitNodeActive,
                                    onCheckedChange = { state.setServiceCheckEnabled(it) }
                                )
                            }

                            if (isExitNodeActive) {
                                Text(
                                    text = strings.modeServicesDisabledByEndpoint,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            OutlinedButton(
                                onClick = { state.promptServiceCheckDialog() },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = String.format(strings.modeServicesConfigureBtn, enabledServicesCount, services.size),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.dismissModeSettingsDialog() }) {
                Text(strings.modeDoneBtn, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ModeOptionButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SpeedPresetButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
