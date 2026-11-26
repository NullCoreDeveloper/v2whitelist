package com.kiktor.v2whitelist.handler

import android.content.Context
import com.kiktor.v2whitelist.R
import com.kiktor.v2whitelist.core.domain.inspection.ServerFingerprint
import com.kiktor.v2whitelist.core.domain.inspection.ServerInspectionCachePolicy
import com.kiktor.v2whitelist.core.domain.inspection.ServerListEntry
import com.kiktor.v2whitelist.core.model.EConfigType
import com.kiktor.v2whitelist.core.model.PersonalServersConfig
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import com.kiktor.v2whitelist.core.subscription.tag.ServerTagResolver
import com.kiktor.v2whitelist.viewmodel.SubscriptionFilterOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Горячий in-memory кэш серверов и результатов их инспекции.
 * Переживает переоткрытие ServerListActivity и исключает повторное чтение тысяч JSON из MMKV.
 */
object ServerListStore {

    data class CachedBaseServer(
        val guid: String,
        val fingerprint: String,
        val profile: ProfileItem,
        val remarksLower: String,
        val subscriptionId: String,
        val subscriptionName: String,
        val isPersonal: Boolean,
        val fallbackPingMs: Long,
        val fallbackFlagEmoji: String
    )

    data class StoreSnapshot(
        val entries: List<ServerListEntry>,
        val flagsByGuid: Map<String, String>,
        val availableSubscriptions: List<SubscriptionFilterOption>,
        val availableProtocols: List<EConfigType>,
        val removedDuplicatesCount: Int
    )

    private val loadMutex = Mutex()
    private val baseListRef = AtomicReference<List<CachedBaseServer>>(emptyList())
    private val baseByGuid = ConcurrentHashMap<String, CachedBaseServer>()
    private val lastGuidsRef = AtomicReference<List<String>>(emptyList())
    private val inspections = ConcurrentHashMap<String, ServerInspectionInfo>()
    private val vipSetRef = AtomicReference<Set<String>>(emptySet())
    private val availableSubsRef = AtomicReference<List<SubscriptionFilterOption>>(emptyList())
    private val availableProtocolsRef = AtomicReference<List<EConfigType>>(emptyList())
    private val flagsByGuidRef = AtomicReference<Map<String, String>>(emptyMap())
    private val removedDupesCount = AtomicInteger(0)

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun ensureLoaded(context: Context) {
        if (_isLoaded.value) return
        reloadBaseIfNeeded(context.applicationContext, force = true)
    }

    suspend fun reloadBaseIfNeeded(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        loadMutex.withLock {
            val currentGuids = MmkvManager.decodeServerList()
            val alreadyLoaded = _isLoaded.value
            val prevGuids = lastGuidsRef.get()
            if (alreadyLoaded && !force && currentGuids == prevGuids) {
                refreshVipInternal()
                removedDupesCount.set(MmkvManager.decodeLastRemovedDuplicatesCount())
                return@withLock
            }

            val subNameMap = buildSubscriptionNameMap()
            val manualLabel = appContext.getString(R.string.server_list_manual_sub)
            val personalLabel = appContext.getString(R.string.title_personal_servers)

            val newBase = ArrayList<CachedBaseServer>(currentGuids.size)
            val newByGuid = HashMap<String, CachedBaseServer>(currentGuids.size)
            val newFlags = HashMap<String, String>(currentGuids.size)
            val subCountMap = LinkedHashMap<String, Pair<String, Int>>()
            val protocolSet = LinkedHashSet<EConfigType>()

            for (guid in currentGuids) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val isPersonal = PersonalServersConfig.isPersonalServer(profile.subscriptionId)
                val normSubId = when {
                    isPersonal -> "personal"
                    profile.subscriptionId.isBlank() -> "manual"
                    else -> profile.subscriptionId
                }
                val subName = when {
                    isPersonal -> personalLabel
                    profile.subscriptionId.isBlank() -> manualLabel
                    else -> subNameMap[profile.subscriptionId]
                        ?: subNameMap[profile.subscriptionId.removePrefix("custom_sub_")]
                        ?: profile.subscriptionId
                }

                val prevCount = subCountMap[normSubId]?.second ?: 0
                subCountMap[normSubId] = subName to (prevCount + 1)
                protocolSet.add(profile.configType)

                val fallbackPing = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                val fallbackFlag = ServerTagResolver.extractFirstFlagEmoji(profile.remarks).orEmpty()
                val fingerprint = ServerFingerprint.compute(profile)
                val item = CachedBaseServer(
                    guid = guid,
                    fingerprint = fingerprint,
                    profile = profile,
                    remarksLower = profile.remarks.lowercase(),
                    subscriptionId = normSubId,
                    subscriptionName = subName,
                    isPersonal = isPersonal,
                    fallbackPingMs = fallbackPing,
                    fallbackFlagEmoji = fallbackFlag
                )
                newBase.add(item)
                newByGuid[guid] = item
                if (fallbackFlag.isNotEmpty()) {
                    newFlags[guid] = fallbackFlag
                }
            }

            val nowMs = System.currentTimeMillis()
            if (!alreadyLoaded) {
                val persisted = MmkvManager.decodeAllServerInspectionsPruned(nowMs)
                inspections.clear()
                for ((key, info) in persisted) {
                    if (key.startsWith("fp_") && !ServerInspectionCachePolicy.isExpired(info.testedAtMs, nowMs)) {
                        inspections[key] = info
                    }
                }
                for (item in newBase) {
                    if (inspections[item.fingerprint] == null) {
                        val legacy = persisted[item.guid]
                        if (legacy != null && !ServerInspectionCachePolicy.isExpired(legacy.testedAtMs, nowMs)) {
                            inspections[item.fingerprint] = legacy
                        }
                    }
                }
            } else {
                // Не очищаем инспекции отсутствующих GUID: кэш привязан к fingerprint и переживает перегенерацию UUID.
                val iter = inspections.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (ServerInspectionCachePolicy.isExpired(entry.value.testedAtMs, nowMs)) {
                        iter.remove()
                    }
                }
            }

