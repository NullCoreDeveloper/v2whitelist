package com.kiktor.v2whitelist.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kiktor.v2whitelist.core.subscription.model.CustomSubData
import com.kiktor.v2whitelist.desktop.state.DesktopAppState
import com.kiktor.v2whitelist.desktop.ui.i18n.LocalAppStrings
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun SubscriptionsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val subscriptions by state.subscriptions.collectAsState()
    val servers by state.servers.collectAsState()
    val isUpdatingSub by state.isUpdatingSub.collectAsState()

    var editingSub by remember { mutableStateOf<CustomSubData?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }

    // Подсчет количества серверов для каждой подписки
    val serverCountBySubId = remember(servers) {
        val map = mutableMapOf<String, Int>()
        for ((_, p) in servers) {
            val sid = p.subscriptionId
            map[sid] = (map[sid] ?: 0) + 1
        }
        map
    }

    // Диалог редактирования или создания
    if (editingSub != null || isAddingNew) {
        val target = editingSub ?: CustomSubData()
        SubscriptionEditDialog(
            initialData = target,
            isNew = isAddingNew,
            onDismiss = {
                editingSub = null
                isAddingNew = false
            },
            onSave = { saved ->
                state.saveSubscription(saved)
                editingSub = null
                isAddingNew = false
            }
        )
    }

    val meshEnabled by state.meshGradientEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (meshEnabled) Color.Transparent else MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // Шапка: заголовок + кнопка добавления + кнопка обновления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = strings.subManageTitle,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = strings.subManageSubtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Добавить подписку
                Button(
                    onClick = { isAddingNew = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.subAddBtn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                // Обновить все подписки
                Button(
                    onClick = { state.updateAllActiveSubscriptions() },
                    enabled = !isUpdatingSub,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    )
                ) {
                    if (isUpdatingSub) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.subUpdatingBtn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.subUpdateAllBtn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Баннер сбойных подписок (как на Android)
        val failedSubs = remember(subscriptions) { subscriptions.filter { it.enabled && it.lastUpdateFailed } }
        if (failedSubs.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AccentRed.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = AccentRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${strings.subFailBannerTitle} (${failedSubs.size}):",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = AccentRed
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = failedSubs.joinToString(", ") { it.name.ifBlank { strings.unknown } },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(
                        onClick = { state.updateAllActiveSubscriptions() },
                        enabled = !isUpdatingSub
                    ) {
                        Text(strings.subRetryBtn, fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Список подписок
        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = strings.subEmptyText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(subscriptions, key = { it.id }) { sub ->
                    val serverCount = serverCountBySubId[sub.id] ?: serverCountBySubId[sub.name] ?: 0
                    SubscriptionCard(
                        sub = sub,
                        serverCount = serverCount,
                        onToggle = { state.toggleSubscriptionEnabled(sub.id) },
                        onEdit = { editingSub = sub },
                        onDelete = { state.deleteSubscription(sub.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: CustomSubData,
    serverCount: Int,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (sub.lastUpdateFailed) AccentRed.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Чекбокс включения/отключения подписки
            Switch(
                checked = sub.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(end = 14.dp)
            )

            // Основная информация о подписке
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = sub.name.ifBlank { strings.unknown },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (sub.lastUpdateFailed) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AccentRed.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = strings.error,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentRed,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Зеркала и URL
                Text(
                    text = sub.url.ifBlank { "—" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(8.dp))

                // Бейджи: количество зеркал, количество серверов, доля, категории
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val mirrorCount = if (sub.url.isBlank()) 0 else sub.url.split("|").filter { it.isNotBlank() }.size
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "${strings.subMirrorsCount}$mirrorCount",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "${strings.subServersCount}$serverCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (serverCount > 0) AccentMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        if (sub.sharePercent != null && sub.sharePercent!! > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = "${strings.subSharePercent}${sub.sharePercent}%",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // Режимы (категории): БС, ЧС, YouTube
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sub.targetScenarios.contains(CustomSubData.SCENARIO_WHITELIST)) {
                            CategoryBadge(label = strings.scenarioWhitelistTitle, color = AccentMint)
                        }
                        if (sub.targetScenarios.contains(CustomSubData.SCENARIO_BLACKLIST)) {
                            CategoryBadge(label = strings.scenarioBlacklistTitle, color = AccentAmber)
                        }
                        if (sub.targetScenarios.contains(CustomSubData.SCENARIO_YOUTUBE)) {
                            CategoryBadge(label = strings.scenarioYoutubeSubtitle, color = AccentRose)
                        }
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Иконка корзины справа
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = strings.delete,
                    tint = AccentRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SubscriptionEditDialog(
    initialData: CustomSubData,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomSubData) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(initialData.name) }
    var url by remember { mutableStateOf(initialData.url) }
    var filter by remember { mutableStateOf(initialData.filter) }
    var groupRegex by remember { mutableStateOf(initialData.groupRegex) }
    var sharePercentStr by remember { mutableStateOf(initialData.sharePercent?.toString() ?: "") }

    var isWhitelist by remember { mutableStateOf(initialData.targetScenarios.contains(CustomSubData.SCENARIO_WHITELIST)) }
    var isBlacklist by remember { mutableStateOf(initialData.targetScenarios.contains(CustomSubData.SCENARIO_BLACKLIST)) }
    var isYoutube by remember { mutableStateOf(initialData.targetScenarios.contains(CustomSubData.SCENARIO_YOUTUBE)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .width(520.dp)
                .fillMaxHeight(0.9f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (isNew) strings.subDialogAddTitle else strings.subDialogEditTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(14.dp))

                // Скроллируемая область полей ввода
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Название
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.subNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Ссылки на зеркала через |
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(strings.subUrlLabel) },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Фильтр Regex
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(strings.subFilterLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Regex для групп
                    OutlinedTextField(
                        value = groupRegex,
                        onValueChange = { groupRegex = it },
                        label = { Text(strings.subGroupRegexLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Процент доли серверов
                    OutlinedTextField(
                        value = sharePercentStr,
                        onValueChange = { sharePercentStr = it },
                        label = { Text(strings.subSharePercentLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    // Категории авто-режима (галочки)
                    Text(
                        text = strings.subScenariosLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isWhitelist, onCheckedChange = { isWhitelist = it })
                        Text(strings.subCatWhitelist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isBlacklist, onCheckedChange = { isBlacklist = it })
                        Text(strings.subCatBlacklist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isYoutube, onCheckedChange = { isYoutube = it })
                        Text(strings.subCatYoutube, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Кнопки отмена / сохранить (всегда зафиксированы внизу)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings.cancel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val newScenarios = mutableListOf<String>()
                            if (isWhitelist) newScenarios.add(CustomSubData.SCENARIO_WHITELIST)
                            if (isBlacklist) newScenarios.add(CustomSubData.SCENARIO_BLACKLIST)
                            if (isYoutube) newScenarios.add(CustomSubData.SCENARIO_YOUTUBE)

                            val percent = sharePercentStr.trim().toIntOrNull()

                            val result = initialData.copy(
                                name = name.trim(),
                                url = url.trim(),
                                filter = filter.trim(),
                                groupRegex = groupRegex.trim(),
                                sharePercent = percent,
                                targetScenarios = newScenarios
                            )
                            onSave(result)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
                    ) {
                        Text(strings.save, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
