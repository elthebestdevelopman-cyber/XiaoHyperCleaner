package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.TextMatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище кэша найденных активностей. Реализуется [PreferencesManager]
 * (DataStore, ключ `act_cache_<pkg>_<versionCode>_<miuiIncremental>`).
 */
interface ActivityCacheStore {
    suspend fun loadActivityCache(cacheKey: String): String?
    suspend fun saveActivityCache(cacheKey: String, json: String)
    suspend fun clearActivityCache(pkg: String)
}

/**
 * Сканер активностей целевого пакета: вместо захардкоженных маршрутов находит
 * экраны по семантике (keyword-match по имени класса и подписи активности).
 *
 * Кэш версионируется вместе с пакетом и прошивкой:
 * `act_cache_<pkg>_<versionCode>_<miuiIncremental>`; при отсутствии
 * incremental используется суффикс `unknown`.
 */
object ActivityScanner {

    private const val TAG = "scan"
    private const val MAX_CANDIDATES = 5
    private const val MIN_KEYWORD_LENGTH = 3
    private const val CACHE_SCHEMA = 1

    /** Найденная активность с весом совпадения. */
    data class ActivityCandidate(
        val className: String,
        val label: String,
        val score: Int
    )

    /** Результат скана пакета. */
    data class ScanResult(
        val packageName: String,
        val candidates: List<ActivityCandidate>,
        val cacheKey: String,
        val fromCache: Boolean,
        /** Причина результата: ok | cache | package_invisible | no_exported_activity. */
        val reason: String = "ok"
    ) {
        val isEmpty: Boolean get() = candidates.isEmpty()
    }

    /**
     * Ключ кэша активностей. Отсутствующий incremental не ломает ключ —
     * подставляется `unknown` (девайсы без MIUI-строки сборки).
     */
    fun cacheKey(pkg: String, versionCode: Long, incremental: String?): String {
        val suffix = incremental?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        return "act_cache_${pkg}_${versionCode}_$suffix"
    }

    /**
     * Вес совпадения активности с keywords: подпись весит больше имени класса.
     * Возвращает 0, если активность не похожа ни на один ключ.
     */
    fun scoreCandidate(className: String?, label: String?, keywords: List<String>): Int {
        val simple = className?.substringAfterLast('.')?.removeSuffix("Activity")
            ?.let { TextMatcher.normalize(it) } ?: ""
        val normLabel = TextMatcher.normalize(label)
        var best = 0
        for (raw in keywords) {
            val kw = TextMatcher.normalize(raw)
            if (kw.length < MIN_KEYWORD_LENGTH) continue
            if (normLabel.isNotEmpty()) {
                when {
                    normLabel == kw -> best = maxOf(best, 100)
                    normLabel.contains(kw) -> best = maxOf(best, 60)
                }
            }
            if (simple.isNotEmpty()) {
                when {
                    simple == kw -> best = maxOf(best, 90)
                    simple.contains(kw) -> best = maxOf(best, 50)
                    simple.length >= 4 && kw.contains(simple) -> best = maxOf(best, 30)
                }
            }
        }
        return best
    }

