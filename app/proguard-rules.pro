# XiaoHyperCleaner ProGuard Rules

# Для читаемых stack traces после обфускации
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Shizuku reflection
-keep class rikka.shizuku.Shizuku {
    rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.** { *; }

# Свой код не обфусцируем (маленький проект, проще отлаживать)
-keep class com.xiaohypercleaner.** { *; }

# Coroutines
-keepclassmembernames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# DataStore
-dontwarn androidx.datastore.**

# Compose
-keep class androidx.compose.** { *; }