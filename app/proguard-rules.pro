# XiaoHyperCleaner ProGuard Rules

# Для читаемых stack traces после обфускации
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Свой код не обфусцируем (маленький проект, проще отлаживать)
-keep class com.xiaohypercleaner.** { *; }

# Coroutines
-keepclassmembernames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# DataStore
-dontwarn androidx.datastore.**

# Compose
-keep class androidx.compose.** { *; }