package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.xiaohypercleaner.util.AppLog

/**
 * Навигатор прямых Intent'ов для открытия экранов настроек Xiaomi/MIUI/HyperOS.
 *
 * ПУНКТ 3 ОПТИМИЗАЦИИ:
 * Заменяет медленную навигацию через UI (drill-down) на прямые Intent'ы,
 * что экономит 2-5 секунд на каждый шаг.
 *
 * АРХИТЕКТУРА:
 * - buildIntentsForStep() — возвращает список Intent'ов в порядке приоритета
 * - Каждый Intent проверяется через resolveActivity() перед возвратом
 * - Fallback-цепочка: специфичный MIUI → общий Android → Settings.ACTION_SETTINGS
 * - Региональная маршрутизация: CN/Global/HyperOS имеют разные Activity
 *
 * ПРИОРИТЕТ INTENT'ОВ:
 * 1. Специфичный MIUI Intent (miui.intent.action.*) — самый быстрый
 * 2. Общий Android Intent (android.settings.*) — работает на всех прошивках
 * 3. Прямой запуск приложения (ACTION_MAIN) — fallback для приложений
 * 4. Общие настройки (Settings.ACTION_SETTINGS) — последний fallback
 *
 * БЕЗОПАСНОСТЬ:
 * - Все Intent'ы проверяются через resolveActivity() — не запускаем несуществующие
 * - FLAG_ACTIVITY_NEW_TASK добавляется автоматически
 * - FLAG_ACTIVITY_CLEAR_TOP для предотвращения дублирования стека
 */
object DirectIntentNavigator {

    private const val TAG = "DirectIntentNav"

    /** Кэш результатов резолвинга для производительности */
    private val intentCache = mutableMapOf<String, List<Intent>>()

    /**
     * Строит список Intent'ов для открытия экрана настроек указанного шага.
     *
     * @param context Контекст приложения
     * @param step Шаг из SimpleSteps.ALL
     * @param resolvedPackage Резолвленный пакет (из AdaptiveCatalog)
     * @param profile Профиль прошивки (регион, версия, тип)
     * @return Список Intent'ов в порядке приоритета (первый — лучший)
     */
    fun buildIntentsForStep(
        context: Context,
        step: SimpleSteps.Step,
        resolvedPackage: String?,
        profile: RomProfile
    ): List<Intent> {
        // Проверяем кэш
        val cacheKey = "${step.id}_${profile.regionCode}"
        intentCache[cacheKey]?.let { cached ->
            AppLog.d(TAG, "Using cached intents for ${step.id}")
            return cached
        }

        // Строим Intent'ы по ID шага. Явная launcher-компонента из PackageManager
        // идёт первой: неявный `MAIN + CATEGORY_LAUNCHER` не резолвится у части
        // MIUI-приложений (GetApps — `com.xiaomi.mipicks`), и шаг оставался без
        // рабочей точки входа (прогон rmu8qhjhi: бурение шло по сплэшу магазина).
        val primary = (resolvedPackage ?: step.launchPackage)
            ?.let { pmLauncherIntent(context, it) }
        val intents =
            (listOfNotNull(primary) + buildIntentsById(context, step, resolvedPackage, profile))
                .filter { isIntentAvailable(context, it) }

        // Если ничего не нашли — fallback на общие настройки
        val result = if (intents.isEmpty()) {
            AppLog.w(TAG, "No direct intents for ${step.id}, using fallback")
            listOf(settingsIntent()).filter { isIntentAvailable(context, it) }
        } else {
            intents
        }

        // Кэшируем результат
        intentCache[cacheKey] = result
        AppLog.i(TAG, "Built ${result.size} intents for ${step.id}")

        return result
    }

