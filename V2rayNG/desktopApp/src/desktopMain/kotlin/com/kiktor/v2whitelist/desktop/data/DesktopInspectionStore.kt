package com.kiktor.v2whitelist.desktop.data

import com.kiktor.v2whitelist.core.domain.inspection.ServerFingerprint
import com.kiktor.v2whitelist.core.domain.inspection.ServerInspectionCachePolicy
import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.inspection.ScanSessionMeta
import com.kiktor.v2whitelist.core.model.inspection.ServerInspectionInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class InspectionDiskPayload(
    val inspections: Map<String, ServerInspectionInfo> = emptyMap(),
    val lastMeta: ScanSessionMeta? = null,
    val lastRemovedDuplicates: Int = 0
)

/**
 * Персистентное хранилище результатов сканирования серверов для Desktop.
 * Хранит записи по стабильному отпечатку [ServerFingerprint] с TTL-политикой [ServerInspectionCachePolicy],
 * благодаря чему кэш переживает обновление подписок и перезапуск приложения.
 */
class DesktopInspectionStore(
    private val storageFile: File = File(DesktopStorageManager.baseDir, "inspections.json"),
    loadImmediately: Boolean = true
) {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fileLock = Any()
    private val cache = ConcurrentHashMap<String, ServerInspectionInfo>()
    private val lastMetaRef = AtomicReference<ScanSessionMeta?>(null)
    private val removedDupesRef = AtomicInteger(0)

    init {
        if (loadImmediately) {
            loadFromDisk()
        }
    }

    fun loadFromDisk(nowMs: Long = System.currentTimeMillis()) {
        synchronized(fileLock) {
            if (!storageFile.exists()) return
            try {
                val payload = json.decodeFromString<InspectionDiskPayload>(storageFile.readText())
                cache.clear()
                for ((key, info) in payload.inspections) {
                    if (!ServerInspectionCachePolicy.isExpired(info.testedAtMs, nowMs)) {
                        cache[key] = info
                    }
                }
                lastMetaRef.set(payload.lastMeta)
                removedDupesRef.set(payload.lastRemovedDuplicates.coerceAtLeast(0))
            } catch (_: Exception) {
                cache.clear()
            }
        }
    }

    fun get(profile: ProfileItem, nowMs: Long = System.currentTimeMillis()): ServerInspectionInfo? {
        return getByFingerprint(ServerFingerprint.compute(profile), nowMs)
    }

    fun getByFingerprint(fingerprint: String, nowMs: Long = System.currentTimeMillis()): ServerInspectionInfo? {
        val info = cache[fingerprint] ?: return null
        if (ServerInspectionCachePolicy.isExpired(info.testedAtMs, nowMs)) {
            cache.remove(fingerprint, info)
            return null
        }
        return info
    }

    fun put(fingerprint: String, info: ServerInspectionInfo) {
        if (fingerprint.isBlank()) return
        cache[fingerprint] = info
    }

    fun putForProfile(profile: ProfileItem, info: ServerInspectionInfo) {
        put(ServerFingerprint.compute(profile), info)
    }

    fun removeByFingerprints(fingerprints: Collection<String>) {
        for (fp in fingerprints) {
            if (fp.isNotBlank()) {
                cache.remove(fp)
            }
        }
    }

    fun clearAllInspections() {
        cache.clear()
    }

    fun getLastMeta(): ScanSessionMeta? = lastMetaRef.get()

    fun setLastMeta(meta: ScanSessionMeta?) {
        lastMetaRef.set(meta)
    }

    fun getLastRemovedDuplicates(): Int = removedDupesRef.get()

    fun setLastRemovedDuplicates(count: Int) {
        removedDupesRef.set(count.coerceAtLeast(0))
    }

    fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (ServerInspectionCachePolicy.isExpired(entry.value.testedAtMs, nowMs)) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    fun size(): Int = cache.size

    fun flushToDisk(nowMs: Long = System.currentTimeMillis()) {
        synchronized(fileLock) {
            pruneExpired(nowMs)
            val snapshotMap = LinkedHashMap<String, ServerInspectionInfo>(cache.size)
            for ((k, v) in cache) {
                snapshotMap[k] = v
            }
            val payload = InspectionDiskPayload(
                inspections = snapshotMap,
                lastMeta = lastMetaRef.get(),
                lastRemovedDuplicates = removedDupesRef.get()
            )
            val content = try {
                json.encodeToString(payload)
            } catch (_: Exception) {
                return
            }

            val parent = storageFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val tempFile = File(parent ?: File("."), "${storageFile.name}.tmp")
            try {
                tempFile.writeText(content)
                try {
                    Files.move(
                        tempFile.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    Files.move(
                        tempFile.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (_: Exception) {
                try {
                    storageFile.writeText(content)
                } catch (_: Exception) {}
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }
}
