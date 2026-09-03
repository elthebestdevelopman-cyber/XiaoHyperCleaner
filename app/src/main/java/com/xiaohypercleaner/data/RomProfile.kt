package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xiaohypercleaner.util.AppLog

/**
 * Профиль прошивки: CN / Global / Unknown.
 * Нужен, чтобы выбирать правильный alias пакета, когда на устройстве
 * сосуществуют заглушки нескольких регионов.
 */
enum class RomRegion {
    CN,
    GLOBAL,
    UNKNOWN
}

data class RomProfile(
    val region: RomRegion,
    val miuiVersion: String?,
    val hyperOsHint: Boolean,
    val isTablet: Boolean
) {
    companion object {
        private const val TAG = "RomProfile"

        fun detect(context: Context): RomProfile {
            val region = detectRegion(context.packageManager)
            val miui = readProp("ro.miui.ui.version.name")
                ?: readProp("ro.mi.os.version.name")
            val hyper = !miui.isNullOrBlank() ||
                    !readProp("ro.mi.os.version.code").isNullOrBlank() ||
                    isPackagePresent(context.packageManager, "com.miui.securitycenter")
            val tablet = context.resources.configuration.smallestScreenWidthDp >= 600

            val profile = RomProfile(region, miui, hyper, tablet)
            AppLog.i(
                TAG,
                "detected region=$region miui=$miui hyper=$hyper tablet=$tablet " +
                        "sdk=${Build.VERSION.SDK_INT}"
            )
            return profile
        }

        private fun detectRegion(pm: PackageManager): RomRegion {
            val cnMarkers = listOf(
                "com.miui.msa.core",
                "com.xiaomi.market",
                "com.miui.home"
            )
            val globalMarkers = listOf(
                "com.miui.msa.global",
                "com.mi.global.market",
                "com.mi.android.globallauncher",
                "com.mi.globalbrowser"
            )
            val cnHits = cnMarkers.count { isPackagePresent(pm, it) }
            val globalHits = globalMarkers.count { isPackagePresent(pm, it) }
            return when {
                globalHits > cnHits -> RomRegion.GLOBAL
                cnHits > globalHits -> RomRegion.CN
                else -> RomRegion.UNKNOWN
            }
        }

        private fun isPackagePresent(pm: PackageManager, pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        private fun readProp(key: String): String? = try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Сортирует кандидатов пакетов: сначала типичные для региона.
     */
    fun preferPackages(candidates: Collection<String>): List<String> {
        val globalFirst = listOf(".global", "com.mi.global", "com.mi.android.global")
        val cnFirst = listOf("com.miui.", "com.xiaomi.", "com.android.thememanager")
        return candidates.distinct().sortedWith { a, b ->
            val scoreA = score(a, globalFirst, cnFirst)
            val scoreB = score(b, globalFirst, cnFirst)
            scoreB.compareTo(scoreA)
        }
    }

    private fun score(pkg: String, globalFirst: List<String>, cnFirst: List<String>): Int {
        val globalBoost = if (globalFirst.any { pkg.contains(it) }) 10 else 0
        val cnBoost = if (cnFirst.any { pkg.startsWith(it) || pkg.contains(it) }) 10 else 0
        return when (region) {
            RomRegion.GLOBAL -> globalBoost - cnBoost / 2
            RomRegion.CN -> cnBoost - globalBoost / 2
            RomRegion.UNKNOWN -> 0
        }
    }
}