    /**
     * Строит Intent'ы по ID шага.
     * Порядок: специфичный MIUI → общий Android → прямой запуск → fallback.
     */
    private fun buildIntentsById(
        context: Context,
        step: SimpleSteps.Step,
        resolvedPackage: String?,
        profile: RomProfile
    ): List<Intent> {
        val intents = mutableListOf<Intent>()

        when (step.id) {
            // ═══════════════════════════════════════════════════════════
            // БЛОК А: СИСТЕМНЫЕ НАСТРОЙКИ
            // ═══════════════════════════════════════════════════════════

            "msa" -> {
                // MSA — отзыв доступа к личным данным (Authorization & revocation).
                // MIUI-экран «Разрешения» приложения (APP_PERM_EDITOR) первичен:
                // ACTION_PRIVACY_SETTINGS на Global 13 уводил на «О приложении»/Privacy.
                val msaPkg = resolvedPackage ?: "com.miui.msa.global"
                intents.addAll(
                    listOf(
                        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                            putExtra("extra_package_name", msaPkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                        miuiIntent("miui.intent.action.AD_SERVICES_SETTINGS"),
                        settingsIntent(Settings.ACTION_PRIVACY_SETTINGS),
                        miuiIntent("miui.intent.action.PRIVACY_SETTINGS"),
                        appDetailsIntent(msaPkg),
                        appDetailsIntent("com.miui.msa.global"),
                        appDetailsIntent("com.miui.msa.core")
                    )
                )
            }

            "sys_recommendations" -> {
                // Системные рекомендации — через поиск настроек или напрямую.
                // На Global RU рекомендации/реклама живут в Конфиденциальность -> Реклама.
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.SYSTEM_RECOMMENDATIONS"),
                        settingsIntent("android.settings.SYSTEM_RECOMMENDATIONS_SETTINGS"),
                        settingsIntent(Settings.ACTION_PRIVACY_SETTINGS),
                        settingsIntent(Settings.ACTION_SETTINGS)
                    )
                )
            }

            "ads_personalization" -> {
                // Персонализация рекламы.
                // На MIUI Global 13 «Рекламные службы» открывает ACTION_PRIVACY_SETTINGS.
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.AD_SERVICES_SETTINGS"),
                        settingsIntent(Settings.ACTION_PRIVACY_SETTINGS),
                        miuiIntent("miui.intent.action.PRIVACY_SETTINGS"),
                        settingsIntent("android.settings.AD_SERVICES_SETTINGS"),
                        appDetailsIntent("com.miui.systemAdSolution")
                    )
                )
            }

            "ux_program" -> {
                // Программа улучшения качества
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.USER_EXPERIENCE_PROGRAM"),
                        miuiIntent("miui.intent.action.DIAGNOSTIC_SETTINGS"),
                        settingsIntent("android.settings.USER_EXPERIENCE_PROGRAM"),
                        settingsIntent(Settings.ACTION_PRIVACY_SETTINGS)
                    )
                )
            }

            "carousel" -> {
                // Карусель обоев
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.WALLPAPER_CAROUSEL"),
                        settingsIntent("android.settings.LOCK_SCREEN_SETTINGS"),
                        settingsIntent(Settings.ACTION_SETTINGS)
                    )
                )
            }

            "home_suggestions" -> {
                // Предложения на рабочем столе
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.HOME_SETTINGS"),
                        settingsIntent("android.settings.HOME_SETTINGS"),
                        settingsIntent(Settings.ACTION_HOME_SETTINGS),
                        settingsIntent(Settings.ACTION_SETTINGS)
                    )
                )
            }

            // ═══════════════════════════════════════════════════════════
            // БЛОК Б: ВНУТРИ ПРИЛОЖЕНИЙ
            // ═══════════════════════════════════════════════════════════

            "browser_sys" -> {
                // Mi Браузер — настройки рекомендаций
                val pkg = resolvedPackage ?: "com.mi.globalbrowser"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.mi.globalbrowser"),
                        launchIntent("com.android.browser"),
                        launchIntent("com.miui.browser")
                    )
                )
            }

            "music_sys" -> {
                // Музыка — настройки рекомендаций
                val pkg = resolvedPackage ?: "com.miui.player"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.miui.player"),
                        launchIntent("com.miui.music"),
                        launchIntent("com.mi.music")
                    )
                )
            }

            "messages_sys" -> {
                // Сообщения — персонализация. Хардкод-фолбэка на com.miui.mms нет:
                // целевой пакет приходит из requiredPackages/каталога (resolvedPackage).
                val pkg = resolvedPackage ?: "com.android.mms"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.android.mms")
                    )
                )
            }

            "security_sys" -> {
                // Безопасность — рекомендации
                val pkg = resolvedPackage ?: "com.miui.securitycenter"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.miui.securitycenter"),
                        launchIntent("com.miui.securitycore")
                    )
                )
            }

            "cleaner" -> {
                // Очистка — рекомендации
                val pkg = resolvedPackage ?: "com.miui.securitycenter"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.miui.securitycenter"),
                        launchIntent("com.miui.cleaner")
                    )
                )
            }

            "downloads" -> {
                // Загрузки — рекомендации
                val pkg = resolvedPackage ?: "com.android.providers.downloads.ui"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.android.providers.downloads.ui"),
                        launchIntent("com.miui.android.downloads")
                    )
                )
            }

            "themes" -> {
                // Темы — рекомендации
                val pkg = resolvedPackage ?: "com.android.thememanager"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.android.thememanager"),
                        launchIntent("com.miui.thememanager"),
                        launchIntent("com.mi.thememanager")
                    )
                )
            }

            "getapps" -> {
                // GetApps — рекомендации (mipicks = фактический пакет магазина на Global)
                val pkg = resolvedPackage ?: "com.xiaomi.market"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.xiaomi.mipicks"),
                        launchIntent("com.xiaomi.market"),
                        launchIntent("com.miui.market"),
                        launchIntent("com.mi.global.market")
                    )
                )
            }

            "mivideo" -> {
                // Mi Видео — рекомендации
                val pkg = resolvedPackage ?: "com.miui.videoplayer"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.miui.videoplayer"),
                        launchIntent("com.miui.video"),
                        launchIntent("com.mi.global.video")
                    )
                )
            }

            "shareme" -> {
                // ShareMe — конфиденциальность
                val pkg = resolvedPackage ?: "com.xiaomi.midrop"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.xiaomi.midrop"),
                        launchIntent("com.mi.android.globalshareme")
                    )
                )
            }

            "filemanager" -> {
                // Проводник: вариант ОС ведёт через сам Проводник (☰ → Настройки →
                // Информация), CLEAR_DATA — фолбэк. Поэтому запуск приложения первичен,
                // «Сведения о приложении» — только фолбэк (прогон rmu8lzcu9: шаг уходил
                // в App Info и падал на диалоге).
                val main = resolvedPackage ?: "com.mi.android.globalFileexplorer"
                intents.addAll(
                    listOf(
                        launchIntent(main),
                        appDetailsIntent(main),
                        appDetailsIntent("com.mi.android.globalFileexplorer"),
                        appDetailsIntent("com.android.fileexplorer"),
                        appDetailsIntent("com.mi.android.fileexplorer")
                    )
                )
            }

            // ═══════════════════════════════════════════════════════════
            // БЛОК В: ЛЕНТА ВИДЖЕТОВ
            // ═══════════════════════════════════════════════════════════

            "appvault_services", "appvault_about" -> {
                // Лента виджетов — предложения / услуги (globalminusscreen = Global пакет)
                val pkg = resolvedPackage ?: "com.miui.personalassistant"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.mi.android.globalminusscreen"),
                        launchIntent("com.miui.personalassistant"),
                        launchIntent("com.mi.android.global.personalassistant"),
                        launchIntent("com.android.personalassistant")
                    )
                )
            }

            // ═══════════════════════════════════════════════════════════
            // БЛОК Г: УВЕДОМЛЕНИЯ
            // ═══════════════════════════════════════════════════════════

            "notif_msa" -> {
                // Уведомления MSA
                val pkg = resolvedPackage ?: "com.miui.msa.global"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.miui.msa.global"),
                        notificationsIntent("com.miui.msa.core"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_gamecenter" -> {
                // Уведомления игрового центра
                val pkg = resolvedPackage ?: "com.xiaomi.glgm"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.xiaomi.glgm"),
                        notificationsIntent("com.xiaomi.gamecenter"),
                        notificationsIntent("com.miui.gamecenter"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_appvault" -> {
                // Уведомления ленты виджетов
                val pkg = resolvedPackage ?: "com.miui.personalassistant"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.mi.android.globalminusscreen"),
                        notificationsIntent("com.miui.personalassistant"),
                        notificationsIntent("com.mi.android.global.personalassistant"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_themes" -> {
                // Уведомления тем
                val pkg = resolvedPackage ?: "com.android.thememanager"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.android.thememanager"),
                        notificationsIntent("com.miui.thememanager"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_getapps" -> {
                // Уведомления GetApps (mipicks = фактический пакет на Global)
                val pkg = resolvedPackage ?: "com.xiaomi.market"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.xiaomi.mipicks"),
                        notificationsIntent("com.xiaomi.market"),
                        notificationsIntent("com.miui.market"),
                        notificationsIntent("com.mi.global.market"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_browser" -> {
                // Уведомления браузера
                val pkg = resolvedPackage ?: "com.mi.globalbrowser"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.mi.globalbrowser"),
                        notificationsIntent("com.android.browser"),
                        notificationsIntent("com.miui.browser"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            "notif_mivideo" -> {
                // Уведомления Mi Видео
                val pkg = resolvedPackage ?: "com.miui.videoplayer"
                intents.addAll(
                    listOf(
                        notificationsIntent(pkg),
                        notificationsIntent("com.miui.videoplayer"),
                        notificationsIntent("com.miui.video"),
                        notificationsIntent("com.mi.global.video"),
                        appDetailsIntent(pkg)
                    )
                )
            }

            else -> {
                // Неизвестный шаг — fallback на общие настройки
                AppLog.w(TAG, "Unknown step ID: ${step.id}, using fallback")
                intents.add(settingsIntent(Settings.ACTION_SETTINGS))
            }
        }

        return intents
    }

    // ═══════════════════════════════════════════════════════════════
    // Хелперы создания Intent'ов
    // ═══════════════════════════════════════════════════════════════

    /**
     * Создаёт Intent с action MIUI.
     * Используется для специфичных MIUI экранов настроек.
     */
    private fun miuiIntent(action: String): Intent {
        return Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Создаёт Intent с action Android Settings.
     * Используется для общих Android экранов настроек.
     */
    private fun settingsIntent(action: String = Settings.ACTION_SETTINGS): Intent {
        return Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Launcher-интент с явной компонентой от PackageManager.
     *
     * Неявный `MAIN + CATEGORY_LAUNCHER` с `setPackage` у части MIUI-приложений не
     * резолвится вообще (`No Activity found to handle Intent` для GetApps/mipicks),
     * поэтому точку входа берём у самого PackageManager. `null` — launcher-активности
     * у пакета нет (фоновые сервисы вроде MSA): такой пакет в этой цепочке бесполезен.
     */
    private fun pmLauncherIntent(context: Context, packageName: String): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
            .getOrNull()
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }

    /**
     * Создаёт Intent для запуска приложения.
     * Используется для шагов внутри приложений (Браузер, Музыка, Темы и т.д.).
     */
    private fun launchIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            // CLEAR_TASK: MIUI восстанавливает последний экран приложения (ShareMe —
            // мастер отправки, Темы — старую вкладку). Чистая задача даёт корневой экран.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
    }

    /**
     * Создаёт Intent для открытия экрана деталей приложения.
     * Используется для шагов CLEAR_DATA_DECLINE и уведомлений.
     */
    private fun appDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Создаёт Intent для открытия экрана уведомлений приложения.
     * Используется для шагов notif_*.
     */
    private fun notificationsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Проверка доступности Intent'а
    // ═══════════════════════════════════════════════════════════════

    /**
     * Проверяет, есть ли Activity для обработки Intent'а.
     * Предотвращает ActivityNotFoundException при запуске.
     *
     * @param context Контекст приложения
     * @param intent Intent для проверки
     * @return true, если есть хотя бы одно Activity для обработки
     */
    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return try {
            val pm = context.packageManager
            val launcherPkg = launcherPackage(intent)
            val available = if (launcherPkg != null) {
                // Launcher-интенты не объявляют CATEGORY_DEFAULT, поэтому
                // MATCH_DEFAULT_ONLY их не находит («Intent not available» для
                // mipicks) и шаг-приложение терял свою точку входа. Доступность
                // определяет PackageManager, а не фильтр по CATEGORY_DEFAULT.
                pm.getLaunchIntentForPackage(launcherPkg) != null ||
                    pm.queryIntentActivities(intent, 0).isNotEmpty()
            } else {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            }
            if (!available) {
                AppLog.d(
                    TAG,
                    "Intent not available: ${intent.action} / ${intent.`package`} / ${intent.data}"
                )
            }
            available
        } catch (e: Exception) {
            AppLog.w(TAG, "isIntentAvailable failed: ${e.message}")
            false
        }
    }

    /** Пакет интента, если это запуск приложения (`MAIN` + `LAUNCHER` + `setPackage`). */
    private fun launcherPackage(intent: Intent): String? {
        val isLauncherIntent = intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        return if (isLauncherIntent) intent.`package` else null
    }

    /**
     * Очищает кэш Intent'ов.
     * Вызывать при смене языка/региона или обновлении системы.
     */
    fun clearCache() {
        intentCache.clear()
        AppLog.i(TAG, "Intent cache cleared")
    }
}