# Свой код не обфусцируем (маленький проект, проще отлаживать)
-keep class com.xiaohypercleaner.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# DataStore
-dontwarn androidx.datastore.**