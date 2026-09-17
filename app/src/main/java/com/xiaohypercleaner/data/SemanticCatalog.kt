package com.xiaohypercleaner.data

import android.content.Context
import com.xiaohypercleaner.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Семантическая таблица шагов (`assets/catalog/semantic_steps.json`).
 *
 * Единственный источник семантики: keywords, screenMarkers, actionType,
 * sequenceKind, consentTexts, overflowMenuLabels, fallbackDrillPath, 7 локалей.
 * Захардкоженные маршруты остаются подсказками (fallbackDrillPath),
 * `SimpleSteps.searchTexts` — deprecated-fallback на один релиз.
 */
object SemanticCatalog {

    private const val TAG = "semantic"
    private const val ASSET_PATH = "catalog/semantic_steps.json"

    /** Поддерживаемые локали каталога (паритет с resources values-<locale>). */
    val LOCALES: List<String> = listOf("ru", "en", "es", "zh", "hi", "pt", "id")

    enum class ActionType {
        TOGGLE, TAP_CONFIRM, SEQUENCE, SKIP;

        companion object {
            fun from(raw: String?): ActionType = when (raw?.lowercase(Locale.ROOT)) {
                "tap_confirm" -> TAP_CONFIRM
                "sequence" -> SEQUENCE
                "skip" -> SKIP
                else -> TOGGLE
            }
        }
    }

    enum class SequenceKind {
        CONSENT_WALL, DELAYED_CONFIRM, TAP_FALLBACK_SEQUENCE, CLEAR_DATA_DECLINE, OVERFLOW_MENU;

