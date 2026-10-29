package com.kiktor.v2whitelist.desktop.engine

import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger
import java.io.File

/**
 * Единый кроссплатформенный резолвер исполняемых файлов (xray, v2w-scan / v2w-core, tun2socks)
 * и геоданных (geosite.dat, geoip.dat) для Desktop платформы.
 *
 * Решает следующие проблемы окружения:
 * 1. Linux AppImage: временная точка монтирования /tmp/.mount_v2whitXXXXXX динамически меняет имя
 *    при каждом запуске приложения. Пути из временной точки монтирования не должны фиксироваться
 *    в персистентных настройках.
 * 2. Имена ядер: пользователи и сборки могут называть ядро сканера как v2w-scan, так и v2w-core.
 * 3. Windows дистрибутив: бинарники вложены в bin/ относительно корня установки или в app/resources/bin,
 *    а рабочий каталог (user.dir) при запуске из ярлыка Start Menu может указывать на System32.
 * 4. Альтернативные пути Linux: директории ~/.config/v2rayNG, ~/.config/v2ray, ~/.config/xray.
 * 5. Невалидные кастомные пути: если указанный в настройках путь больше не существует, резолвер
 *    безопасно откатывается к поиску встроенного ядра вместо падения.
 */
object DesktopBinaryResolver {

    private val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val isWindows: Boolean = osName.contains("windows")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = !isWindows && !isMac

    val xrayBinaryNames: List<String> = if (isWindows) {
        listOf("xray.exe", "xray-core.exe", "xray")
    } else {
        listOf("xray", "xray-core")
    }

    val v2wBinaryNames: List<String> = if (isWindows) {
        listOf(
            "v2w-scan.exe",
            "v2w-core.exe",
            "v2whitelist-scan.exe",
            "v2whitelist-core.exe",
            "v2w-scan",
            "v2w-core"
        )
    } else {
        listOf(
            "v2w-scan",
            "v2w-core",
            "v2whitelist-scan",
            "v2whitelist-core"
        )
    }

    val tun2socksBinaryNames: List<String> = if (isWindows) {
        listOf("tun2socks.exe", "tun2socks")
    } else {
        listOf("tun2socks")
    }

