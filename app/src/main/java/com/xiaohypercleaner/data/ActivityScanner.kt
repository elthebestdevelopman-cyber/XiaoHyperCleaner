package com.xiaohypercleaner.data

import android.content.Context
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
        val fromCache: Boolean
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
                return ScanResult(pkg, parsed, key, fromCache = true)
            }
        }

        val candidates = runCatching { scanActivities(context, pkg, keywords) }
            .getOrElse { e ->
                AppLog.w(TAG, "scan failed pkg=$pkg: ${e.message}")
                emptyList()
            }
        AppLog.i(
            TAG,
            "cache miss pkg=$pkg key=$key candidates=${candidates.size} " +
                "top=${candidates.firstOrNull()?.className ?: "-"}"
        )
        if (cache != null && candidates.isNotEmpty()) {
            runCatching { cache.saveActivityCache(key, toJson(candidates)) }
        }
        return ScanResult(pkg, candidates, key, fromCache = false)
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

    /**
     * Запрос экспортируемых активностей пакета и скоринг по keywords.
     * Package visibility обеспечена `<queries>` в манифесте.
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
        return result
            .sortedWith(compareByDescending<ActivityCandidate> { it.score }.thenBy { it.className })
            .take(MAX_CANDIDATES)
    }
}