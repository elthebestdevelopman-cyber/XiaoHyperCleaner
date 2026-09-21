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

            "google_diagnostics" -> {
                // Google «Использование и диагностика»: GMS-активность не exported
                // (`am start -a com.google.android.gms.usagereporting.GOOGLE_SETTINGS` →
                // permission denial), поэтому экран открываем через системные Настройки —
                // Privacy Dashboard, где этот пункт и находится.
                intents.addAll(
                    listOf(
                        settingsIntent(Settings.ACTION_PRIVACY_SETTINGS),
                        settingsIntent(Settings.ACTION_SETTINGS)
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
                // Безопасность — рекомендации: целевой экран («РЕКОМЕНДАЦИИ → Получать
                // рекомендации», «Загружать только по Wi-Fi») — это
                // com.miui.securityscan.ui.settings.SettingsActivity. Он объявлен с действием
                // miui.intent.action.APP_SETTINGS (дамп sec_settings_gear) — тем же, которым
                // ходит путь «Настройки → Приложения → Системные приложения», поэтому drill
                // «гайка» больше не обязателен.
                intents.addAll(securitySettingsIntents())
                intents.addAll(
                    listOf(
                        launchIntent("com.miui.securitycenter"),
                        launchIntent("com.miui.securitycore")
                    )
                )
            }

            "cleaner" -> {
                // Очистка — рекомендации: СВОЙ экран в СВОЁМ пакете
                // (com.miui.cleaner/com.miui.optimizecenter.settings.SettingsActivity,
                // «Настройки очистки», блок «РЕКОМЕНДАЦИИ»; дамп sec_cleaner). Прежний
                // resolvedPackage = com.miui.securitycenter вёл в Безопасность — чужой экран
                // с теми же строками (прогон rmua2sd7x: cleaner упал на маркерах чужого
                // экрана, security_sys искал тумблер там, где его нет).
                intents.addAll(cleanerSettingsIntents())
                intents.addAll(
                    listOf(
                        launchIntent("com.miui.cleaner"),
                        launchIntent("com.miui.securitycenter"),
                        launchIntent("com.miui.securitycore")
                    )
                )
            }

            "downloads" -> {
                // Загрузки — рекомендации. Экран настроек («Получать рекомендации»,
                // «Отзыв согласия»; дамп dl_settings) открывается из ⋮ → «Настройки»,
                // поэтому точкой входа служит сам список Загрузок: неявный
                // VIEW_DOWNLOADS даёт диалог выбора («Загрузки» / «Файлы»), а с явным
                // пакетом и компонентой резолвится однозначно.
                intents.addAll(downloadListIntents())
                val pkg = resolvedPackage ?: "com.android.providers.downloads.ui"
                intents.addAll(
                    listOf(
                        launchIntent(pkg),
                        launchIntent("com.miui.android.downloads")
                    )
                )
            }

            "home_suggestions" -> {
                // Лента виджетов (App Vault) — настройки лаунчера. У POCO Launcher
                // (com.mi.android.globallauncher, дамп home_settings_desktop) это
                // «Рабочий стол» → «Включить Ленту виджетов»; в системных Настройках
                // пункта «Рабочий стол» на POCO нет, поэтому входим прямо в настройки
                // лаунчера. Путь MIUI Home остаётся через drill в каталоге.
                intents.addAll(launcherSettingsIntents())
                intents.addAll(
                    listOf(
                        miuiIntent("miui.intent.action.HOME_SETTINGS"),
                        settingsIntent(Settings.ACTION_HOME_SETTINGS)
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
                // GetApps — рекомендации. Экран «Конфиденциальность» («гайка» в профиле)
                // открывается напрямую экспортированной активностью (дамп устройства:
                // com.xiaomi.market.ui.PrivacyPreferenceFragmentActivity, заголовок
                // «Конфиденциальность», тумблер «Персональные рекомендации»), поэтому
                // бурение «Профиль → Настройки → Конфиденциальность» не требуется.
                // Первый узел «Настройки» в профиле ведёт на нативный MarketPreferenceActivity,
                // где «Конфиденциальности» нет вовсе (прогон rmua2sd7x: drill_failed).
                intents.add(
                    explicitActivity(
                        "com.xiaomi.mipicks",
                        "com.xiaomi.market.ui.PrivacyPreferenceFragmentActivity"
                    )
                )
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
     * Явная активность-экран настроек приложения (например, «Конфиденциальность» GetApps):
     * проверяется через PackageManager и ставится первым интентом, когда drill по UI
     * упирается в неоднозначные «Настройки» внутри приложения.
     */
    internal fun explicitActivity(packageName: String, className: String): Intent =
        Intent().setComponent(android.content.ComponentName(packageName, className)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Экран «Настройки безопасности» (`com.miui.securitycenter`): точка входа — действие
     * `APP_SETTINGS` (по нему же открывается из системных Настроек → Приложения →
     * Системные приложения), плюс спец-действие и явная активность как страховка.
     */
    internal fun securitySettingsIntents(): List<Intent> = listOf(
        // Внимание: `miui.intent.action.APP_SETTINGS` из фильтра этой активности НЕ
        // резолвится неявным интентом (в фильтре нет CATEGORY_DEFAULT — проверено
        // `am start -a` на устройстве: "unable to resolve"). Пригодны спец-действие и
        // явная компонента.
        actionIntent("com.miui.securitycenter.action.SECURITYCENTER_SETTINGS", "com.miui.securitycenter"),
        explicitActivity(
            "com.miui.securitycenter",
            "com.miui.securityscan.ui.settings.SettingsActivity"
        )
    )

    /**
     * Экран «Настройки очистки» (`com.miui.cleaner`): действие
     * `GARBAGE_CLEANUP_SETTINGS` + явная активность. Отдельный пакет — отдельный тумблер
     * «Получать рекомендации» (одноимённая строка есть и в Безопасности).
     */
    internal fun cleanerSettingsIntents(): List<Intent> = listOf(
        actionIntent("com.miui.securitycenter.action.GARBAGE_CLEANUP_SETTINGS", "com.miui.cleaner"),
        explicitActivity("com.miui.cleaner", "com.miui.optimizecenter.settings.SettingsActivity")
    )

    /** Intent по действию с явным пакетом (без MAIN/LAUNCHER). */
    private fun actionIntent(action: String, packageName: String): Intent =
        Intent(action).setPackage(packageName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Список Загрузок (`com.android.providers.downloads.ui`): неявный VIEW_DOWNLOADS
     * показывает диалог «Что использовать?» («Загрузки» / «Файлы», дамп resolver_now),
     * поэтому пакет задан явно, а компонента `DownloadList` — страховка.
     */
    internal fun downloadListIntents(): List<Intent> = listOf(
        actionIntent("android.intent.action.VIEW_DOWNLOADS", "com.android.providers.downloads.ui"),
        explicitActivity(
            "com.android.providers.downloads.ui",
            "com.android.providers.downloads.ui.DownloadList"
        )
    )

    /**
     * Настройки лаунчера: у POCO Launcher (`com.mi.android.globallauncher`, дамп
     * home_settings_desktop) — действие `com.mi.android.globallauncher.Setting` и
     * `HomeSettingsActivity`; у MIUI Home — та же активность в `com.miui.home`.
     * Пункта «Рабочий стол» в системных Настройках на POCO нет.
     */
    internal fun launcherSettingsIntents(): List<Intent> = listOf(
        actionIntent("com.mi.android.globallauncher.Setting", "com.mi.android.globallauncher"),
        explicitActivity(
            "com.mi.android.globallauncher",
            "com.miui.home.settings.HomeSettingsActivity"
        ),
        explicitActivity("com.miui.home", "com.miui.home.settings.HomeSettingsActivity")
    )

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
            val available = when {
                // Явная компонента-экран настроек приложения: резолвится без фильтров,
                // CATEGORY_DEFAULT у таких активностей нет (GetApps: PrivacyPreference…),
                // и MATCH_DEFAULT_ONLY отбраковал бы рабочий интент.
                intent.component != null ->
                    pm.resolveActivity(intent, 0) != null ||
                        pm.queryIntentActivities(intent, 0).isNotEmpty()
                // Launcher-интенты не объявляют CATEGORY_DEFAULT, поэтому
                // MATCH_DEFAULT_ONLY их не находит («Intent not available» для
                // mipicks) и шаг-приложение терял свою точку входа. Доступность
                // определяет PackageManager, а не фильтр по CATEGORY_DEFAULT.
                launcherPkg != null ->
                    pm.getLaunchIntentForPackage(launcherPkg) != null ||
                        pm.queryIntentActivities(intent, 0).isNotEmpty()
                else -> pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
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