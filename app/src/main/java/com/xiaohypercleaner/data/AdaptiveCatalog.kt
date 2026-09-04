package com.xiaohypercleaner.data

import android.content.Context
import com.xiaohypercleaner.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Каталог строк UI и alias-пакетов из assets.
 *
 * Адаптация к новым HyperOS/MIUI без переписывания SimpleRunner:
 * 1. `catalog/package_aliases.json` — пакеты по шагам/группам
 * 2. `catalog/ui_locale.json` — кнопки диалогов (skip/decline/enter/…)
 * 3. `catalog/step_ui.json` — searchTexts / confirmTexts / доп. синонимы drill
 *
 * После OTA достаточно обновить JSON и выпустить патч.
 */
object AdaptiveCatalog {
    private const val TAG = "AdaptiveCatalog"
    private const val PACKAGES_ASSET = "catalog/package_aliases.json"
    private const val LOCALE_ASSET = "catalog/ui_locale.json"
    private const val STEP_UI_ASSET = "catalog/step_ui.json"

    /** Кнопки, которые нельзя автокликать — запускают очистку Безопасности */
    private val FORBIDDEN_ENTER = setOf(
        "начать", "start", "开始", "comenzar", "стартовать"
    )

    private val loaded = AtomicReference<Snapshot?>(null)

    data class StepUiOverride(
        val searchTexts: List<String> = emptyList(),
        val confirmTexts: List<String> = emptyList(),
        val additionalToggles: List<String> = emptyList(),
        val drillExtras: List<List<String>> = emptyList()
    )

    data class Snapshot(
        val stepOverrides: Map<String, List<String>>,
        val groups: Map<String, List<String>>,
        val stepGroup: Map<String, String>,
        val skip: List<String>,
        val decline: List<String>,
        val enter: List<String>,
        val permissionAllow: List<String>,
        val settingsSearch: List<String>,
        val stepUi: Map<String, StepUiOverride>
    )

    fun ensureLoaded(context: Context): Snapshot {
        loaded.get()?.let { return it }
        synchronized(this) {
            loaded.get()?.let { return it }
            val snap = load(context.applicationContext)
            loaded.set(snap)
            return snap
        }
    }

    /** Для тестов / hot-reload после подмены assets в debug */
    fun clearCache() {
        loaded.set(null)
    }

