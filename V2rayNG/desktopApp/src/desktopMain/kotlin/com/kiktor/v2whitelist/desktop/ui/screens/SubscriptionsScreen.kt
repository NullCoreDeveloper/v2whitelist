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
import com.kiktor.v2whitelist.desktop.ui.theme.*

@Composable
fun SubscriptionsScreen(
    state: DesktopAppState,
    modifier: Modifier = Modifier
) {
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
                    text = "Управление подписками",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Единая система подписок, зеркал и категорий маршрутизации",
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
                    Text("Добавить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("Обновление…", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Обновить все", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                        contentDescription = "Предупреждение",
                        tint = AccentRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Не удалось обновить подписки (${failedSubs.size}):",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = AccentRed
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = failedSubs.joinToString(", ") { it.name.ifBlank { "Без названия" } },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(
                        onClick = { state.updateAllActiveSubscriptions() },
                        enabled = !isUpdatingSub
                    ) {
                        Text("Повторить", fontSize = 12.sp, color = AccentRed, fontWeight = FontWeight.Bold)
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
                    text = "Нет добавленных подписок. Нажмите «Добавить», чтобы подключить источник.",
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
                    val mirrorCount = sub.url.split("|").filter { it.isNotBlank() }.size

                    SubscriptionCard(
                        sub = sub,
                        serverCount = serverCount,
                        mirrorCount = mirrorCount,
                        onToggleEnabled = { state.toggleSubscriptionEnabled(sub.id) },
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
    mirrorCount: Int,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isFailed = sub.enabled && sub.lastUpdateFailed

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        border = BorderStroke(
            if (isFailed) 1.5.dp else 1.dp,
            when {
                isFailed -> AccentRed
                sub.enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.outline
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Чекбокс включения
                Checkbox(
                    checked = sub.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sub.name.ifBlank { "Без названия" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isFailed) AccentRed else if (sub.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isFailed) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentRed.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "Ошибка обновления",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentRed,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Количество зеркал и серверов
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "Зеркал: $mirrorCount",
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
                                text = "Серверов: $serverCount",
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
                                    text = "Доля: ${sub.sharePercent}%",
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
                            CategoryBadge(label = "Белый список", color = AccentMint)
                        }
                        if (sub.targetScenarios.contains(CustomSubData.SCENARIO_BLACKLIST)) {
                            CategoryBadge(label = "Чёрный список", color = AccentAmber)
                        }
                        if (sub.targetScenarios.contains(CustomSubData.SCENARIO_YOUTUBE)) {
                            CategoryBadge(label = "YouTube / Музыка", color = AccentRose)
                        }
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Иконка корзины справа (удалять можно и свои, и дефолтные)
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Удалить подписку",
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

/**
 * Диалог редактирования / создания подписки (в точности поля из Android ПроБел):
 * 1. Название подписки
 * 2. Ссылки на подписку (через | как разделитель)
 * 3. Фильтр Regex (убирать сервера еще до их появления из списка)
 * 4. Regex для групп (сортировка по группам)
 * 5. Процент доли серверов
 * 6. Категории авто-режима (чекбоксы: Белые списки, Черные списки, YouTube и музыка)
 */
@Composable
private fun SubscriptionEditDialog(
    initialData: CustomSubData,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomSubData) -> Unit
) {
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
                    text = if (isNew) "Добавить подписку" else "Редактирование подписки",
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
                        label = { Text("Название подписки") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Ссылки на зеркала через |
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Ссылки на подписку (зеркала через | )") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Фильтр Regex
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text("Фильтр Regex (исключать узлы до появления)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Regex для групп
                    OutlinedTextField(
                        value = groupRegex,
                        onValueChange = { groupRegex = it },
                        label = { Text("Regex для групп") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    // Процент доли серверов
                    OutlinedTextField(
                        value = sharePercentStr,
                        onValueChange = { sharePercentStr = it },
                        label = { Text("Процент доли серверов (%)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    // Категории авто-режима (галочки)
                    Text(
                        text = "Категории авто-режима:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isWhitelist, onCheckedChange = { isWhitelist = it })
                        Text("Белые списки", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isBlacklist, onCheckedChange = { isBlacklist = it })
                        Text("Чёрные списки", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isYoutube, onCheckedChange = { isYoutube = it })
                        Text("YouTube и музыка", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
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
                        Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Сохранить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
