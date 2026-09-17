package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog

/**
 * Префильтр плана Simple Mode.
 *
 * В план попадают только шаги, которые имеют смысл на устройстве:
 * - установленные пакеты (`requiredPackages`, вариантные группы каталога);
 * - home-шаги только если лаунчер реально MIUI (plan-time через [resolveHomePackage]);
 * - `notif_*` исключаются при выключенной прозрачности уведомлений.
 *
 * Runtime-ветки `app_not_installed` и `home_not_miui` при этом не нужны:
 * размер плана = total на оверлее.
 */
object PlanBuilder {

    private const val TAG = "plan"

    /** Шаг плана: legacy-структура (навигация/пакеты) + семантика. */
    data class PlanStep(
        val step: SimpleSteps.Step,
        val semantic: SemanticCatalog.SemanticStep?
    ) {
        val id: String get() = step.id
    }

    /** Пакеты-кандидаты лаунчера MIUI (для plan-time home-skip). */
    private val HOME_PACKAGES = listOf(
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.miui.launcher",
        "com.mi.global.home"
    )

    private const val NOTIF_PREFIX = "notif_"

    /**
     * Собирает план прогона. Вызывается один раз перед фазой STEPS.
     *
     * @param notifTransparency включена ли прозрачность уведомлений (дефолт ON);
     *   при false шаги `notif_*` исключаются из плана.
     */
    fun build(
        context: Context,
        profile: RomProfile,
        notifTransparency: Boolean = true
    ): List<PlanStep> {
        SemanticCatalog.ensureLoaded(context)
        val installedHome = resolveHomePackage(context)
        val excluded = ArrayList<String>()
        val plan = ArrayList<PlanStep>(SimpleSteps.ALL.size)

        for (step in SimpleSteps.ALL) {
            val semantic = SemanticCatalog.step(step.id)

            if (!notifTransparency && step.id.startsWith(NOTIF_PREFIX)) {
                excluded.add("${step.id}:notif_disabled")
                continue
            }

            if (step.id == "home_suggestions") {
                val isMiuiHome = installedHome != null && HOME_PACKAGES.any {
                    it.equals(installedHome, ignoreCase = true)
                }
                if (!isMiuiHome) {
                    excluded.add("${step.id}:home_not_miui")
                    continue
                }
            }

            val packages = candidatePackages(context, step, profile)
            if (packages.isNotEmpty() && packages.none { ActivityScanner.isPackageVisible(context, it) }) {
                excluded.add("${step.id}:app_not_installed")
                continue
            }

            plan.add(PlanStep(step, semantic))
        }

        AppLog.i(
            TAG,
            "plan steps=${plan.size} excluded=${excluded.size} notif=$notifTransparency " +
                "region=${profile.regionCode} excludedIds=${excluded.joinToString(",")}"
        )
        return plan
    }

    /**
     * Пакет лаунчера по умолчанию (plan-time home-skip).
     * resolveActivity может быть отфильтрован package visibility — тогда
     * проверяем видимость известных HOME-пакетов (иначе home-шаг ложно выпадет).
     */
    fun resolveHomePackage(context: Context): String? {
        val resolved = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (resolved != null) return resolved
        return HOME_PACKAGES.firstOrNull { ActivityScanner.isPackageVisible(context, it) }
    }

    /** Пакеты шага: семантическая таблица + каталожные варианты + legacy requiredPackages. */
    private fun candidatePackages(
        context: Context,
        step: SimpleSteps.Step,
        profile: RomProfile
    ): List<String> {
        val catalog = runCatching {
            AdaptiveCatalog.packagesForStep(context, step.id, step.requiredPackages, profile)
        }.getOrDefault(emptyList())
        val semantic = SemanticCatalog.requiredPackages(step.id)
        return (semantic + catalog + step.requiredPackages).distinct()
    }
}

/**
 * Активный план прогона: общий для SimpleModeController (порядок шагов,
 * total на оверлее) и AdbEnablerService (резолв шага по индексу).
 */
object SimplePlan {

    private const val TAG = "plan"

    @Volatile
    private var steps: List<PlanBuilder.PlanStep> = emptyList()

    fun set(plan: List<PlanBuilder.PlanStep>) {
        steps = plan
        AppLog.i(TAG, "active steps=${plan.size}")
    }

    fun all(): List<PlanBuilder.PlanStep> = steps

    fun total(): Int = steps.size

    fun stepAt(index: Int): PlanBuilder.PlanStep? = steps.getOrNull(index)

    fun isActive(): Boolean = steps.isNotEmpty()

    fun reset() {
        steps = emptyList()
        AppLog.i(TAG, "active steps reset")
    }
}