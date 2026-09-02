# ═══════════════════════════════════════════════════════════════
# XiaoHyperCleaner ProGuard Rules
# ═══════════════════════════════════════════════════════════════
# ИСПРАВЛЕНО (Shrinker inspection «Overly broad keep rule»):
# широкие правила вида `-keep class X.** { *; }` сужены до точечных.
# Компоненты из манифеста (Activity/Service/Provider) AGP сохраняет
# АВТОМАТИЧЕСКИ, а библиотеки (Compose, DataStore, protobuf, Shizuku)
# поставляют собственные consumer-rules — дублировать их не нужно.
# ═══════════════════════════════════════════════════════════════

# ───────────────────────────────────────────────────────────────
# Общие атрибуты (для читаемых stack traces в Crashlytics)
# ───────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses

# ───────────────────────────────────────────────────────────────
# Android Framework компоненты (система находит их по имени)
# ───────────────────────────────────────────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# AccessibilityService — система ищет сервис по имени из
# accessibility_service_config.xml (явно, для надёжности)
-keep class com.xiaohypercleaner.service.AdbEnablerService { *; }

# Application класс
-keep class com.xiaohypercleaner.XiaoHyperApp { *; }

# ───────────────────────────────────────────────────────────────
# Shizuku API (reflection для IPC)
# ИСПРАВЛЕНО: вместо rikka.shizuku.** { *; } — только public API
# ───────────────────────────────────────────────────────────────
-keep public class rikka.shizuku.* { public *; }
-keep public interface rikka.shizuku.* { public *; }
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keep class rikka.shizuku.Shizuku {
    rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# ───────────────────────────────────────────────────────────────
# Data-слой
# ИСПРАВЛЕНО: вместо com.xiaohypercleaner.data.** { *; } — только
# классы, участвующие в сериализации/передаче. Остальные достижимы
# напрямую и сохраняются R8 автоматически.
# ───────────────────────────────────────────────────────────────
-keep class com.xiaohypercleaner.data.OptimizationReport { *; }
-keep class com.xiaohypercleaner.data.OptimizationReport$* { *; }
-keep class com.xiaohypercleaner.data.SimpleSteps$Step { *; }
-keep class com.xiaohypercleaner.data.SimpleStepState { *; }

# ───────────────────────────────────────────────────────────────
# Service-слой
# ИСПРАВЛЕНО: правило com.xiaohypercleaner.service.** { *; } УДАЛЕНО.
# AdbEnablerService и OverlayService сохранены правилами выше
# (явное + * extends android.app.Service). Остальные классы
# (SimpleRunner, OverlayController, SimpleStepBridge, ChainFlags)
# достижимы напрямую — R8 сохранит их сам.
# ───────────────────────────────────────────────────────────────

# ───────────────────────────────────────────────────────────────
# Kotlinx Serialization (если OptimizationReport @Serializable)
# ───────────────────────────────────────────────────────────────
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xiaohypercleaner.data.**$$serializer { *; }
-keepclassmembers class com.xiaohypercleaner.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.xiaohypercleaner.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ───────────────────────────────────────────────────────────────
# DataStore + protobuf-lite
# ИСПРАВЛЕНО: удалены широкие -keep androidx.datastore.** и
# com.google.protobuf.** — библиотеки поставляют consumer-rules.
# Оставлены только dontwarn и узкое правило GeneratedMessageLite.
# ───────────────────────────────────────────────────────────────
-dontwarn androidx.datastore.**
#noinspection ShrinkerUnresolvedReference
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ───────────────────────────────────────────────────────────────
# Kotlinx Coroutines
# ───────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ───────────────────────────────────────────────────────────────
# Jetpack Compose
# ИСПРАВЛЕНО: удалены широкие -keep androidx.compose.** и
# material.icons.** — Compose поставляет consumer-rules, а иконки
# (Icons.Filled.*) достижимы напрямую из кода.
# ───────────────────────────────────────────────────────────────

# ───────────────────────────────────────────────────────────────
# Enum classes (используются как state markers)
# ───────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ───────────────────────────────────────────────────────────────
# Parcelable (если есть data-классы с @Parcelize)
# ───────────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ───────────────────────────────────────────────────────────────
# Исключения (для читаемых логов)
# ───────────────────────────────────────────────────────────────
-keep class * extends java.lang.Exception { *; }