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
 * 4. Семейство и версию оболочки: MIUI 12/12.5/13/14, HyperOS 1/2/3 ([RomFamily], [uiVersion])
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

/**
 * Семейство оболочки. Определяется по системным свойствам
 * (`ro.miui.ui.version.name` → MIUI, `ro.mi.os.version.name` → HyperOS) и
 * префиксу `ro.build.version.incremental` (V… / OS…). От семейства и версии
 * ([RomProfile.uiVersion]) зависит выбор варианта шага в семантическом каталоге.
 */
enum class RomFamily {
    MIUI,
    HYPEROS,
    UNKNOWN
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
    val locale: Locale = Locale.getDefault(),
    val family: RomFamily = RomFamily.UNKNOWN,
    val uiVersion: String? = null
) {
    /** Числовое значение версии оболочки для сравнения с диапазоном варианта (13 → 13.0). */
    val uiOrdinal: Double get() = parseUiOrdinal(uiVersion)

    /**
     * Совпадает ли профиль с диапазоном варианта каталога.
     *
     * UNKNOWN-семейство (свойства прошивки недоступны) совпадает с любым
     * вариантом: итоговое решение принимает каталог по приоритету вариантов.
     */
    fun matchesOs(family: RomFamily, uiMin: Double, uiMax: Double): Boolean {
        if (family != RomFamily.UNKNOWN && this.family != RomFamily.UNKNOWN && this.family != family) {
            return false
        }
        val ui = uiOrdinal
        if (ui <= 0.0) return false
        return ui >= uiMin - UI_EPS && ui <= uiMax + UI_EPS
    }

    companion object {
        private const val TAG = "RomProfile"
        private const val UI_EPS = 0.001

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

            val miuiVersionProp = readProp("ro.miui.ui.version.name")
            val hyperOsVersionProp = readProp("ro.mi.os.version.name")
            val incremental = Build.VERSION.INCREMENTAL
            val miui = miuiVersionProp ?: hyperOsVersionProp
            // HyperOS определяется ТОЛЬКО по системным свойствам ro.mi.os.*.
            // com.miui.securitycore присутствует и на MIUI 12/13 — по нему нельзя судить
            // о HyperOS (ложное срабатывание ломало таймауты и маршруты).
            val osVersionName = hyperOsVersionProp
            val legacyHyperHint = !readProp("ro.mi.os.version.code").isNullOrBlank() ||
                osVersionName != null && (osVersionName.contains("1.") || osVersionName.contains("2."))

            // Семейство/версия: свойство → префикс incremental (V12x/V13x/V14x, OS1.x/OS2.x/OS3.x).
            val family = detectFamily(miuiVersionProp, hyperOsVersionProp, incremental)
            val uiVersion = resolveUiVersion(family, miuiVersionProp, hyperOsVersionProp, incremental)
            val hyper = family == RomFamily.HYPEROS || legacyHyperHint
            val romSource = when {
                hyperOsVersionProp != null -> "prop:ro.mi.os"
                miuiVersionProp != null -> "prop:ro.miui.ui"
                else -> "incremental"
            }

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
                locale = Locale.getDefault(),
                family = family,
                uiVersion = uiVersion
            )

            AppLog.i(TAG, "rom: family=$family ui=${uiVersion ?: "unknown"} source=$romSource")
            AppLog.i(
                TAG,
                "Pre-Scan: region=$regionCategory ($regionCode) scope=$scope " +
                    "miui=$miui hyper=$hyper tablet=$isTablet sdk=${Build.VERSION.SDK_INT} " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL}"
            )
            return profile
        }

        /** Семейство оболочки: HyperOS-свойство приоритетнее MIUI-свойства. */
        internal fun detectFamily(
            miuiUiVersion: String?,
            hyperOsVersion: String?,
            incremental: String?
        ): RomFamily {
            val inc = incremental?.trim()?.uppercase(Locale.ROOT).orEmpty()
            // Префикс incremental авторитетен: HyperOS-устройства продолжают отдавать
            // MIUI-свойство (V816), поэтому OS-префикс сборки проверяем первым.
            if (INC_OS.containsMatchIn(inc.take(4))) return RomFamily.HYPEROS
            if (!hyperOsVersion.isNullOrBlank()) return RomFamily.HYPEROS
            if (!miuiUiVersion.isNullOrBlank()) return RomFamily.MIUI
            if (INC_V.containsMatchIn(inc.take(4))) return RomFamily.MIUI
            return RomFamily.UNKNOWN
        }

        /** Версия оболочки в виде "12", "12.5", "13", "14", "1", "2", "3". */
        internal fun resolveUiVersion(
            family: RomFamily,
            miuiUiVersion: String?,
            hyperOsVersion: String?,
            incremental: String?
        ): String? {
            val primary = when (family) {
                RomFamily.HYPEROS -> hyperOsVersion
                RomFamily.MIUI -> miuiUiVersion
                RomFamily.UNKNOWN -> hyperOsVersion ?: miuiUiVersion
            }
            uiVersionLabel(primary)?.let { return it }
            return uiVersionLabel(incremental)
        }

        /** "V130" → "13", "V12.5.4" → "12.5", "OS2.0.4.0" → "2", "V13.0.5.0.SJURUXM" → "13". */
        internal fun uiVersionLabel(raw: String?): String? {
            val trimmed = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            val body = trimmed.removePrefix("OS").removePrefix("V").trim()
            val digits = body.takeWhile { it.isDigit() || it == '.' }
            if (digits.isEmpty()) return null
            val parts = digits.split('.').filter { it.isNotEmpty() }
            val major = parts.firstOrNull() ?: return null
            val majorValue = major.take(2).trimStart('0')
            if (majorValue.isEmpty()) return null
            // Отсеиваем коды вида V816 (81.6 — не версия оболочки, а код сборки).
            val majorNumber = majorValue.toIntOrNull() ?: return null
            if (majorNumber > 20) return null
            // "130" (без точки) → major 13 + minor 0; "12" → 12 без минора.
            val minor = when {
                parts.size > 1 -> parts[1].take(1).trimStart('0')
                major.length > 2 -> major.substring(2, 3).trimStart('0')
                else -> ""
            }
            return if (minor.isEmpty()) majorValue else "$majorValue.$minor"
        }

        /** Числовое значение версии для диапазонов вариантов: "13" → 13.0, "12.5" → 12.5. */
        internal fun parseUiOrdinal(ui: String?): Double {
            val label = ui ?: return 0.0
            val parts = label.split('.')
            val major = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
            val minor = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            return major + (minor.coerceIn(0.0, 9.0) / 10.0)
        }

        private val INC_V = Regex("^V\\d")
        private val INC_OS = Regex("^OS\\d")

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
