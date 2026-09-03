package com.xiaohypercleaner.data

import android.content.Context
import com.xiaohypercleaner.util.AppLog
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Каталог строк UI и alias-пакетов из assets.
 *
 * После обновления HyperOS достаточно править JSON в assets
 * (или подменить файл без переписывания логики SimpleRunner).
 */
object AdaptiveCatalog {
    private const val TAG = "AdaptiveCatalog"
    private const val PACKAGES_ASSET = "catalog/package_aliases.json"
    private const val LOCALE_ASSET = "catalog/ui_locale.json"

    private val loaded = AtomicReference<Snapshot?>(null)

    data class Snapshot(
        val stepOverrides: Map<String, List<String>>,
        val groups: Map<String, List<String>>,
        val skip: List<String>,
        val decline: List<String>,
        val enter: List<String>,
        val permissionAllow: List<String>,
        val settingsSearch: List<String>
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

    fun packagesForStep(
        context: Context,
        stepId: String,
        defaults: List<String>,
        profile: RomProfile
    ): List<String> {
        val snap = ensureLoaded(context)
        val fromJson = snap.stepOverrides[stepId].orEmpty()
        val merged = linkedSetOf<String>()
        merged.addAll(fromJson)
        merged.addAll(defaults)
        return profile.preferPackages(merged)
    }

    private fun load(context: Context): Snapshot {
        return try {
            val packagesJson = readAsset(context, PACKAGES_ASSET)
            val localeJson = readAsset(context, LOCALE_ASSET)
            val p = JSONObject(packagesJson)
            val l = JSONObject(localeJson)
            Snapshot(
                stepOverrides = p.optJSONObject("stepOverrides").toStringListMap(),
                groups = p.optJSONObject("groups").toStringListMap(),
                skip = l.optJSONArray("skip").toStringList(),
                decline = l.optJSONArray("decline").toStringList(),
                enter = l.optJSONArray("enter").toStringList(),
                permissionAllow = l.optJSONArray("permissionAllow").toStringList(),
                settingsSearch = l.optJSONArray("settingsSearch").toStringList()
            ).also {
                AppLog.i(
                    TAG,
                    "loaded catalog: steps=${it.stepOverrides.size} groups=${it.groups.size}"
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "catalog load failed, using empty overlay: ${e.message}")
            Snapshot(
                stepOverrides = emptyMap(),
                groups = emptyMap(),
                skip = emptyList(),
                decline = emptyList(),
                enter = emptyList(),
                permissionAllow = emptyList(),
                settingsSearch = emptyList()
            )
        }
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun org.json.JSONObject?.toStringListMap(): Map<String, List<String>> {
        this ?: return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = optJSONArray(k).toStringList()
        }
        return out
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
}
