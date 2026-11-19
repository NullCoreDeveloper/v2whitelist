package com.kiktor.v2whitelist.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kiktor.v2whitelist.desktop.engine.AppSplitMode
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.util.DesktopAppEntry
import com.kiktor.v2whitelist.desktop.util.DesktopInstalledAppsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppSplitTunnelingDialog(state: DesktopAppState) {
    val show by state.showAppSplitDialog.collectAsState()
    if (!show) return

    val strings = LocalAppStrings.current
    val splitMode by state.appSplitTunnelingMode.collectAsState()
    val directApps by state.appSplitDirectApps.collectAsState()
    val proxyApps by state.appSplitProxyApps.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Запущенные, 1 = Установленные, 2 = Выбранные
    var searchQuery by remember { mutableStateOf("") }
    var manualInput by remember { mutableStateOf("") }
    var manualTarget by remember { mutableStateOf("DIRECT") } // DIRECT | PROXY
    var runningApps by remember { mutableStateOf<List<DesktopAppEntry>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<DesktopAppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(show) {
        isLoading = true
        withContext(Dispatchers.IO) {
            runningApps = DesktopInstalledAppsProvider.getRunningApps()
            installedApps = DesktopInstalledAppsProvider.getInstalledApps()
        }
        isLoading = false
    }

    val directSet = remember(directApps) { directApps.map { it.lowercase() }.toSet() }
    val proxySet = remember(proxyApps) { proxyApps.map { it.lowercase() }.toSet() }

    val configuredEntries = remember(directApps, proxyApps, runningApps, installedApps) {
        val known = (runningApps + installedApps).associateBy { it.id.lowercase() }
        val allConfiguredIds = (directApps + proxyApps).distinctBy { it.lowercase() }
        allConfiguredIds.map { id ->
            known[id.lowercase()] ?: DesktopAppEntry(
                id = id,
                displayName = id,
                processName = id
            )
        }
    }

    val visibleApps = remember(selectedTab, searchQuery, runningApps, installedApps, configuredEntries) {
        val source = when (selectedTab) {
            0 -> runningApps
            1 -> installedApps
            else -> configuredEntries
        }
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) source
        else source.filter {
            it.displayName.lowercase().contains(q) ||
                it.processName.lowercase().contains(q) ||
                it.executablePath.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = { state.dismissAppSplitDialog() },
        icon = {
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = strings.appSplitDialogTitle,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Выбор режима по умолчанию
                Text(
                    text = strings.appSplitDefaultPolicyLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = splitMode == AppSplitMode.BYPASS,
                        onClick = { state.setAppSplitMode(AppSplitMode.BYPASS) },
                        label = { Text(strings.appSplitModeBypassLabel, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = splitMode == AppSplitMode.ONLY_PROXY,
                        onClick = { state.setAppSplitMode(AppSplitMode.ONLY_PROXY) },
                        label = { Text(strings.appSplitModeOnlyProxyLabel, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (splitMode == AppSplitMode.BYPASS) {
                        strings.appSplitModeBypassHint
                    } else {
                        strings.appSplitModeOnlyProxyHint
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Поле ручного добавления процесса / пути
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        placeholder = { Text(strings.appSplitManualPlaceholder, fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = manualTarget == "DIRECT",
                        onClick = { manualTarget = if (manualTarget == "DIRECT") "PROXY" else "DIRECT" },
                        label = { Text(if (manualTarget == "DIRECT") strings.appSplitBypassTag else strings.appSplitVpnTag, fontSize = 11.sp) }
                    )
                    IconButton(
                        onClick = {
                            val trimmed = manualInput.trim()
                            if (trimmed.isNotEmpty()) {
                                state.setAppSplitRoute(trimmed, manualTarget)
                                manualInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                // Вкладки + поиск
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("${strings.appSplitTabRunning} (${runningApps.size})", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("${strings.appSplitTabInstalled} (${installedApps.size})", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        label = { Text("${strings.appSplitTabSelected} (${configuredEntries.size})", fontSize = 11.sp) }
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.appSplitSearchPlaceholder, fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else if (visibleApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = strings.appSplitEmptyList,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(min = 160.dp, max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(visibleApps, key = { "${it.id.lowercase()}_${it.executablePath}" }) { app ->
                            val key = app.id.lowercase()
                            val isDirect = key in directSet
                            val isProxy = key in proxySet

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (app.executablePath.isNotBlank() && app.executablePath != app.processName) {
                                                "${app.processName} • ${app.executablePath}"
                                            } else {
                                                app.processName
                                            },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    FilterChip(
                                        selected = isDirect,
                                        onClick = { state.setAppSplitRoute(app.id, if (isDirect) "NONE" else "DIRECT") },
                                        label = { Text(strings.appSplitBypassTag, fontSize = 10.sp) }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    FilterChip(
                                        selected = isProxy,
                                        onClick = { state.setAppSplitRoute(app.id, if (isProxy) "NONE" else "PROXY") },
                                        label = { Text(strings.appSplitVpnTag, fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { state.dismissAppSplitDialog() },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(strings.done)
            }
        }
    )
}