    /**
     * Проверяет, указывает ли путь на временную точку монтирования AppImage.
     */
    fun isTemporaryMountPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return path.contains("/.mount_") || path.contains("\\.mount_")
    }

    /**
     * Формирует список кандидатов директорий поиска исполняемых файлов.
     */
    fun getCandidateDirectories(): List<File> {
        val userHome = System.getProperty("user.home") ?: "."
        val dirs = LinkedHashSet<File>()

        fun addHierarchy(base: File?) {
            if (base == null || !base.exists()) return
            val target = if (base.isDirectory) base else base.parentFile ?: return
            dirs.add(File(target, "bin"))
            dirs.add(File(target, "app/resources/bin"))
            dirs.add(File(target, "lib/app/resources/bin"))
            dirs.add(File(target, "app/bin"))
            dirs.add(File(target, "lib/app/bin"))
            dirs.add(File(target, "resources/bin"))
            dirs.add(File(target, "app/resources"))
            dirs.add(File(target, "lib/app/resources"))
            dirs.add(File(target, "app"))
            dirs.add(File(target, "lib/app"))
            dirs.add(target)

            target.parentFile?.let { parent ->
                dirs.add(File(parent, "bin"))
                dirs.add(File(parent, "app/resources/bin"))
                dirs.add(File(parent, "lib/app/resources/bin"))
                dirs.add(File(parent, "app/bin"))
                dirs.add(File(parent, "lib/app/bin"))
                dirs.add(File(parent, "resources/bin"))
                dirs.add(File(parent, "app/resources"))
                dirs.add(File(parent, "lib/app/resources"))
                dirs.add(File(parent, "app"))
                dirs.add(File(parent, "lib/app"))
                dirs.add(parent)
            }
        }

        // 0. Нативный лаунчер jpackage (MSI инсталлятор Windows, DEB, AppImage, DMG)
        listOfNotNull(
            System.getProperty("jdk.jpackage.app-path"),
            System.getProperty("jpackage.app-path")
        ).forEach { appPath ->
            addHierarchy(File(appPath))
        }

        // 1. AppImage монтирование (Linux)
        System.getenv("APPDIR")?.let { appdir ->
            val f = File(appdir)
            if (f.exists()) {
                dirs.add(File(f, "bin"))
                dirs.add(File(f, "lib/app/resources/bin"))
                dirs.add(File(f, "lib/app/bin"))
                dirs.add(File(f, "usr/bin"))
                dirs.add(f)
            }
        }

        // 2. Системное свойство app.dir (передается нашим лаунчером)
        System.getProperty("app.dir")?.let { ad ->
            addHierarchy(File(ad))
        }

        // 3. compose.application.resources.dir
        System.getProperty("compose.application.resources.dir")?.let { resPath ->
            val resDir = File(resPath)
            if (resDir.exists()) {
                dirs.add(File(resDir, "bin"))
                dirs.add(resDir)
                resDir.parentFile?.let { addHierarchy(it) }
            }
        }

        // 4. Относительно java.home (каталог JRE встроенный в дистрибутив jpackage)
        System.getProperty("java.home")?.let { jh ->
            val jHome = File(jh)
            if (jHome.exists()) {
                // Windows jpackage: <root>/runtime -> root
                // Linux jpackage: <root>/lib/runtime -> lib -> root
                jHome.parentFile?.let { root ->
                    addHierarchy(root)
                    root.parentFile?.let { addHierarchy(it) }
                }
                // macOS bundle: <bundle>/Contents/runtime/Contents/Home -> <bundle>/Contents
                jHome.parentFile?.parentFile?.parentFile?.parentFile?.let { macContents ->
                    dirs.add(File(macContents, "Resources/bin"))
                    dirs.add(File(macContents, "MacOS"))
                }
            }
        }

        // 5. Относительно запущенного JAR
        runCatching {
            DesktopBinaryResolver::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { uri ->
                addHierarchy(File(uri))
            }
        }

        // 6. Пользовательские директории настроек и данных
        when {
            isWindows -> {
                System.getenv("LOCALAPPDATA")?.let { localAppData ->
                    dirs.add(File(localAppData, "v2whitelist/bin"))
                    dirs.add(File(localAppData, "v2whitelist/app/resources/bin"))
                    dirs.add(File(localAppData, "v2whitelist/app/bin"))
                    dirs.add(File(localAppData, "v2rayNG/bin"))
                    dirs.add(File(localAppData, "Programs/v2whitelist/bin"))
                    dirs.add(File(localAppData, "Programs/v2whitelist/app/resources/bin"))
                    dirs.add(File(localAppData, "Programs/v2whitelist/app/bin"))
                    dirs.add(File(localAppData, "Programs/v2whitelist"))
                    dirs.add(File(localAppData, "v2whitelist"))
                }
                System.getenv("APPDATA")?.let { appData ->
                    dirs.add(File(appData, "v2whitelist/bin"))
                    dirs.add(File(appData, "v2whitelist/app/resources/bin"))
                }
                dirs.add(File("C:\\Program Files\\v2whitelist\\bin"))
                dirs.add(File("C:\\Program Files\\v2whitelist\\app\\resources\\bin"))
                dirs.add(File("C:\\Program Files\\v2whitelist\\app\\bin"))
                dirs.add(File("C:\\Program Files\\v2whitelist\\resources\\bin"))
                dirs.add(File("C:\\Program Files\\v2whitelist"))
                dirs.add(File("C:\\Program Files (x86)\\v2whitelist\\bin"))
                dirs.add(File("C:\\Program Files (x86)\\v2whitelist\\app\\resources\\bin"))
                dirs.add(File("C:\\Program Files (x86)\\v2whitelist"))
            }
            isMac -> {
                dirs.add(File(userHome, "Library/Application Support/v2whitelist/bin"))
                dirs.add(File("/usr/local/bin"))
                dirs.add(File("/opt/homebrew/bin"))
            }
            else -> {
                // Linux: стандартные и альтернативные каталоги
                dirs.add(File(userHome, ".config/v2whitelist/bin"))
                dirs.add(File(userHome, ".config/v2rayNG/bin"))
                dirs.add(File(userHome, ".config/v2rayng/bin"))
                dirs.add(File(userHome, ".config/xray/bin"))
                dirs.add(File(userHome, ".config/v2ray/bin"))
                dirs.add(File(userHome, ".local/share/v2whitelist/bin"))
                dirs.add(File(userHome, ".local/share/v2rayNG/bin"))
                dirs.add(File(userHome, ".local/bin"))
                dirs.add(File("/opt/v2whitelist/bin"))
                dirs.add(File("/opt/v2whitelist/lib/app/resources/bin"))
                dirs.add(File("/opt/v2rayng/bin"))
                dirs.add(File("/opt/xray"))
                dirs.add(File("/usr/local/bin"))
                dirs.add(File("/usr/bin"))
            }
        }

        // 7. Рабочая директория и пути проекта для локального запуска и тестов
        dirs.add(File("bin"))
        dirs.add(File("../bin"))
        dirs.add(File("../../bin"))
        dirs.add(File("V2rayNG/desktopApp/bin"))
        dirs.add(File("package-resources/bin"))
        dirs.add(File("../package-resources/bin"))
        dirs.add(File("v2w-core"))
        dirs.add(File("../v2w-core"))
        dirs.add(File("../../v2w-core"))
        dirs.add(File("."))

        return dirs.filter { it.exists() }
    }

    /**
     * Поиск бинарного файла по списку допустимых имен.
     */
    fun findBinary(names: List<String>, customPath: String? = null): File? {
        if (!customPath.isNullOrBlank()) {
            val cf = File(customPath)
            return if (isExecutable(cf)) cf.canonicalFile else null
        }

        val dirs = getCandidateDirectories()
        for (dir in dirs) {
            for (name in names) {
                val candidate = File(dir, name)
                if (isExecutable(candidate)) {
                    return candidate.canonicalFile
                }
            }
        }

        // Поиск в переменной окружения PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dirStr in pathEnv.split(File.pathSeparator)) {
            if (dirStr.isBlank()) continue
            val dir = File(dirStr)
            if (dir.exists()) {
                for (name in names) {
                    val candidate = File(dir, name)
                    if (isExecutable(candidate)) {
                        return candidate.canonicalFile
                    }
                }
            }
        }

        return null
    }

    /**
     * Надежная кроссплатформенная проверка исполняемости файла.
     * На Windows проверяет расширение .exe, на Unix гарантирует выставление бита +x.
     */
    fun isExecutable(file: File): Boolean {
        if (!file.exists() || file.isDirectory) return false
        if (isWindows) {
            val lower = file.name.lowercase()
            return lower.endsWith(".exe") || file.canExecute()
        }
        if (!file.canExecute()) {
            try {
                file.setExecutable(true, false)
            } catch (_: Exception) {}
        }
        return file.canExecute()
    }

    /**
     * Поиск директории с базами геоданных (geosite.dat и geoip.dat).
     */
    fun findAssetDirectory(customPath: String? = null, xrayBinary: File? = null): File? {
        if (!customPath.isNullOrBlank()) {
            val cf = File(customPath)
            return if (cf.exists() && hasGeoData(cf)) cf.canonicalFile else null
        }

        val userHome = System.getProperty("user.home") ?: "."
        val dirs = LinkedHashSet<File>()

        fun addAssetHierarchy(base: File?) {
            if (base == null || !base.exists()) return
            val target = if (base.isDirectory) base else base.parentFile ?: return
            dirs.add(File(target, "assets"))
            dirs.add(File(target, "app/resources/assets"))
            dirs.add(File(target, "lib/app/resources/assets"))
            dirs.add(File(target, "resources/assets"))
            dirs.add(File(target, "app/resources"))
            dirs.add(File(target, "lib/app/resources"))
            dirs.add(File(target, "bin"))
            dirs.add(target)

            target.parentFile?.let { parent ->
                dirs.add(File(parent, "assets"))
                dirs.add(File(parent, "app/resources/assets"))
                dirs.add(File(parent, "lib/app/resources/assets"))
                dirs.add(File(parent, "resources/assets"))
                dirs.add(File(parent, "app/resources"))
                dirs.add(File(parent, "lib/app/resources"))
                dirs.add(File(parent, "bin"))
                dirs.add(parent)
            }
        }

        // Нативный лаунчер jpackage
        listOfNotNull(
            System.getProperty("jdk.jpackage.app-path"),
            System.getProperty("jpackage.app-path")
        ).forEach { appPath ->
            addAssetHierarchy(File(appPath))
        }

        // Каталог рядом с найденным бинарником Xray
        xrayBinary?.parentFile?.let { xrayDir ->
            addAssetHierarchy(xrayDir)
        }

        // AppImage (Linux)
        System.getenv("APPDIR")?.let { appdir ->
            val f = File(appdir)
            if (f.exists()) {
                dirs.add(File(f, "lib/app/resources/assets"))
                dirs.add(File(f, "lib/app/resources"))
                dirs.add(File(f, "assets"))
                dirs.add(File(f, "bin"))
                dirs.add(f)
            }
        }

        // compose.application.resources.dir
        System.getProperty("compose.application.resources.dir")?.let { resPath ->
            val resDir = File(resPath)
            if (resDir.exists()) {
                dirs.add(File(resDir, "assets"))
                dirs.add(resDir)
                resDir.parentFile?.let { addAssetHierarchy(it) }
            }
        }

        // java.home
        System.getProperty("java.home")?.let { jh ->
            val jHome = File(jh)
            jHome.parentFile?.let { root ->
                addAssetHierarchy(root)
                root.parentFile?.let { addAssetHierarchy(it) }
            }
        }

        // Запущенный JAR
        runCatching {
            DesktopBinaryResolver::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { uri ->
                addAssetHierarchy(File(uri))
            }
        }

        // Системные директории профиля пользователя
        when {
            isWindows -> {
                System.getenv("LOCALAPPDATA")?.let { localAppData ->
                    dirs.add(File(localAppData, "v2whitelist/assets"))
                    dirs.add(File(localAppData, "v2whitelist/app/resources/assets"))
                    dirs.add(File(localAppData, "Programs/v2whitelist/assets"))
                    dirs.add(File(localAppData, "Programs/v2whitelist/app/resources/assets"))
                }
                dirs.add(File("C:\\Program Files\\v2whitelist\\assets"))
                dirs.add(File("C:\\Program Files\\v2whitelist\\app\\resources\\assets"))
                dirs.add(File("C:\\Program Files (x86)\\v2whitelist\\assets"))
                dirs.add(File("C:\\Program Files (x86)\\v2whitelist\\app\\resources\\assets"))
            }
            isMac -> {
                dirs.add(File(userHome, "Library/Application Support/v2whitelist/assets"))
            }
            else -> {
                dirs.add(File(userHome, ".config/v2whitelist/assets"))
                dirs.add(File(userHome, ".config/v2rayNG/assets"))
                dirs.add(File(userHome, ".config/xray/assets"))
                dirs.add(File(userHome, ".local/share/v2whitelist/assets"))
                dirs.add(File("/usr/share/xray"))
                dirs.add(File("/usr/share/v2ray"))
                dirs.add(File("/usr/local/share/xray"))
                dirs.add(File("/usr/local/share/v2ray"))
                dirs.add(File("/opt/v2whitelist/assets"))
                dirs.add(File("/opt/xray"))
            }
        }

        // Локальные папки
        dirs.add(File("assets"))
        dirs.add(File("bin"))
        dirs.add(File("package-resources/assets"))
        dirs.add(File("../package-resources/assets"))
        dirs.add(File("V2rayNG/desktopApp/assets"))
        dirs.add(File("V2rayNG/app/src/main/assets"))
        dirs.add(File("."))

        for (d in dirs) {
            if (d.exists() && hasGeoData(d)) {
                return d.canonicalFile
            }
        }

        return null
    }

    private fun hasGeoData(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "geosite.dat").exists() || File(dir, "geoip.dat").exists()
    }
}
