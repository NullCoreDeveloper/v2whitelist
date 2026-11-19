package com.kiktor.v2whitelist.desktop.util

import com.kiktor.v2whitelist.desktop.engine.DesktopBinaryResolver
import java.io.File

data class DesktopAppEntry(
    val id: String,          // имя процесса или путь для правила маршрутизации
    val displayName: String, // человекочитаемое имя приложения
    val processName: String, // базовое имя бинарника (напр. chrome / chrome.exe)
    val executablePath: String = "",
    val isRunning: Boolean = false
)

/**
 * Поставщик списка запущенных процессов и установленных приложений для раздельного туннелирования на Desktop.
 */
object DesktopInstalledAppsProvider {

    private val ignoredProcessNames = setOf(
        "systemd", "kthreadd", "kworker", "migration", "rcu_sched", "dbus-daemon",
        "xray", "xray.exe", "xray-core", "v2w-tun", "v2w-tun.exe", "v2w-service.exe",
        "tun2socks", "tun2socks.exe", "svchost.exe", "conhost.exe", "csrss.exe",
        "wininit.exe", "services.exe", "lsass.exe", "smss.exe", "idle", "system"
    )

    fun getRunningApps(): List<DesktopAppEntry> {
        val byKey = linkedMapOf<String, DesktopAppEntry>()
        try {
            ProcessHandle.allProcesses().forEach { ph ->
                val cmd = ph.info().command().orElse(null)?.trim().orEmpty()
                if (cmd.isBlank()) return@forEach
                val norm = cmd.replace('\\', '/')
                val baseName = norm.substringAfterLast('/')
                if (baseName.isBlank() || baseName.lowercase() in ignoredProcessNames) return@forEach
                val key = baseName.lowercase()
                if (!byKey.containsKey(key)) {
                    val pretty = baseName.removeSuffix(".exe").replaceFirstChar { it.uppercaseChar() }
                    byKey[key] = DesktopAppEntry(
                        id = baseName,
                        displayName = pretty,
                        processName = baseName,
                        executablePath = cmd,
                        isRunning = true
                    )
                }
            }
        } catch (_: Exception) {}
        return byKey.values.sortedBy { it.displayName.lowercase() }
    }

    fun getInstalledApps(): List<DesktopAppEntry> {
        return when {
            DesktopBinaryResolver.isWindows -> scanWindowsInstalledApps()
            DesktopBinaryResolver.isMac -> scanMacInstalledApps()
            else -> scanLinuxInstalledApps()
        }
    }

    private fun scanLinuxInstalledApps(): List<DesktopAppEntry> {
        val userHome = System.getProperty("user.home").orEmpty()
        val appDirs = listOf(
            File("/usr/share/applications"),
            File("/usr/local/share/applications"),
            File(userHome, ".local/share/applications"),
            File("/var/lib/flatpak/exports/share/applications"),
            File(userHome, ".local/share/flatpak/exports/share/applications")
        )
        val result = linkedMapOf<String, DesktopAppEntry>()
        for (dir in appDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { _, name -> name.endsWith(".desktop") } ?: continue
            for (file in files) {
                runCatching {
                    var inEntry = false
                    var name = ""
                    var execLine = ""
                    var noDisplay = false
                    var type = ""
                    for (raw in file.readLines()) {
                        val line = raw.trim()
                        if (line.startsWith("[")) {
                            if (inEntry) break
                            inEntry = line == "[Desktop Entry]"
                            continue
                        }
                        if (!inEntry) continue
                        when {
                            line.startsWith("Name=") && name.isEmpty() -> name = line.removePrefix("Name=").trim()
                            line.startsWith("Exec=") -> execLine = line.removePrefix("Exec=").trim()
                            line.startsWith("NoDisplay=") -> noDisplay = line.removePrefix("NoDisplay=").trim().equals("true", ignoreCase = true)
                            line.startsWith("Type=") -> type = line.removePrefix("Type=").trim()
                        }
                    }
                    if (noDisplay || (type.isNotBlank() && type != "Application") || execLine.isBlank()) return@runCatching
                    val cmdToken = extractExecutableFromDesktopExec(execLine) ?: return@runCatching
                    val binName = cmdToken.substringAfterLast('/')
                    if (binName.isBlank() || binName.lowercase() in ignoredProcessNames) return@runCatching
                    // Для обёрток вроде env/sh/flatpak берём информативное имя бинарника или пропускаем пустышки
                    if (binName in setOf("env", "sh", "bash", "xdg-open")) return@runCatching
                    val key = binName.lowercase()
                    if (!result.containsKey(key)) {
                        result[key] = DesktopAppEntry(
                            id = binName,
                            displayName = name.ifBlank { binName },
                            processName = binName,
                            executablePath = cmdToken
                        )
                    }
                }
            }
        }
        return result.values.sortedBy { it.displayName.lowercase() }
    }

