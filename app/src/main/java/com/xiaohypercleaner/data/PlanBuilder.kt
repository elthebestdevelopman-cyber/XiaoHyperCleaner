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

    /** Шаг рекомендаций в папках рабочего стола: вход — лаунчер, а не Настройки. */
    private const val FOLDER_STEP = "folder_recommendations"

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
        // Варианты шагов под версию прошивки выбираются один раз на прогон:
        // дальше accessors каталога отдают эффективные (вариантные) значения.
        SemanticCatalog.selectVariant(profile)
        val installedHome = resolveHomePackage(context)
        val neverTouch = SemanticCatalog.neverTouchPackages()
        val excluded = ArrayList<String>()
        val plan = ArrayList<PlanStep>(SimpleSteps.ALL.size)

        for (step in SimpleSteps.ALL) {
            val semantic = SemanticCatalog.step(step.id)

            if (!notifTransparency && step.id.startsWith(NOTIF_PREFIX)) {
                excluded.add("${step.id}:notif_disabled")
                continue
            }

            if (step.id == "home_suggestions" || step.id == FOLDER_STEP) {
                val isMiuiHome = installedHome != null && HOME_PACKAGES.any {
                    it.equals(installedHome, ignoreCase = true)
                }
                if (!isMiuiHome) {
                    excluded.add("${step.id}:home_not_miui")
                    continue
                }
            }

            val packages = candidatePackages(context, step, profile)

            // Красный список: destructive-операция над neverTouch-пакетом не создаётся
            // никогда. Наличие пакета в requiredPackages/launchPackage само по себе
            // не основание для исключения: UI-тумблеры внутри его экранов разрешены.
            if (isForbiddenDestructive(
                    actionType = step.actionType,
                    destructiveAction = semantic?.destructiveAction,
                    packages = packages,
                    neverTouch = neverTouch
                )
            ) {
                excluded.add("${step.id}:never_touch")
                continue
            }

            if (packages.isNotEmpty() && packages.none { ActivityScanner.isPackageVisible(context, it) }) {
                excluded.add("${step.id}:app_not_installed")
                continue
            }

            plan.add(PlanStep(step, semantic))
        }

        AppLog.i(
            TAG,
            "plan steps=${plan.size} excluded=${excluded.size} notif=$notifTransparency " +
                "region=${profile.regionCode} rom=${profile.family}/${profile.uiVersion ?: "?"} " +
                "excludedIds=${excluded.joinToString(",")}"
        )
        return plan
    }

    /** Destructive-действия, запрещённые для пакетов красного списка. */
    internal val DESTRUCTIVE_ACTIONS: Set<String> = setOf("disable", "uninstall", "clear_data", "force_stop")

    /**
     * Гейт красного списка [SemanticCatalog.neverTouchPackages]: шаг исключается,
     * только если он выполняет destructive-операцию (disable/uninstall/clear data/
     * force-stop) над пакетом из списка. Открытие UI и UI-тумблеры разрешены.
     *
     * Чистая функция — тестируется без Android.
     */
    internal fun isForbiddenDestructive(
        actionType: SimpleSteps.ActionType,
        destructiveAction: String?,
        packages: Collection<String>,
        neverTouch: Set<String>
    ): Boolean {
        val action = destructiveAction?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: if (actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE) "clear_data" else null
        if (action == null || action !in DESTRUCTIVE_ACTIONS) return false
        return packages.any { it in neverTouch }
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