    fun resolveInstalledPackageForGroup(
        context: Context,
        groupName: String,
        profile: RomProfile
    ): String? {
        val snap = ensureLoaded(context)
        val candidates = snap.groups[groupName].orEmpty()
        val preferred = profile.preferPackages(candidates)
        val pm = context.packageManager
        for (pkg in preferred) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun packagesForStep(
        context: Context,
        stepId: String,
        defaults: List<String>,
        profile: RomProfile
    ): List<String> {
        val snap = ensureLoaded(context)
        val merged = linkedSetOf<String>()
        merged.addAll(snap.stepOverrides[stepId].orEmpty())
        snap.stepGroup[stepId]?.let { g ->
            merged.addAll(snap.groups[g].orEmpty())
        }
        // Эвристика: id префикс → группа (browser_sys → browser)
        val groupGuess = stepId.substringBefore('_').takeIf { it.isNotBlank() }
        if (groupGuess != null) {
            merged.addAll(snap.groups[groupGuess].orEmpty())
            merged.addAll(snap.groups[stepId].orEmpty())
        }
        merged.addAll(defaults)
        return profile.preferPackages(merged)
    }

    fun mergeSearchTexts(context: Context, stepId: String, defaults: List<String>): List<String> {
        val extra = ensureLoaded(context).stepUi[stepId]?.searchTexts.orEmpty()
        return (defaults + extra).distinct()
    }

    fun mergeConfirmTexts(context: Context, stepId: String, defaults: List<String>): List<String> {
        val extra = ensureLoaded(context).stepUi[stepId]?.confirmTexts.orEmpty()
        return (defaults + extra).distinct()
    }

    fun mergeAdditionalToggles(
        context: Context,
        stepId: String,
        defaults: List<String>
    ): List<String> {
        val extra = ensureLoaded(context).stepUi[stepId]?.additionalToggles.orEmpty()
        return (defaults + extra).distinct()
    }

    /**
     * Расширяет каждый уровень drillPath синонимами из step_ui.json (drillExtras[i]).
     */
    fun mergeDrillPath(
        context: Context,
        stepId: String,
        defaults: List<List<String>>
    ): List<List<String>> {
        val extras = ensureLoaded(context).stepUi[stepId]?.drillExtras.orEmpty()
        if (extras.isEmpty()) return defaults
        return defaults.mapIndexed { i, level ->
            (level + extras.getOrNull(i).orEmpty()).distinct()
        }
    }

    fun safeEnterTexts(raw: List<String>): List<String> =
        raw.filterNot { FORBIDDEN_ENTER.contains(it.trim().lowercase()) }

    private fun load(context: Context): Snapshot {
        return try {
            val packagesJson = readAsset(context, PACKAGES_ASSET)
            val localeJson = readAsset(context, LOCALE_ASSET)
            val stepUiJson = runCatching { readAsset(context, STEP_UI_ASSET) }.getOrDefault("{}")
            val p = JSONObject(packagesJson)
            val l = JSONObject(localeJson)
            val s = JSONObject(stepUiJson)
            val enterRaw = l.optJSONArray("enter").toStringList()
            Snapshot(
                stepOverrides = p.optJSONObject("stepOverrides").toStringListMap(),
                groups = p.optJSONObject("groups").toStringListMap(),
                stepGroup = p.optJSONObject("stepGroup").toStringMap(),
                skip = l.optJSONArray("skip").toStringList(),
                decline = l.optJSONArray("decline").toStringList(),
                enter = safeEnterTexts(enterRaw),
                permissionAllow = l.optJSONArray("permissionAllow").toStringList(),
                settingsSearch = l.optJSONArray("settingsSearch").toStringList(),
                stepUi = s.optJSONObject("steps").toStepUiMap()
            ).also {
                AppLog.i(
                    TAG,
                    "loaded catalog: pkgSteps=${it.stepOverrides.size} groups=${it.groups.size} " +
                        "uiSteps=${it.stepUi.size} enterSafe=${it.enter.size}/${enterRaw.size}"
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "catalog load failed, using empty overlay: ${e.message}")
            Snapshot(
                stepOverrides = emptyMap(),
                groups = emptyMap(),
                stepGroup = emptyMap(),
                skip = emptyList(),
                decline = emptyList(),
                enter = emptyList(),
                permissionAllow = emptyList(),
                settingsSearch = emptyList(),
                stepUi = emptyMap()
            )
        }
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun JSONObject?.toStringListMap(): Map<String, List<String>> {
        this ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = optJSONArray(k).toStringList()
        }
        return out
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        this ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it }
        }
        return out
    }

    private fun JSONObject?.toStepUiMap(): Map<String, StepUiOverride> {
        this ?: return emptyMap()
        val out = mutableMapOf<String, StepUiOverride>()
        val keys = keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val obj = optJSONObject(id) ?: continue
            out[id] = StepUiOverride(
                searchTexts = obj.optJSONArray("searchTexts").toStringList(),
                confirmTexts = obj.optJSONArray("confirmTexts").toStringList(),
                additionalToggles = obj.optJSONArray("additionalToggles").toStringList(),
                drillExtras = obj.optJSONArray("drillExtras").toListOfStringLists()
            )
        }
        return out
    }

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun JSONArray?.toListOfStringLists(): List<List<String>> {
        this ?: return emptyList()
        return buildList {
            for (i in 0 until length()) {
                add(optJSONArray(i).toStringList())
            }
        }
    }
}
