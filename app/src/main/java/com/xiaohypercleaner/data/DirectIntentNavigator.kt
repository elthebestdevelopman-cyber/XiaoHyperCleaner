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
 *
 * РЕЕСТР «NOT_EXPORTED = только drill» (пробы 2026-09-26, `docs/diag/intent_probe_report.md`):
 * цели закрыты для стороннего актора, вход только UI-бурением —
 * `google_diagnostics` (обе цели: действие GMS и CollapseUsageReportingActivity),
 * `ux_program` (`UsageAndDiagnosticsActivity`), `music_sys` (`MusicSettings`),
 * `downloads` (`InterSettingActivity`), `installer_recommendations` (`SettingsActivity`),
 * `security_sys` (`optimizemanage.settings.SettingsActivity`),
 * `getapps` (`PrivacyPreferenceFragmentActivity` — на устройстве mipicks 602-16.4.4.0
 * первая позиция коммита f35595c не подтвердилась).
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
                // MSA — отзыв доступа к личным данным. Пробы 2026-09-26: APP_PERM_EDITOR
                // с extra_package_name=msa открывает настройки Безопасности (WRONG_SCREEN),
                // miui AD_SERVICES_SETTINGS — NO_RESOLVE, miui PRIVACY_SETTINGS —
                // WRONG_SCREEN (PrivacySafetyActivity). Зелёных интентов нет вовсе —
                // шаг идёт только drill'ом (каталог: «Пароли и безопасность» →
                // «Доступ к личным данным»).
                AppLog.i(TAG, "msa: no verified direct intents — drill only")
            }

            "sys_recommendations" -> {
                // Системные рекомендации. Проба p_sysrec_seccenter (2026-09-26): явная
                // компонента `com.miui.appmanager.AppManagerSettings` в securitycenter
                // экспортирована, открывает экран с тумблером «Получать рекомендации»
                // (verdict OK) — вход уровня 1 без бурения «Приложения → Ещё».
                // Фолбэк — «Все приложения» системных Настроек (verdict OK); miui
                // SYSTEM_RECOMMENDATIONS и aosp SYSTEM_RECOMMENDATIONS_SETTINGS —
                // NO_RESOLVE (убраны).
                intents.addAll(
                    listOf(
                        explicitActivity(
                            "com.miui.securitycenter",
                            "com.miui.appmanager.AppManagerSettings"
                        ),
                        settingsIntent("android.settings.APPLICATION_SETTINGS")
                    )
                )
            }

            "ads_personalization" -> {
                // Персонализация рекламы. Проба p_ads_comp_adservice (2026-09-26): явная
                // компонента `com.android.settings.ad.AdServiceSettings` открывает целевой
                // экран («Персонализированная реклама», verdict OK). Действия
                // AD_SERVICES_SETTINGS (miui/aosp) — NO_RESOLVE, miui PRIVACY_SETTINGS —
                // WRONG_SCREEN: в цепочке только зелёная компонента.
                intents.add(
                    explicitActivity(
                        "com.android.settings",
                        "com.android.settings.ad.AdServiceSettings"
                    )
                )
            }

            "ux_program" -> {
                // Программа улучшения качества + MIUI-«Использование и диагностика».
                // Пробы 2026-09-26: miui USER_EXPERIENCE_PROGRAM и DIAGNOSTIC_SETTINGS,
                // aosp USER_EXPERIENCE_PROGRAM — NO_RESOLVE; UsageAndDiagnosticsActivity —
                // NOT_EXPORTED (только drill). Зелёных интентов нет: вход — drill каталога
                // («Пароли и безопасность» → «Конфиденциальность» → «Дополнительные настройки»).
                AppLog.i(TAG, "ux_program: no verified direct intents — drill only")
            }

            "google_diagnostics" -> {
                // Google «Использование и диагностика». Пробы 2026-09-26: действие
                // com.google.android.gms.usagereporting.GOOGLE_SETTINGS и компонента
                // CollapseUsageReportingActivity — NOT_EXPORTED (только drill).
                // Вход — «Конфиденциальность» системных Настроек (это единственный шаг,
                // где PRIVACY_SETTINGS оставлен), дальше drill [Использование и диагностика].
                intents.add(settingsIntent(Settings.ACTION_PRIVACY_SETTINGS))
            }

            "carousel" -> {
                // Карусель обоев. На POCO/MIUI 13 пункта «Карусель обоев» в «Блокировке экрана»
                // нет вовсе (дамп lockscreen), зато у приложения карусели есть экспортированное
                // действие настроек: оно открывает экран с тумблерами «Карусель экрана блокировки»,
                // «Проведите вправо…», «Обновлять через мобильный Интернет»
                // (com.miui.cw.feature.ui.setting.SettingActivity, дамп carousel_setting_act).
                intents.addAll(carouselSettingsIntents())
                // WALLPAPER_CAROUSEL / LOCK_SCREEN_SETTINGS / ACTION_SETTINGS убраны:
                // пробами 2026-09-26 они не подтверждены, а зелёные точки входа шага —
                // действие SETTING и компонента SettingActivity (verdict OK).
            }

            // ═══════════════════════════════════════════════════════════
            // БЛОК Б: ВНУТРИ ПРИЛОЖЕНИЙ
            // ═══════════════════════════════════════════════════════════

            "browser_sys" -> {
                // Mi Браузер. Пробы 2026-09-26: компонента BrowserSettingsActivity и
                // действие com.android.browser.OPEN_SETTINGS открывают «Основные настройки»
                // (verdict OK) — они первые; miui APP_SETTINGS — NO_RESOLVE (убран).
                // Launcher-интенты остаются фолбэком входа в приложение.
                val pkg = resolvedPackage ?: "com.mi.globalbrowser"
                intents.addAll(
                    listOf(
                        explicitActivity(pkg, "com.android.browser.BrowserSettingsActivity"),
                        actionIntent("com.android.browser.OPEN_SETTINGS", pkg),
                        launchIntent(pkg),
                        launchIntent("com.mi.globalbrowser")
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
                // Лента виджетов (App Vault) — настройки лаунчера POCO. Пробы 2026-09-26:
                // действие `com.mi.android.globallauncher.Setting` и компонента
                // `com.mi.android.globallauncher/com.miui.home.settings.HomeSettingsActivity`
                // открывают «Рабочий стол» (verdict OK); miui HOME_SETTINGS и com.miui.home —
                // NO_RESOLVE, android.settings.HOME_SETTINGS не проверялся — убраны.
                intents.addAll(launcherSettingsIntents())
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
                // PrivacyPreferenceFragmentActivity убрана: проба p_getapps_privacy
                // (2026-09-26) — NOT_EXPORTED на устройстве (mipicks 602-16.4.4.0),
                // первая позиция коммита f35595c не подтвердилась. Остаётся drill по UI
                // и launcher-интент ниже.
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
                // Лента виджетов — предложения / услуги. Пробы 2026-09-26: компонента
                // `com.mi.android.globalminusscreen/…tab.TabSettingActivity` открывает экран
                // настроек ленты (verdict OK, маркеры «Лента виджетов»+«Рекомендуемое» /
                // «О ленте виджетов») — она первая, бурение через «Рабочий стол» не нужно.
                val pkg = resolvedPackage ?: "com.miui.personalassistant"
                intents.addAll(
                    listOf(
                        explicitActivity(
                            "com.mi.android.globalminusscreen",
                            "com.mi.android.globalminusscreen.tab.TabSettingActivity"
                        ),
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
        explicitActivity(
            "com.android.providers.downloads.ui",
            "com.android.providers.downloads.ui.DownloadList"
        ),
        actionIntent("android.intent.action.VIEW_DOWNLOADS", "com.android.providers.downloads.ui")
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
        )
    )

    /**
     * Настройки карусели обоев (`com.miui.android.fashiongallery`): экспортированное действие
     * `com.miui.android.fashiongallery.setting.SETTING` открывает
     * `com.miui.cw.feature.ui.setting.SettingActivity` — экран с тумблерами «Карусель экрана
     * блокировки», «Проведите вправо по Экрану блокировки», «Обновлять через мобильный Интернет»
     * (дамп carousel_setting_act). Пункта «Карусель обоев» в «Блокировке экрана» на POCO/MIUI 13
     * нет (дамп lockscreen), поэтому прямой вход — основной, а drill остаётся фолбэком.
     */
    internal fun carouselSettingsIntents(): List<Intent> = listOf(
        actionIntent(
            "com.miui.android.fashiongallery.setting.SETTING",
            "com.miui.android.fashiongallery"
        ),
        explicitActivity(
            "com.miui.android.fashiongallery",
            "com.miui.cw.feature.ui.setting.SettingActivity"
        )
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