            baseListRef.set(newBase)
            baseByGuid.clear()
            baseByGuid.putAll(newByGuid)
            lastGuidsRef.set(currentGuids)
            flagsByGuidRef.set(newFlags)
            availableSubsRef.set(
                subCountMap.entries
                    .map { (id, pair) -> SubscriptionFilterOption(id = id, name = pair.first, count = pair.second) }
                    .sortedByDescending { it.count }
            )
            availableProtocolsRef.set(protocolSet.sortedBy { it.name })
            vipSetRef.set(MmkvManager.getVipCache().toSet())
            removedDupesCount.set(MmkvManager.decodeLastRemovedDuplicatesCount())

            _isLoaded.value = true
            _revision.update { it + 1 }
        }
    }

    fun getCachedProfile(guid: String): ProfileItem? = baseByGuid[guid]?.profile

    fun getCachedFingerprint(guid: String): String? = baseByGuid[guid]?.fingerprint

    fun getCachedInspection(guid: String): ServerInspectionInfo? {
        val cacheKey = baseByGuid[guid]?.fingerprint ?: guid
        return inspections[cacheKey]
            ?.takeUnless { ServerInspectionCachePolicy.isExpired(it.testedAtMs, System.currentTimeMillis()) }
    }

    fun getCachedPingMs(guid: String): Long = baseByGuid[guid]?.fallbackPingMs ?: 0L

    fun updatePingInMemory(guid: String, delayMs: Long) {
        if (delayMs <= 0L) return
        val prev = baseByGuid[guid] ?: return
        if (prev.fallbackPingMs != delayMs) {
            baseByGuid[guid] = prev.copy(fallbackPingMs = delayMs)
        }
        val cacheKey = prev.fingerprint
        inspections[cacheKey]?.let { existing ->
            inspections[cacheKey] = existing.copy(
                alive = true,
                delayMs = delayMs,
                testedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun putInspectionInMemory(guid: String, info: ServerInspectionInfo) {
        val base = baseByGuid[guid]
        val cacheKey = base?.fingerprint ?: guid
        inspections[cacheKey] = info
        base?.let { prev ->
            val newPing = if (info.alive) info.delayMs else -1L
            if (prev.fallbackPingMs != newPing) {
                val updated = prev.copy(fallbackPingMs = newPing)
                baseByGuid[guid] = updated
            }
        }
    }

    fun updateProfileInMemory(guid: String, profile: ProfileItem) {
        val prev = baseByGuid[guid] ?: return
        val newFlag = ServerTagResolver.extractFirstFlagEmoji(profile.remarks).orEmpty()
        val newFp = ServerFingerprint.compute(profile)
        if (newFp != prev.fingerprint) {
            inspections.remove(prev.fingerprint)?.let { oldInsp ->
                inspections.putIfAbsent(newFp, oldInsp)
            }
        }
        val updated = prev.copy(
            fingerprint = newFp,
            profile = profile,
            remarksLower = profile.remarks.lowercase(),
            fallbackFlagEmoji = newFlag
        )
        baseByGuid[guid] = updated
    }

    fun clearInspectionsInMemory() {
        inspections.clear()
        bumpRevision()
    }

    fun clearInspectionsAndPingsInMemory() {
        inspections.clear()
        val oldList = baseListRef.get()
        if (oldList.isNotEmpty()) {
            val cleared = oldList.map { item ->
                val updated = if (item.fallbackPingMs != 0L) item.copy(fallbackPingMs = 0L) else item
                baseByGuid[updated.guid] = updated
                updated
            }
            baseListRef.set(cleared)
        }
        bumpRevision()
    }

    fun clearInspectionsAndPingsForGuids(targetGuids: Set<String>) {
        if (targetGuids.isEmpty()) return
        for (g in targetGuids) {
            baseByGuid[g]?.let { prev ->
                inspections.remove(prev.fingerprint)
                if (prev.fallbackPingMs != 0L) {
                    baseByGuid[g] = prev.copy(fallbackPingMs = 0L)
                }
            }
            inspections.remove(g)
        }
        val oldList = baseListRef.get()
        if (oldList.isNotEmpty()) {
            val cleared = oldList.map { item ->
                if (item.guid in targetGuids && item.fallbackPingMs != 0L) item.copy(fallbackPingMs = 0L) else item
            }
            baseListRef.set(cleared)
        }
        bumpRevision()
    }

    fun refreshVipFromMmkv() {
        refreshVipInternal()
        bumpRevision()
    }

    private fun refreshVipInternal() {
        vipSetRef.set(MmkvManager.getVipCache().toSet())
    }

    fun toggleVip(guid: String) {
        val current = MmkvManager.getVipCache().toMutableSet()
        if (guid in current) {
            MmkvManager.removeVipServer(guid)
            current.remove(guid)
        } else {
            MmkvManager.addVipServer(guid)
            current.add(guid)
        }
        vipSetRef.set(current)
        bumpRevision()
    }

    fun bumpRevision() {
        _revision.update { it + 1 }
    }

    fun snapshot(): StoreSnapshot {
        val baseList = baseListRef.get()
        val vipSet = vipSetRef.get()
        val nowMs = System.currentTimeMillis()
        val entries = ArrayList<ServerListEntry>(baseList.size)
        for (item in baseList) {
            val latest = baseByGuid[item.guid] ?: item
            val insp = inspections[latest.fingerprint]
                ?.takeUnless { ServerInspectionCachePolicy.isExpired(it.testedAtMs, nowMs) }
            entries.add(
                ServerListEntry(
                    guid = latest.guid,
                    profile = latest.profile,
                    subscriptionId = latest.subscriptionId,
                    subscriptionName = latest.subscriptionName,
                    isVip = latest.guid in vipSet,
                    isPersonal = latest.isPersonal,
                    fallbackPingMs = latest.fallbackPingMs,
                    inspection = insp,
                    remarksLower = latest.remarksLower
                )
            )
        }
        return StoreSnapshot(
            entries = entries,
            flagsByGuid = flagsByGuidRef.get(),
            availableSubscriptions = availableSubsRef.get(),
            availableProtocols = availableProtocolsRef.get(),
            removedDuplicatesCount = removedDupesCount.get()
        )
    }

    private fun buildSubscriptionNameMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (def in DefaultSubscriptions.PREPOPULATED_SUBS) {
            if (def.name.isNotBlank()) {
                map["custom_sub_${def.id}"] = def.name
                map[def.id] = def.name
            }
        }
        for (custom in SubscriptionHelper.loadCustomSubs()) {
            if (custom.name.isNotBlank()) {
                map["custom_sub_${custom.id}"] = custom.name
                map[custom.id] = custom.name
            }
        }
        for (sub in MmkvManager.decodeSubscriptions()) {
            if (sub.subscription.remarks.isNotBlank()) {
                map[sub.guid] = sub.subscription.remarks
            }
        }
        return map
    }
}
