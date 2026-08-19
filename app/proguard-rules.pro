# XiaoHyperCleaner ProGuard Rules

# Для читаемых stack traces после обфускации
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku reflection
-keep class rikka.shizuku.Shizuku {
    rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.** { *; }

# ✅ ИСПРАВЛЕНО: защищаем только то, что трогает reflection/сериализация/манифест.
# UI-слой (ui.*) теперь обфусцируется — это безопасно для Compose.
-keep public class com.xiaohypercleaner.data.** { *; }
-keep public class com.xiaohypercleaner.service.** { *; }
-keep public class com.xiaohypercleaner.engine.** { *; }

# Data-классы отчётов (могут сериализоваться в JSON)
-keep class com.xiaohypercleaner.data.OptimizationReport { *; }
-keep class com.xiaohypercleaner.data.SimpleSteps$Step { *; }
-keep class com.xiaohypercleaner.data.SimpleStepState { *; }

# Coroutines
-keepclassmembernames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# DataStore
-dontwarn androidx.datastore.**

# Compose
-keep class androidx.compose.** { *; }