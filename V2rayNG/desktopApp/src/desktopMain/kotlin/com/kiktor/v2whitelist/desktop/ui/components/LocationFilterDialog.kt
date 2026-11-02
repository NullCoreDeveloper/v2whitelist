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
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.util.CountryNames

@Composable
fun LocationFilterDialog(state: DesktopAppState) {
    val show by state.showLocationFilterDialog.collectAsState()
    if (!show) return

    val strings = LocalAppStrings.current
    val currentMode by state.locationFilterMode.collectAsState()
    val activeSet by state.locationFilterSet.collectAsState()
    val availableLocations by state.availableLocations.collectAsState()
    val appLanguage by state.appLanguage.collectAsState()

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
                text = strings.locFilterTitle,
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
                    text = strings.locFilterDesc,
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
                                Text(strings.locExcludeRadio, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(strings.locExcludeDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(strings.locWhitelistRadio, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(strings.locWhitelistDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(strings.locDisabledRadio, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(strings.locDisabledDesc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Список стран
                if (currentMode != LocationFilterMode.DISABLED) {
                    Text(
                        text = "${strings.locationsFilter} (${availableLocations.size}):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availableLocations, key = { it.key }) { entry ->
                            val tag = entry.key
                            val count = entry.value
                            val isChecked = activeSet.contains(tag)
                            val displayName = CountryNames.getDisplayName(tag, appLanguage)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { state.toggleLocationFilter(tag) }
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { state.toggleLocationFilter(tag) }
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "$displayName ($count)",
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Кнопка сброса выбора
                    if (activeSet.isNotEmpty()) {
                        TextButton(
                            onClick = { state.clearAllLocations() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(strings.locResetAll, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { state.dismissLocationFilterDialog() },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(strings.done)
            }
        }
    )
}
