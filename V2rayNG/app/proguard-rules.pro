# =========================================================================
# ProGuard / R8 Rules for ПроБел (v2whitelist)
# =========================================================================

# Keep line numbers and source file names for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# -------------------------------------------------------------------------
# Go / Xray Core JNI Bindings
# -------------------------------------------------------------------------
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# -------------------------------------------------------------------------
# Kotlin & Coroutines
# -------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# -------------------------------------------------------------------------
# Kotlinx Serialization (Used in :core:model, :core:config, etc.)
# -------------------------------------------------------------------------
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,allowobfuscation,allowshrinking class * {
    <init>(...);
}

# -------------------------------------------------------------------------
# GSON & DTO Models
# -------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.kiktor.v2whitelist.dto.** { *; }
-keep class com.kiktor.v2whitelist.core.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# -------------------------------------------------------------------------
# Storage & Database (MMKV)
# -------------------------------------------------------------------------
-keep class com.tencent.mmkv.** { *; }
-keep class com.kiktor.v2whitelist.handler.MmkvManager { *; }

# -------------------------------------------------------------------------
# Networking (OkHttp & Okio)
# -------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -------------------------------------------------------------------------
# Quickie QR Scanner & ML Kit
# -------------------------------------------------------------------------
-keep class io.github.g00fy2.quickie.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# -------------------------------------------------------------------------
# Android Architecture & Background Services
# -------------------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep public class com.kiktor.v2whitelist.service.** extends android.app.Service { *; }
-keep public class com.kiktor.v2whitelist.receiver.** extends android.content.BroadcastReceiver { *; }
-keep public class * extends androidx.preference.Preference { *; }
