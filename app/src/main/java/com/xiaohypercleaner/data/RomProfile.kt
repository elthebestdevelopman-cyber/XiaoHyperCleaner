package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.xiaohypercleaner.util.AppLog
import java.util.Locale

/**
 * Профиль прошивки и региона устройства Xiaomi / Poco / Redmi.
 *
 * Определяет:
 * 1. Системный регион (через ro.miui.region, ro.mi.os.region, Locale)
 * 2. Уровень оптимизации:
 *    - MAXIMUM: Индия (IN), Китай (CN) — максимальная нагрузка системными рекомендациями
 *    - STANDARD: Россия (RU), Индонезия (ID), GLOBAL и др.
 *    - PRE_OPTIMIZED_EEA: Европа / Великобритания (GDPR — настройки уже оптимизированы)
 * 3. Тип устройства: Смартфон vs Планшет (Xiaomi Pad, Redmi Pad)
 * 4. Версию оболочки: HyperOS 2, HyperOS 1, MIUI 14/13/12
 */
enum class RomRegion {
    CN,
    GLOBAL,
    UNKNOWN
}

enum class OptimizationScope {
    /** Максимальный уровень: агрессивные системные рекомендации (IN, CN) */
    MAXIMUM,
    /** Стандартный уровень: базовые системные рекомендации (RU, ID, GLOBAL) */
    STANDARD,
    /** Европейский регион (EEA/GDPR): система оптимизирована по умолчанию */
    PRE_OPTIMIZED_EEA
}

data class RomProfile(
    val region: RomRegion,
    val miuiVersion: String?,
    val hyperOsHint: Boolean,
    val isTablet: Boolean,
    val regionCode: String = when (region) {
        RomRegion.CN -> "CN"
        RomRegion.GLOBAL -> "GLOBAL"
        RomRegion.UNKNOWN -> "UNKNOWN"
    },
    val optimizationScope: OptimizationScope = when (region) {
        RomRegion.CN -> OptimizationScope.MAXIMUM
        else -> OptimizationScope.STANDARD
    },
    val androidSdk: Int = Build.VERSION.SDK_INT,
    val locale: Locale = Locale.getDefault()
) {
    companion object {
        private const val TAG = "RomProfile"

        /** Список регионов Европейской экономической зоны (EEA) / GDPR */
        private val EEA_REGIONS = setOf(
            "EEA", "EU", "UK", "GB", "DE", "FR", "IT", "ES", "PL", "NL", "SE",
            "PT", "RO", "BE", "AT", "GR", "CZ", "DK", "FI", "IE", "BG", "HR",
            "SK", "HU", "LT", "SI", "LV", "EE", "CY", "LU", "MT"
        )

        fun detect(context: Context): RomProfile {
            val pm = context.packageManager
            val regionCategory = detectRegionCategory(pm)

            // Чтение системного региона прошивки
            val sysRegionProp = readProp("ro.miui.region")
                ?: readProp("ro.mi.os.region")
                ?: readProp("ro.product.mod_device")?.substringAfterLast("_")?.take(2)
                ?: Locale.getDefault().country.uppercase()

            val regionCode = sysRegionProp.trim().uppercase()

            val scope = when {
                regionCode == "IN" || regionCode == "CN" -> OptimizationScope.MAXIMUM
                EEA_REGIONS.contains(regionCode) -> OptimizationScope.PRE_OPTIMIZED_EEA
                else -> OptimizationScope.STANDARD
            }

            val miui = readProp("ro.miui.ui.version.name")
                ?: readProp("ro.mi.os.version.name")
            val hyper = !readProp("ro.mi.os.version.code").isNullOrBlank() ||
                readProp("ro.mi.os.version.name")?.contains("1.") == true ||
                readProp("ro.mi.os.version.name")?.contains("2.") == true ||
                isPackagePresent(pm, "com.miui.securitycore")

            val config = context.resources.configuration
            val characteristics = readProp("ro.build.characteristics").orEmpty()
            val isTablet = config.smallestScreenWidthDp >= 600 ||
                config.screenWidthDp >= 600 ||
                characteristics.contains("tablet")

            val profile = RomProfile(
                region = regionCategory,
                regionCode = regionCode,
                optimizationScope = scope,
                miuiVersion = miui,
                hyperOsHint = hyper,
                isTablet = isTablet,
                androidSdk = Build.VERSION.SDK_INT,
                locale = Locale.getDefault()
            )

            AppLog.i(
                TAG,
                "Pre-Scan: region=$regionCategory ($regionCode) scope=$scope " +
                    "miui=$miui hyper=$hyper tablet=$isTablet sdk=${Build.VERSION.SDK_INT} " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}"
            )
            return profile
        }

        private fun detectRegionCategory(pm: PackageManager): RomRegion {
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
                else -> RomRegion.GLOBAL
            }
        }

        private fun isPackagePresent(pm: PackageManager, pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }

        fun readProp(key: String): String? = try {
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
            RomRegion.UNKNOWN -> globalBoost
        }
    }
}
