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
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.core)
                implementation("org.slf4j:slf4j-nop:2.0.16")
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
        jvmArgs += listOf("-Djdk.gtk.version=3")

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage
            )
            packageName = "v2whitelist"
            packageVersion = "2.0.0"
            description = "v2whitelist Desktop Client"
            vendor = "NullCore"
        }
    }
}
