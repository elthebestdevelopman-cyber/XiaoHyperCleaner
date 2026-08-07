# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences {
    public static final ** *;
}

# Compose & Custom Data
-keep class androidx.compose.** { *; }
-keep class com.xiaohypercleaner.data.ServiceRegistry { *; }