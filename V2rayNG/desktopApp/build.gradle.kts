plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
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
            "-Djdk.gtk.version=3",
            "-Djdk.lang.Process.launchMechanism=vfork"
        )

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Pkg
            )
            val targetPackageVersion = (project.findProperty("packageVersion") as? String)?.removePrefix("v") ?: "2.0.0"
            packageName = "v2whitelist"
            packageVersion = targetPackageVersion
            description = "ПроБел — v2whitelist Desktop"
            vendor = "NullCore"
            copyright = "© 2026 NullCore"

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                debMaintainer = "developer@nullcore.dev"
                menuGroup = "Network"
                appCategory = "Network"
            }

            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menu = true
                menuGroup = "ПроБел"
                upgradeUuid = "d7e98a35-43a1-4ee3-b0be-0e54b6b19812"
                dirChooser = true
                perUserInstall = false
            }

            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                bundleID = "com.kiktor.v2whitelist"
                dockName = "ПроБел"
                packageBuildVersion = targetPackageVersion
            }
        }
    }
}

tasks.matching {
    it.name in listOf("createDistributable", "packageAppImage", "packageReleaseAppImage", "packageDistributionForCurrentOS")
}.configureEach {
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/v2whitelist").get().asFile
        if (appDir.exists() && org.gradle.internal.os.OperatingSystem.current().isLinux) {
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
                    -Dskiko.library.path="${'$'}APPDIR/lib/app" \
                    -Dcompose.application.resources.dir="${'$'}APPDIR/lib/app/resources" \
                    -Dcompose.application.configure.swing.globals=true \
                    -Djpackage.app-version=2.0.0 \
                    -cp "${'$'}APPDIR/lib/app/*" \
                    com.kiktor.v2whitelist.desktop.MainKt \
                    "${'$'}@"
            """.trimIndent()
            binFile.writeText(launcherScript)
            binFile.setExecutable(true)
        }
    }
}
