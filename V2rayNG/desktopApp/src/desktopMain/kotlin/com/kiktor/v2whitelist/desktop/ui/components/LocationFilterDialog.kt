package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.core.domain.selector.LocationFilterMode
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.util.CountryNames

@Composable
fun LocationFilterDialog(state: DesktopAppState) {
    val show by state.showLocationFilterDialog.collectAsState()
    if (!show) return

    val currentMode by state.locationFilterMode.collectAsState()
    val activeSet by state.locationFilterSet.collectAsState()
    val availableLocations by state.availableLocations.collectAsState()

    AlertDialog(
        onDismissRequest = { state.dismissLocationFilterDialog() },
        icon = {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Фильтр локаций серверов",
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
                    text = "Используется при интеллектуальном подборе серверов (SmartConnect) и в авто-режиме.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Режим фильтрации
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.setLocationFilterMode(LocationFilterMode.EXCLUDE) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = currentMode == LocationFilterMode.EXCLUDE,
                                onClick = { state.setLocationFilterMode(LocationFilterMode.EXCLUDE) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Исключать выбранные", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Чёрный список стран (серверы этих стран будут пропущены)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.setLocationFilterMode(LocationFilterMode.WHITELIST) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = currentMode == LocationFilterMode.WHITELIST,
                                onClick = { state.setLocationFilterMode(LocationFilterMode.WHITELIST) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Только выбранные", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Белый список стран (подключение только к отмеченным странам)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.setLocationFilterMode(LocationFilterMode.DISABLED) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = currentMode == LocationFilterMode.DISABLED,
                                onClick = { state.setLocationFilterMode(LocationFilterMode.DISABLED) }
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text("Отключить фильтр", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Учитывать серверы любых стран без ограничений", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Кнопки быстрого выбора
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { state.resetLocationFilterToDefault() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Дефолт (искл. РФ)", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { state.clearAllLocations() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Снять все", fontSize = 11.sp)
                    }
                }

                // Список стран с чекбоксами
                if (availableLocations.isEmpty()) {
                    Text(
                        text = "В активных подписках нет серверов с распознанными локациями",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(availableLocations, key = { it.key }) { entry ->
                            val tag = entry.key
                            val count = entry.value
                            val isChecked = activeSet.contains(tag)
                            val displayName = CountryNames.getDisplayName(tag)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { state.toggleLocationFilter(tag) }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { state.toggleLocationFilter(tag) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = displayName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$count",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { state.dismissLocationFilterDialog() }) {
                Text("Готово")
            }
        }
    )
}