        companion object {
            fun from(raw: String?): SequenceKind? = entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true)
            }
        }
    }

    data class SemanticStep(
        val id: String,
        val titles: Map<String, String>,
        val keywords: Map<String, List<String>>,
        val screenMarkers: Map<String, List<String>>,
        val actionType: ActionType,
        val sequenceKind: SequenceKind?,
        val confirmTexts: Map<String, List<String>>,
        val tapFallbackTexts: Map<String, List<String>>,
        val consentTexts: Map<String, List<String>>,
        val overflowMenuLabels: Map<String, List<String>>,
        val confirmWaitMs: Long,
        val maxConsentIterations: Int,
        val launchPackage: String?,
        val skipReason: String?,
        val fallbackDrillPath: List<List<String>>,
        val safe: Boolean,
        val localeCoverage: List<String>,
        /** Пакеты-цели шага (видимость задаётся <queries> манифеста). */
        val requiredPackages: List<String> = emptyList()
    )

    /** Политика обработки системных диалогов (deny по умолчанию). */
    data class ConsentPolicy(
        val welcomeMarkers: Map<String, List<String>>,
        val welcomeActions: Map<String, List<String>>,
        val permissionMarkers: Map<String, List<String>>,
        val defaultDecision: String,
        val denyTexts: Map<String, List<String>>,
        val allowTexts: Map<String, List<String>>,
        val allowOverrides: Set<String>,
        val maxIterationsPerStep: Int
    )

    @Volatile
    private var stepsById: Map<String, SemanticStep> = emptyMap()

    @Volatile
    private var loadedSteps: List<SemanticStep> = emptyList()

    @Volatile
    private var consentPolicy: ConsentPolicy? = null

    @Volatile
    private var isLoaded: Boolean = false

    private val lock = Any()

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(lock) {
            if (isLoaded) return
            load(context)
        }
    }

    fun all(): List<SemanticStep> = loadedSteps

    fun step(id: String): SemanticStep? = stepsById[id]

    fun policy(): ConsentPolicy? = consentPolicy

    /** Локаль устройства, если покрыта каталогом; иначе en → ru → первая доступная. */
    fun locale(): String {
        val device = Locale.getDefault().language.lowercase(Locale.ROOT)
        if (device in LOCALES) return device
        if ("en" in LOCALES) return "en"
        if ("ru" in LOCALES) return "ru"
        return LOCALES.firstOrNull() ?: "en"
    }

    // ─── Доступ к текстам шага ───────────────────────────────────────────

    fun keywords(id: String, fallback: List<String> = emptyList()): List<String> =
        merge(localizedTexts(step(id)?.keywords), fallback)

    fun screenMarkers(id: String): List<String> = localizedTexts(step(id)?.screenMarkers)

    fun confirmTexts(id: String, fallback: List<String> = emptyList()): List<String> =
        merge(localizedTexts(step(id)?.confirmTexts), fallback)

    fun tapFallbackTexts(id: String, fallback: List<String> = emptyList()): List<String> =
        merge(localizedTexts(step(id)?.tapFallbackTexts), fallback)

    fun consentTexts(id: String): List<String> = localizedTexts(step(id)?.consentTexts)

    fun overflowMenuLabels(id: String): List<String> = localizedTexts(step(id)?.overflowMenuLabels)

    fun title(id: String, fallback: String): String {
        val titles = step(id)?.titles ?: return fallback
        return titles[locale()] ?: titles["en"] ?: titles["ru"] ?: fallback
    }

    fun actionType(id: String): ActionType = step(id)?.actionType ?: ActionType.TOGGLE

    fun sequenceKind(id: String): SequenceKind? = step(id)?.sequenceKind

    fun confirmWaitMs(id: String, fallback: Long): Long =
        step(id)?.confirmWaitMs?.takeIf { it > 0L } ?: fallback

    fun maxConsentIterations(id: String, fallback: Int): Int =
        step(id)?.maxConsentIterations?.takeIf { it > 0 } ?: fallback

    fun launchPackage(id: String): String? = step(id)?.launchPackage

    /** Пакеты-цели шага из семантической таблицы (fallback к legacy requiredPackages). */
    fun requiredPackages(id: String): List<String> = step(id)?.requiredPackages.orEmpty()

    fun skipReason(id: String): String? = step(id)?.skipReason

    /** Подсказки-фолбэки drillPath (старые вариантные пути). */
    fun fallbackDrillPath(id: String): List<List<String>> = step(id)?.fallbackDrillPath.orEmpty()

    // ─── Consent policy ──────────────────────────────────────────────────

    fun welcomeMarkers(): List<String> = localizedTexts(consentPolicy?.welcomeMarkers)

    fun welcomeActions(): List<String> = localizedTexts(consentPolicy?.welcomeActions)

    fun permissionMarkers(): List<String> = localizedTexts(consentPolicy?.permissionMarkers)

    fun denyTexts(): List<String> = localizedTexts(consentPolicy?.denyTexts)

    fun allowTexts(): List<String> = localizedTexts(consentPolicy?.allowTexts)

    fun shouldAllow(stepId: String): Boolean =
        consentPolicy?.allowOverrides?.contains(stepId) == true

    fun maxConsentIterationsPolicy(): Int =
        consentPolicy?.maxIterationsPerStep?.coerceAtLeast(1) ?: 3

    // ─── Загрузка ─────────────────────────────────────────────────────────

    private fun load(context: Context) {
        try {
            val raw = context.assets.open(ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            val arr = root.optJSONArray("steps") ?: JSONArray()
            val parsed = ArrayList<SemanticStep>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parsed.add(parseStep(it)) }
            }
            loadedSteps = parsed
            stepsById = parsed.associateBy { it.id }
            consentPolicy = parsePolicy(root.optJSONObject("consentPolicy"))
            AppLog.i(
                TAG,
                "loaded steps=${parsed.size} locales=${LOCALES.size} " +
                    "policy=${if (consentPolicy != null) "yes" else "no"} locale=${locale()}"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "load failed: ${e.message}", e)
            loadedSteps = emptyList()
            stepsById = emptyMap()
            consentPolicy = null
        } finally {
            isLoaded = true
        }
    }

    private fun parseStep(o: JSONObject): SemanticStep = SemanticStep(
        id = o.optString("id"),
        titles = parseStringMap(o.optJSONObject("title")),
        keywords = parseListMap(o.optJSONObject("keywords")),
        screenMarkers = parseListMap(o.optJSONObject("screenMarkers")),
        actionType = ActionType.from(o.optString("actionType")),
        sequenceKind = SequenceKind.from(o.optString("sequenceKind").takeIf { it.isNotEmpty() }),
        confirmTexts = parseListMap(o.optJSONObject("confirmTexts")),
        tapFallbackTexts = parseListMap(o.optJSONObject("tapFallbackTexts")),
        consentTexts = parseListMap(o.optJSONObject("consentTexts")),
        overflowMenuLabels = parseListMap(o.optJSONObject("overflowMenuLabels")),
        confirmWaitMs = o.optLong("confirmWaitMs", 0L),
        maxConsentIterations = o.optInt("maxConsentIterations", 0),
        launchPackage = o.optString("launchPackage").takeIf { it.isNotEmpty() },
        skipReason = o.optString("skipReason").takeIf { it.isNotEmpty() },
        fallbackDrillPath = parseDrillPath(o.optJSONArray("fallbackDrillPath")),
        safe = o.optBoolean("safe", true),
        localeCoverage = parseStringArray(o.optJSONArray("localeCoverage")),
        requiredPackages = parseStringArray(o.optJSONArray("requiredPackages"))
    )

    private fun parsePolicy(o: JSONObject?): ConsentPolicy? {
        o ?: return null
        return ConsentPolicy(
            welcomeMarkers = parseListMap(o.optJSONObject("welcomeMarkers")),
            welcomeActions = parseListMap(o.optJSONObject("welcomeActions")),
            permissionMarkers = parseListMap(o.optJSONObject("permissionMarkers")),
            defaultDecision = o.optString("defaultDecision", "deny"),
            denyTexts = parseListMap(o.optJSONObject("denyTexts")),
            allowTexts = parseListMap(o.optJSONObject("allowTexts")),
            allowOverrides = parseStringArray(o.optJSONArray("allowOverrides")).toSet(),
            maxIterationsPerStep = o.optInt("maxIterationsPerStep", 3)
        )
    }

    private fun parseStringMap(o: JSONObject?): Map<String, String> {
        o ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        o.keys().forEach { key -> map[key] = o.optString(key) }
        return map
    }

    private fun parseListMap(o: JSONObject?): Map<String, List<String>> {
        o ?: return emptyMap()
        val map = LinkedHashMap<String, List<String>>()
        o.keys().forEach { key -> map[key] = parseStringArray(o.optJSONArray(key)) }
        return map
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }

    private fun parseDrillPath(arr: JSONArray?): List<List<String>> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            parseStringArray(arr.optJSONArray(i)).takeIf { it.isNotEmpty() }
        }
    }

    /** Тексты локали устройства с фолбэком en → ru → первой непустой локали. */
    private fun localizedTexts(map: Map<String, List<String>>?): List<String> {
        map ?: return emptyList()
        map[locale()]?.takeIf { it.isNotEmpty() }?.let { return it }
        map["en"]?.takeIf { it.isNotEmpty() }?.let { return it }
        map["ru"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return map.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun merge(primary: List<String>, fallback: List<String>): List<String> =
        (primary + fallback).filter { it.isNotBlank() }.distinct()

    /** Сброс синглтона — только для тестов. */
    internal fun resetForTest() {
        synchronized(lock) {
            loadedSteps = emptyList()
            stepsById = emptyMap()
            consentPolicy = null
            isLoaded = false
        }
    }
}