plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

fun getVersionName(project: Project): String {
    val override = (project.findProperty("VERSION_NAME") as? String)?.removePrefix("v")
        ?: (project.findProperty("versionName") as? String)?.removePrefix("v")
        ?: (project.findProperty("packageVersion") as? String)?.removePrefix("v")
    if (!override.isNullOrEmpty()) return override
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "--match", "v*").start()
        val version = process.inputStream.bufferedReader().readText().trim().removePrefix("v")
        if (version.isEmpty()) "1.2.7" else version
    } catch (e: Exception) {
        "1.2.7"
    }
}

val desktopVersion = getVersionName(project)
val generatedSrcDir = layout.buildDirectory.dir("generated/sources/buildConfig/desktopMain")

val generateDesktopBuildConfig by tasks.registering {
    inputs.property("versionName", desktopVersion)
    outputs.dir(generatedSrcDir)
    doLast {
        val outputFile = generatedSrcDir.get().file("com/kiktor/v2whitelist/desktop/DesktopBuildConfig.kt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.kiktor.v2whitelist.desktop

            object DesktopBuildConfig {
                const val VERSION_NAME: String = "$desktopVersion"
                const val APP_NAME: String = "ПроБел desktop"
            }
            """.trimIndent()
        )
    }
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            kotlin.srcDir(generatedSrcDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation(project(":core:model"))
                implementation(project(":core:config"))
                implementation(project(":core:subscription"))
                implementation(project(":core:domain"))
                implementation(project(":core:repository"))

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.core)
                implementation("org.slf4j:slf4j-nop:2.0.16")
                implementation("dev.nucleusframework:composenativetray:2.1.7")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.kiktor.v2whitelist.desktop.MainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Djdk.gtk.version=3",
            "-Djdk.lang.Process.launchMechanism=vfork"
        )

        nativeDistributions {
            modules("java.base", "java.desktop", "java.logging", "java.net.http", "java.sql", "jdk.unsupported")
            val currentOs = org.gradle.internal.os.OperatingSystem.current()
            when {
                currentOs.isLinux -> targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
                )
                currentOs.isWindows -> targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
                )
                currentOs.isMacOsX -> targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Pkg
                )
                else -> targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage)
            }

            val rawVersion = desktopVersion
            val targetPackageVersion = Regex("""\d+(\.\d+)*""").find(rawVersion)?.value ?: "1.2.7"
            val macMajor = targetPackageVersion.substringBefore('.').toIntOrNull() ?: 0
            val effectiveVersion = if (currentOs.isMacOsX && macMajor < 1) "1.0.0" else targetPackageVersion

            packageName = "v2whitelist"
            packageVersion = effectiveVersion
            description = "ProBel - v2whitelist Desktop"
            vendor = "NullCore"
            copyright = "Copyright (c) 2026 NullCore"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("package-resources"))

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                debMaintainer = "developer@nullcore.dev"
                menuGroup = "Network"
                appCategory = "Network"
            }

            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menu = true
                menuGroup = "ProBel"
                upgradeUuid = "d7e98a35-43a1-4ee3-b0be-0e54b6b19812"
                dirChooser = true
                perUserInstall = false
            }

            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                bundleID = "com.kiktor.v2whitelist"
                dockName = "ПроБел"
                val macVersion = if (macMajor < 1) "1.0.0" else targetPackageVersion
                packageVersion = macVersion
                packageBuildVersion = macVersion
                dmgPackageVersion = macVersion
                pkgPackageVersion = macVersion
            }
        }
    }
}

tasks.withType<org.gradle.api.tasks.AbstractCopyTask>().configureEach {
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

tasks.matching {
    it.name.contains("Distributable", ignoreCase = true) ||
    it.name.contains("package", ignoreCase = true) ||
    it.name.contains("appResources", ignoreCase = true)
}.configureEach {
    if (this is org.gradle.api.tasks.AbstractCopyTask) {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    }
    doFirst {
        val resDir = file("package-resources")
        val binDir = project.file("bin")
        val assetsDir = project.file("assets")
        val destBin = File(resDir, "bin").apply { mkdirs() }
        val destAssets = File(resDir, "assets").apply { mkdirs() }
        if (binDir.exists()) {
            binDir.copyRecursively(destBin, overwrite = true)
            destBin.listFiles()?.forEach { it.setExecutable(true) }
        }
        if (assetsDir.exists()) {
            assetsDir.copyRecursively(destAssets, overwrite = true)
        }
        // Compose копирует в каталог ресурсов приложения (app/resources) только содержимое common/ и <os>-<arch>/,
        // иначе в MSI/EXE-установщике нет xray/tun2socks/v2w-scan.
        val commonDir = File(resDir, "common").apply { mkdirs() }
        if (binDir.exists()) binDir.copyRecursively(File(commonDir, "bin"), overwrite = true)
        if (assetsDir.exists()) assetsDir.copyRecursively(File(commonDir, "assets"), overwrite = true)
        val winDir = File(resDir, "windows-x64").apply { mkdirs() }
        if (binDir.exists()) binDir.copyRecursively(File(winDir, "bin"), overwrite = true)
    }
}

tasks.matching {
    it.name in listOf(
        "createDistributable",
        "createReleaseDistributable",
        "packageMsi",
        "packageReleaseMsi",
        "packageExe",
        "packageReleaseExe",
        "packageAppImage",
        "packageReleaseAppImage",
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS"
    )
}.configureEach {
    doLast {
        val os = org.gradle.internal.os.OperatingSystem.current()
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/v2whitelist").get().asFile
        val binDir = project.file("bin")
        val assetsDir = project.file("assets")

        if (appDir.exists() && os.isLinux) {
            val runtimeBinDir = file("$appDir/lib/runtime/bin")
            runtimeBinDir.mkdirs()
            val javaDest = file("$runtimeBinDir/java")
            if (!javaDest.exists()) {
                val currentJava = file("${System.getProperty("java.home")}/bin/java")
                if (currentJava.exists()) {
                    currentJava.copyTo(javaDest, overwrite = true)
                    javaDest.setExecutable(true)
                }
            }

            // Копирование bin и assets в корень приложения и в ресурсы
            val destBin = File(appDir, "bin").apply { mkdirs() }
            val destAssets = File(appDir, "assets").apply { mkdirs() }
            val resBin = File(appDir, "lib/app/resources/bin").apply { mkdirs() }
            val resAssets = File(appDir, "lib/app/resources/assets").apply { mkdirs() }
            if (binDir.exists()) {
                binDir.copyRecursively(destBin, overwrite = true)
                binDir.copyRecursively(resBin, overwrite = true)
                destBin.listFiles()?.forEach { it.setExecutable(true) }
                resBin.listFiles()?.forEach { it.setExecutable(true) }
            }
            if (assetsDir.exists()) {
                assetsDir.copyRecursively(destAssets, overwrite = true)
                assetsDir.copyRecursively(resAssets, overwrite = true)
            }

            val binFile = file("$appDir/bin/v2whitelist")
            val launcherScript = """
                #!/bin/sh
                APPDIR="${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")")"
                RUNTIME="${'$'}APPDIR/lib/runtime"
                if [ -x "${'$'}RUNTIME/bin/java" ]; then
                    JAVA_EXEC="${'$'}RUNTIME/bin/java"
                elif command -v java >/dev/null 2>&1; then
                    JAVA_EXEC="java"
                elif [ -x "/usr/lib/jvm/default/bin/java" ]; then
                    JAVA_EXEC="/usr/lib/jvm/default/bin/java"
                else
                    echo "Error: Java runtime not found" >&2
                    exit 1
                fi
                exec "${'$'}JAVA_EXEC" \
                    -Djdk.gtk.version=3 \
                    -Djdk.lang.Process.launchMechanism=vfork \
                    -Dapp.dir="${'$'}APPDIR" \
                    -Dskiko.library.path="${'$'}APPDIR/lib/app" \
                    -Dcompose.application.resources.dir="${'$'}APPDIR/lib/app/resources" \
                    -Dcompose.application.configure.swing.globals=true \
                    -Djpackage.app-version=$desktopVersion \
                    -cp "${'$'}APPDIR/lib/app/*" \
                    com.kiktor.v2whitelist.desktop.MainKt \
                    "${'$'}@"
            """.trimIndent()
            binFile.writeText(launcherScript)
            binFile.setExecutable(true)
        }

        if (os.isWindows && appDir.exists()) {
            val destBin = File(appDir, "bin").apply { mkdirs() }
            val destAssets = File(appDir, "assets").apply { mkdirs() }
            val resBin = File(appDir, "app/resources/bin").apply { mkdirs() }
            val resAssets = File(appDir, "app/resources/assets").apply { mkdirs() }
            val rBin = File(appDir, "resources/bin").apply { mkdirs() }
            val rAssets = File(appDir, "resources/assets").apply { mkdirs() }
            if (binDir.exists()) {
                binDir.copyRecursively(destBin, overwrite = true)
                binDir.copyRecursively(resBin, overwrite = true)
                binDir.copyRecursively(rBin, overwrite = true)
            }
            if (assetsDir.exists()) {
                assetsDir.copyRecursively(destAssets, overwrite = true)
                assetsDir.copyRecursively(resAssets, overwrite = true)
                assetsDir.copyRecursively(rAssets, overwrite = true)
            }
            val exeFile = File(appDir, "v2whitelist.exe")
            val manifestFile = project.file("src/desktopMain/resources/windows/app.manifest")
            if (exeFile.exists() && manifestFile.exists()) {
                try {
                    ProcessBuilder("mt.exe", "-manifest", manifestFile.absolutePath, "-outputresource:${exeFile.absolutePath};#1")
                        .start()
                        .waitFor()
                } catch (_: Exception) {}
            }
            File(appDir, "install_service.bat").writeText(
                """
                @echo off
                chcp 65001 >nul
                net session >nul 2>&1
                if %errorLevel% neq 0 (
                    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
                    exit /b
                )
                set "BIN=%~dp0bin\v2w-service.exe"
                if not exist "%BIN%" set "BIN=%~dp0app\resources\bin\v2w-service.exe"
                if not exist "%BIN%" set "BIN=%~dp0v2w-service.exe"
                "%BIN%" -install
                pause
                """.trimIndent()
            )
            File(appDir, "uninstall_service.bat").writeText(
                """
                @echo off
                chcp 65001 >nul
                net session >nul 2>&1
                if %errorLevel% neq 0 (
                    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
                    exit /b
                )
                set "BIN=%~dp0bin\v2w-service.exe"
                if not exist "%BIN%" set "BIN=%~dp0app\resources\bin\v2w-service.exe"
                if not exist "%BIN%" set "BIN=%~dp0v2w-service.exe"
                "%BIN%" -uninstall
                pause
                """.trimIndent()
            )
        }

        val macAppDir = layout.buildDirectory.dir("compose/binaries/main/app/v2whitelist.app").get().asFile
        if (os.isMacOsX && macAppDir.exists()) {
            val resDir = File(macAppDir, "Contents/Resources").apply { mkdirs() }
            val destBin = File(resDir, "bin").apply { mkdirs() }
            val destAssets = File(resDir, "assets").apply { mkdirs() }
            if (binDir.exists()) binDir.copyRecursively(destBin, overwrite = true)
            if (assetsDir.exists()) assetsDir.copyRecursively(destAssets, overwrite = true)
            destBin.listFiles()?.forEach { it.setExecutable(true) }
        }
    }
}

tasks.matching { it.name.startsWith("compileKotlinDesktop") }.configureEach {
    dependsOn(generateDesktopBuildConfig)
}