    /**
     * Скан активностей пакета с кэшем: cache hit → мгновенный результат,
     * иначе запрос к PackageManager и запись кэша.
     */
    suspend fun scan(
        context: Context,
        pkg: String,
        keywords: List<String>,
        cache: ActivityCacheStore? = null,
        incremental: String? = null
    ): ScanResult {
        val versionCode = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(pkg, 0))
        }.getOrDefault(0L)
        val key = cacheKey(pkg, versionCode, incremental ?: Build.VERSION.INCREMENTAL)

        cache?.let { store ->
            val cached = runCatching { store.loadActivityCache(key) }.getOrNull()
            val parsed = cached?.takeIf { it.isNotBlank() }?.let { fromJson(it) }
            if (parsed != null) {
                AppLog.i(TAG, "cache hit pkg=$pkg key=$key candidates=${parsed.size}")
                return ScanResult(pkg, parsed, key, fromCache = true, reason = "cache")
            }
        }

        // Причина нуля различима: пакет невидим (package visibility) ≠ нет компонент.
        if (!isPackageVisible(context, pkg)) {
            AppLog.w(
                TAG,
                "scan pkg=$pkg key=$key reason=package_invisible candidates=0 — " +
                    "пакет не виден (нужен <package>/<queries> или launcher-активность)"
            )
            return ScanResult(pkg, emptyList(), key, fromCache = false, reason = "package_invisible")
        }

        val candidates = runCatching { scanActivities(context, pkg, keywords) }
            .getOrElse { e ->
                AppLog.w(TAG, "scan failed pkg=$pkg: ${e.message}")
                emptyList()
            }
        val reason = if (candidates.isEmpty()) "no_exported_activity" else "ok"
        AppLog.i(
            TAG,
            "scan pkg=$pkg key=$key reason=$reason candidates=${candidates.size} " +
                "top=${candidates.firstOrNull()?.className ?: "-"}"
        )
        if (cache != null && candidates.isNotEmpty()) {
            runCatching { cache.saveActivityCache(key, toJson(candidates)) }
        }
        return ScanResult(pkg, candidates, key, fromCache = false, reason = reason)
    }

    /**
     * Видимость пакета (Android 11+ package visibility): прямой запрос, иначе —
     * наличие launcher-активности в видимом множестве. Голый getPackageInfo по
     * хардкод-имени даёт ложное «не установлен».
     */
    fun isPackageVisible(context: Context, pkg: String): Boolean {
        val direct = runCatching {
            context.packageManager.getPackageInfo(pkg, 0); true
        }.getOrDefault(false)
        if (direct) return true
        val launcherVisible = runCatching {
            context.packageManager.queryIntentActivities(launcherIntent(pkg), 0).isNotEmpty()
        }.getOrDefault(false)
        if (launcherVisible) {
            AppLog.d(TAG, "package $pkg visible via launcher-intent query")
        }
        return launcherVisible
    }

    /** Сериализация кандидатов в кэш (schema-версия обязательна). */
    fun toJson(candidates: List<ActivityCandidate>): String {
        val arr = JSONArray()
        candidates.forEach { c ->
            arr.put(
                JSONObject()
                    .put("class", c.className)
                    .put("label", c.label)
                    .put("score", c.score)
            )
        }
        return JSONObject().put("schema", CACHE_SCHEMA).put("candidates", arr).toString()
    }

    /** Разбор кэша; несовпадающая schema или битый JSON → null (перескан). */
    fun fromJson(json: String): List<ActivityCandidate>? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("schema", 0) != CACHE_SCHEMA) return@runCatching null
        val arr = root.optJSONArray("candidates") ?: return@runCatching null
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cn = o.optString("class").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ActivityCandidate(cn, o.optString("label"), o.optInt("score"))
        }
    }.getOrNull()

    /** Неявный launcher-интент по пакету (класс заранее неизвестен). */
    private fun launcherIntent(pkg: String): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)

    /**
     * Launcher-активности пакета из видимого множества. Фолбэк, когда
     * GET_ACTIVITIES фильтруется package visibility: entry point приложения
     * всё равно даёт явную компоненту для запуска.
     */
    private fun launcherActivities(
        context: Context,
        pkg: String,
        keywords: List<String>
    ): List<ActivityCandidate> {
        val resolved = runCatching {
            context.packageManager.queryIntentActivities(launcherIntent(pkg), 0)
        }.getOrNull().orEmpty()
        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val className = activity.name ?: return@mapNotNull null
            if (activity.packageName != pkg) return@mapNotNull null
            val label = runCatching { activity.loadLabel(context.packageManager).toString() }
                .getOrNull().orEmpty()
            val score = scoreCandidate(className, label, keywords)
            ActivityCandidate(className, label, if (score > 0) score else 1)
        }
    }

    /**
     * Запрос экспортируемых активностей пакета и скоринг по keywords.
     * Package visibility обеспечена `<queries>` в манифесте; при пустом
     * результате — фолбэк на launcher-активности видимого множества.
     */
    private fun scanActivities(
        context: Context,
        pkg: String,
        keywords: List<String>
    ): List<ActivityCandidate> {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
        val activities = info.activities.orEmpty()
        val result = ArrayList<ActivityCandidate>()
        for (activity in activities) {
            val className = activity.name ?: continue
            // Явный запуск чужой активности возможен только для exported.
            if (!activity.exported) continue
            if (activity.packageName != null && activity.packageName != pkg) continue
            val label = runCatching { activity.loadLabel(context.packageManager).toString() }
                .getOrNull().orEmpty()
            val score = scoreCandidate(className, label, keywords)
            if (score > 0) result.add(ActivityCandidate(className, label, score))
        }
        if (result.isEmpty()) {
            val launcher = launcherActivities(context, pkg, keywords)
            if (launcher.isNotEmpty()) {
                AppLog.i(TAG, "scan pkg=$pkg launcher-fallback candidates=${launcher.size}")
            }
            return launcher
                .sortedWith(compareByDescending<ActivityCandidate> { it.score }.thenBy { it.className })
                .take(MAX_CANDIDATES)
        }
        return result
            .sortedWith(compareByDescending<ActivityCandidate> { it.score }.thenBy { it.className })
            .take(MAX_CANDIDATES)
    }
}