    internal fun extractExecutableFromDesktopExec(execLine: String): String? {
        val trimmed = execLine.trim()
        if (trimmed.isEmpty()) return null
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var quoteChar = '"'
        for (ch in trimmed) {
            when {
                inQuotes && ch == quoteChar -> inQuotes = false
                !inQuotes && (ch == '"' || ch == '\'') -> {
                    inQuotes = true
                    quoteChar = ch
                }
                !inQuotes && ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        tokens.add(cur.toString())
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())

        for (tok in tokens) {
            if (tok == "env" || tok.contains('=')) continue
            if (tok.startsWith("%")) continue
            return tok
        }
        return null
    }

    private fun scanWindowsInstalledApps(): List<DesktopAppEntry> {
        val result = linkedMapOf<String, DesktopAppEntry>()
        val roots = listOfNotNull(
            System.getenv("ProgramFiles")?.let { File(it) },
            System.getenv("ProgramFiles(x86)")?.let { File(it) },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Programs") }
        )
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            val vendorDirs = root.listFiles { f -> f.isDirectory } ?: continue
            for (vendor in vendorDirs) {
                runCatching {
                    vendor.walkTopDown().maxDepth(3)
                        .filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
                        .forEach { exe ->
                            val lower = exe.name.lowercase()
                            if (lower in ignoredProcessNames ||
                                lower.contains("unins") ||
                                lower.contains("update") ||
                                lower.contains("crash") ||
                                lower.contains("helper")
                            ) return@forEach
                            if (!result.containsKey(lower)) {
                                result[lower] = DesktopAppEntry(
                                    id = exe.name,
                                    displayName = exe.name.removeSuffix(".exe").removeSuffix(".EXE"),
                                    processName = exe.name,
                                    executablePath = exe.absolutePath
                                )
                            }
                        }
                }
            }
        }
        return result.values.sortedBy { it.displayName.lowercase() }
    }

    private fun scanMacInstalledApps(): List<DesktopAppEntry> {
        val userHome = System.getProperty("user.home").orEmpty()
        val dirs = listOf(File("/Applications"), File(userHome, "Applications"))
        val result = linkedMapOf<String, DesktopAppEntry>()
        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val bundles = dir.listFiles { f -> f.isDirectory && f.name.endsWith(".app") } ?: continue
            for (app in bundles) {
                val macOsDir = File(app, "Contents/MacOS")
                val exe = macOsDir.listFiles { f -> f.isFile && f.canExecute() }?.firstOrNull() ?: continue
                val key = exe.name.lowercase()
                if (!result.containsKey(key)) {
                    result[key] = DesktopAppEntry(
                        id = exe.name,
                        displayName = app.name.removeSuffix(".app"),
                        processName = exe.name,
                        executablePath = exe.absolutePath
                    )
                }
            }
        }
        return result.values.sortedBy { it.displayName.lowercase() }
    }
}
