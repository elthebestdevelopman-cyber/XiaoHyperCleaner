package com.xiaohypercleaner.data

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Финальная карта маршрутов по инструкции сообщества (все 11 пунктов).
 *
 * Типы действий:
 *  TOGGLE             — найти переключатель и выключить (+ additionalToggles рядом)
 *  CLEAR_DATA_DECLINE — очистить данные приложения и нажать «Отмена» на приветствии
 *
 * confirmTexts + confirmWaitMs — для старого варианта msa (диалог с 10-сек таймером).
 * preDrillWaitMs — пауза перед бурением (экраны со сканом, напр. «Очистка»).
 * swipeUpAfterLaunch — свайп вверх после запуска (поиск приложений в лаунчере).
 */
object SimpleSteps {

    enum class RiskLevel { SAFE, CONDITIONAL, HIGH }
    enum class ActionType { TOGGLE, CLEAR_DATA_DECLINE }

    data class Step(
        val id: String,
        val titleRu: String,
        val titleEn: String,
        val descRu: String,
        val descEn: String,
        val intents: List<Intent>,
        val searchTexts: List<String>,
        val targetChecked: Boolean = false,
        val manualHintRu: String,
        val manualHintEn: String,
        val riskLevel: RiskLevel = RiskLevel.SAFE,
        val warningRu: String? = null,
        val warningEn: String? = null,
        val drillPath: List<List<String>> = emptyList(),
        val launchPackage: String? = null,
        val requiredPackages: List<String> = emptyList(),
        val actionType: ActionType = ActionType.TOGGLE,
        val additionalToggles: List<String> = emptyList(),
        val confirmTexts: List<String> = emptyList(),
        val confirmWaitMs: Long = 0L,
        val preDrillWaitMs: Long = 0L,
        val swipeUpAfterLaunch: Boolean = false
    )

    // ── Хелперы ──────────────────────────────────────────────────────

    private fun settingsRoot() = Intent(Settings.ACTION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun notificationsIntent(pkg: String) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun appDetailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$pkg".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun launchIntent(pkg: String) = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER); setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Уровень 1 для маршрутов через «Отпечатки, данные лица и защита устройства» */
    private val SEC = listOf(
        "Отпечатки, данные лица и защита устройства",
        "Отпечатки, данные лица и за…",
        "Fingerprints, face data & device security",
        "Пароли и безопасность", "Passwords & security"
    )

    private val APPS = listOf("Приложения", "Apps")
    private val OVERFLOW = listOf("⋮", "Ещё", "More options", "More", "Дополнительно")
    private val OTHER_SETTINGS =
        listOf("Прочие настройки", "Additional settings", "Advanced settings")
    private val SYS_APPS = listOf("Системные приложения", "System apps")
    private val PRIVACY = listOf("Конфиденциальность", "Privacy")
    private val GEAR = listOf("⚙", "⚙️", "Настройки", "Settings")
    private val PROFILE = listOf("Профиль", "Profile", "Я", "Me")

    val ALL: List<Step> = listOf(

        // ═══ БЛОК А: СИСТЕМНЫЕ НАСТРОЙКИ ═══

        // П.1: msa. HyperOS: Доступ к личным данным → тумблер.
        // Старый MIUI: Авторизация и отзыв → тумблер msa → диалог с 10-сек таймером → «Отозвать».
        Step(
            id = "msa",
            titleRu = "Системный сервис MSA", titleEn = "MSA service",
            descRu = "Отзываем разрешение msa на доступ к личным данным.",
            descEn = "Revoking msa permission to personal data.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("msa"),
            manualHintRu = "Настройки → Отпечатки, данные лица и защита устройства → Доступ к личным данным → выключите msa.\n\nНа старых MIUI: Авторизация и отзыв → msa → дождитесь конца 10-секундного отсчёта → «Отозвать».",
            manualHintEn = "Settings → Fingerprints… → Access to personal data → turn off msa.",
            drillPath = listOf(
                SEC,
                listOf(
                    "Доступ к личным данным", "Access to personal data",
                    "Авторизация и отзыв", "Authorization & revocation"
                )
            ),
            confirmTexts = listOf("Отозвать", "Revoke"),
            confirmWaitMs = 11_000L,
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // П.2: Приложения → ⋮ → Прочие настройки → «Получать рекомендации»
        Step(
            id = "sys_recommendations",
            titleRu = "Системные рекомендации", titleEn = "System recommendations",
            descRu = "Выключаем «Получать рекомендации» в прочих настройках приложений.",
            descEn = "Turning off \"Receive recommendations\".",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Получать рекомендации", "Receive recommendations"),
            manualHintRu = "Настройки → Приложения → ⋮ → Прочие настройки → выключите «Получать рекомендации».",
            manualHintEn = "Settings → Apps → ⋮ → Additional settings → turn off \"Receive recommendations\".",
            drillPath = listOf(APPS, OVERFLOW, OTHER_SETTINGS)
        ),

        // П.3: Конфиденциальность → Рекламные службы → «Персонализация рекламы»
        Step(
            id = "ads_personalization",
            titleRu = "Персонализация рекламы", titleEn = "Ads personalization",
            descRu = "Отключаем персонализацию рекламы.",
            descEn = "Turning off ads personalization.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Персонализация рекламы",
                "Ads personalization",
                "Personalization of ads"
            ),
            manualHintRu = "Настройки → Отпечатки… → Конфиденциальность → Рекламные службы → выключите «Персонализация рекламы».",
            manualHintEn = "Settings → Fingerprints… → Privacy → Ad services → turn off personalization.",
            drillPath = listOf(SEC, PRIVACY, listOf("Рекламные службы", "Ad services"))
        ),

        // П.3b: Конфиденциальность → «Участвовать в Программе улучшения качества»
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения качества", titleEn = "User Experience Program",
            descRu = "Отключаем сбор статистики использования.",
            descEn = "Turning off usage statistics collection.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Участвовать в Программе улучшения качества",
                "Join User Experience Program", "User Experience Program"
            ),
            manualHintRu = "Настройки → Отпечатки… → Конфиденциальность → выключите «Участвовать в Программе улучшения качества».",
            manualHintEn = "Settings → Fingerprints… → Privacy → turn off User Experience Program.",
            drillPath = listOf(SEC, PRIVACY)
        ),

        // П.6: Блокировка экрана → Карусель обоев (2 тумблера)
        Step(
            id = "carousel",
            titleRu = "Карусель обоев", titleEn = "Wallpaper Carousel",
            descRu = "Выключаем карусель обоев и обновление через мобильный интернет.",
            descEn = "Turning off Wallpaper Carousel and mobile updates.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Карусель обоев", "Wallpaper Carousel", "Wallpaper carousel"),
            additionalToggles = listOf(
                "Обновлять через мобильный Интернет",
                "Update via mobile network"
            ),
            manualHintRu = "Настройки → Блокировка экрана → Карусель обоев → выключите всё.",
            manualHintEn = "Settings → Lock screen → Wallpaper Carousel → turn everything off.",
            drillPath = listOf(
                listOf("Блокировка экрана", "Lock screen"),
                listOf("Карусель обоев", "Wallpaper Carousel", "Glance")
            )
        ),

        // П.4a: Системные приложения → Mi Браузер → Дополнительные настройки
        Step(
            id = "browser_sys",
            titleRu = "Реклама Mi Браузера", titleEn = "Mi Browser ads",
            descRu = "Выключаем «Показывать рекламу» в системном браузере.",
            descEn = "Turning off \"Show ads\" in Mi Browser.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Показывать рекламу", "Show ads"),
            manualHintRu = "Настройки → Приложения → ⋮ → Прочие настройки → Системные приложения → Mi Браузер → Дополнительные настройки → выключите «Показывать рекламу».",
            manualHintEn = "Settings → Apps → ⋮ → Additional settings → System apps → Mi Browser → Advanced → turn off \"Show ads\".",
            drillPath = listOf(
                APPS, OVERFLOW, OTHER_SETTINGS, SYS_APPS,
                listOf("Mi Браузер", "Mi Browser"),
                listOf("Дополнительные настройки", "Advanced settings")
            ),
            requiredPackages = listOf("com.android.browser", "com.mi.global.browser")
        ),

        // П.4b: Системные приложения → Музыка (3 тумблера)
        Step(
            id = "music_sys",
            titleRu = "Реклама Музыки", titleEn = "Music ads",
            descRu = "Выключаем рекламу и рекомендации в Музыке.",
            descEn = "Turning off ads and recommendations in Music.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Показывать рекламу", "Show ads"),
            additionalToggles = listOf(
                "Показывать рекомендации в интернете", "Show recommendations online",
                "Персональные рекомендации", "Personalized recommendations"
            ),
            manualHintRu = "Настройки → … → Системные приложения → Музыка → выключите «Показывать рекламу» и рекомендации.",
            manualHintEn = "Settings → … → System apps → Music → turn off ads and recommendations.",
            drillPath = listOf(APPS, OVERFLOW, OTHER_SETTINGS, SYS_APPS, listOf("Музыка", "Music")),
            requiredPackages = listOf("com.miui.player")
        ),

        // П.4c: Системные приложения → Сообщения → Расширенные → Параметры рекламы
        Step(
            id = "messages_sys",
            titleRu = "Реклама Сообщений", titleEn = "Messages ads",
            descRu = "Выключаем персонализацию рекламы в Сообщениях.",
            descEn = "Turning off ads personalization in Messages.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Персонализация рекламы", "Ads personalization"),
            additionalToggles = listOf("Рекомендации", "Recommendations"),
            manualHintRu = "… → Системные приложения → Сообщения → Расширенные настройки → Параметры рекламы → выключите всё.",
            manualHintEn = "… → System apps → Messages → Advanced → Ad settings → turn everything off.",
            drillPath = listOf(
                APPS, OVERFLOW, OTHER_SETTINGS, SYS_APPS,
                listOf("Сообщения", "Messages", "Mi Messages"),
                listOf("Расширенные настройки", "Advanced settings"),
                listOf("Параметры рекламы", "Ad settings")
            ),
            requiredPackages = listOf("com.miui.mms")
        ),

        // П.4d: Системные приложения → Безопасность (2 тумблера)
        Step(
            id = "security_sys",
            titleRu = "Рекомендации Безопасности", titleEn = "Security recommendations",
            descRu = "Выключаем рекомендации в приложении Безопасность.",
            descEn = "Turning off Security app recommendations.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Получать рекомендации", "Receive recommendations"),
            additionalToggles = listOf("Загружать только по Wi-Fi", "Download only over Wi-Fi"),
            manualHintRu = "… → Системные приложения → Безопасность → выключите «Получать рекомендации» и «Загружать только по Wi-Fi».",
            manualHintEn = "… → System apps → Security → turn off recommendations and Wi-Fi-only.",
            drillPath = listOf(
                APPS,
                OVERFLOW,
                OTHER_SETTINGS,
                SYS_APPS,
                listOf("Безопасность", "Security")
            ),
            requiredPackages = listOf("com.miui.securitycenter")
        ),

        // ═══ БЛОК Б: ВНУТРИ ПРИЛОЖЕНИЙ ═══

        // П.4e: Безопасность → Очистка → ⚙ (скан ~4 сек перед бурением)
        Step(
            id = "cleaner",
            titleRu = "Рекомендации Очистки", titleEn = "Cleaner recommendations",
            descRu = "Выключаем рекомендации в Очистке.",
            descEn = "Turning off Cleaner recommendations.",
            intents = listOf(launchIntent("com.miui.securitycenter")),
            searchTexts = listOf("Получать рекомендации", "Receive recommendations"),
            additionalToggles = listOf("Загружать только по Wi-Fi", "Download only over Wi-Fi"),
            manualHintRu = "Безопасность → Очистка → ⚙ → выключите «Получать рекомендации» и «Загружать только по Wi-Fi».",
            manualHintEn = "Security → Cleaner → ⚙ → turn off recommendations.",
            launchPackage = "com.miui.securitycenter",
            preDrillWaitMs = 4_000L,
            drillPath = listOf(listOf("Очистка", "Cleaner", "Ускорить", "Speed up"), GEAR),
            requiredPackages = listOf("com.miui.securitycenter")
        ),

        // П.4f: Загрузки → ⋮ → Настройки
        Step(
            id = "downloads",
            titleRu = "Рекомендации Загрузок", titleEn = "Downloads recommendations",
            descRu = "Выключаем рекомендации в Загрузках.",
            descEn = "Turning off Downloads recommendations.",
            intents = listOf(launchIntent("com.android.providers.downloads.ui")),
            searchTexts = listOf("Получать рекомендации", "Receive recommendations"),
            manualHintRu = "Загрузки → ⋮ → Настройки → выключите «Получать рекомендации».",
            manualHintEn = "Downloads → ⋮ → Settings → turn off recommendations.",
            launchPackage = "com.android.providers.downloads.ui",
            drillPath = listOf(OVERFLOW, GEAR),
            requiredPackages = listOf(
                "com.android.providers.downloads.ui",
                "com.miui.android.downloads"
            )
        ),

        // П.4g: Темы → ⚙ (2 тумблера)
        Step(
            id = "themes",
            titleRu = "Реклама Тем", titleEn = "Themes ads",
            descRu = "Выключаем рекламу в Темах.",
            descEn = "Turning off Themes ads.",
            intents = listOf(launchIntent("com.android.thememanager")),
            searchTexts = listOf("Показывать рекламу", "Show ads"),
            additionalToggles = listOf("Персональные рекомендации", "Personalized recommendations"),
            manualHintRu = "Темы → ⚙ → выключите «Показывать рекламу» и «Персональные рекомендации».",
            manualHintEn = "Themes → ⚙ → turn off ads and recommendations.",
            launchPackage = "com.android.thememanager",
            drillPath = listOf(
                listOf("Профиль", "Profile", "Моя", "Me"),
                listOf("⚙", "⚙️", "Настройки", "Settings")
            ),
            requiredPackages = listOf("com.android.thememanager")
        ),

        // П.4h: GetApps → Профиль → ⚙ → Конфиденциальность
        Step(
            id = "getapps",
            titleRu = "Рекомендации GetApps", titleEn = "GetApps recommendations",
            descRu = "Выключаем рекомендации в GetApps.",
            descEn = "Turning off GetApps recommendations.",
            intents = listOf(launchIntent("com.xiaomi.market")),
            searchTexts = listOf("Персональные рекомендации", "Personalized recommendations"),
            manualHintRu = "GetApps → Профиль → ⚙ → Конфиденциальность → выключите «Персональные рекомендации».",
            manualHintEn = "GetApps → Profile → ⚙ → Privacy → turn off recommendations.",
            launchPackage = "com.xiaomi.market",
            drillPath = listOf(PROFILE, GEAR, PRIVACY),
            requiredPackages = listOf("com.xiaomi.market")
        ),

        // П.4i: Mi Видео → Профиль → ⚙ (сбрасывается раз в 90 дней)
        Step(
            id = "mivideo",
            titleRu = "Рекомендации Mi Видео", titleEn = "Mi Video recommendations",
            descRu = "Выключаем рекомендации в Mi Видео.",
            descEn = "Turning off Mi Video recommendations.",
            intents = listOf(launchIntent("com.miui.videoplayer")),
            searchTexts = listOf("Персональные рекомендации", "Personalized recommendations"),
            manualHintRu = "Mi Видео → Профиль → ⚙ → выключите «Персональные рекомендации».",
            manualHintEn = "Mi Video → Profile → ⚙ → turn off recommendations.",
            launchPackage = "com.miui.videoplayer",
            drillPath = listOf(PROFILE, GEAR),
            requiredPackages = listOf("com.miui.videoplayer")
        ),

        // П.4j: ShareMe → ⋮ → О приложении → Справка и обратная связь
        Step(
            id = "shareme",
            titleRu = "Персонализация ShareMe", titleEn = "ShareMe personalization",
            descRu = "Выключаем персонализацию рекламы в ShareMe.",
            descEn = "Turning off ShareMe ads personalization.",
            intents = listOf(launchIntent("com.xiaomi.midrop")),
            searchTexts = listOf("Персонализация рекламы", "Ads personalization"),
            manualHintRu = "ShareMe → ⋮ → О приложении → Справка и обратная связь → выключите «Персонализация рекламы».",
            manualHintEn = "ShareMe → ⋮ → About → Help & feedback → turn off ads personalization.",
            launchPackage = "com.xiaomi.midrop",
            drillPath = listOf(
                OVERFLOW, listOf("О приложении", "About"),
                listOf("Справка и обратная связь", "Help & feedback")
            ),
            requiredPackages = listOf("com.xiaomi.midrop")
        ),

        // П.5: Проводник — очистить данные + «Отмена» на приветствии
        Step(
            id = "filemanager",
            titleRu = "Реклама Проводника", titleEn = "File Manager ads",
            descRu = "Очищаем данные Проводника и не принимаем политику.",
            descEn = "Clearing File Manager data and declining the policy.",
            intents = listOf(appDetailsIntent("com.mi.android.globalFileexplorer")),
            searchTexts = emptyList(),
            manualHintRu = "Настройки → Приложения → Проводник → Очистить все → откройте Проводник → нажмите «Отмена».",
            manualHintEn = "Settings → Apps → File Manager → Clear all → open it → tap Cancel.",
            actionType = ActionType.CLEAR_DATA_DECLINE,
            launchPackage = "com.mi.android.globalFileexplorer",
            requiredPackages = listOf(
                "com.mi.android.globalFileexplorer",
                "com.android.fileexplorer"
            )
        ),

        // ═══ БЛОК В: ЛАУНЧЕР (свайп) ═══

        // П.7: Поиск приложений → Настройки → «Рекомендации по приложениям»
        Step(
            id = "search_ads",
            titleRu = "Реклама в поиске приложений", titleEn = "App search ads",
            descRu = "Выключаем рекомендации в поиске приложений.",
            descEn = "Turning off app search recommendations.",
            intents = listOf(launchIntent("com.miui.home")),
            searchTexts = listOf("Рекомендации по приложениям", "App recommendations"),
            manualHintRu = "Поиск приложений → ⋮ → Настройки → выключите «Рекомендации по приложениям».",
            manualHintEn = "App search → ⋮ → Settings → turn off \"App recommendations\".",
            launchPackage = "com.miui.home",
            swipeUpAfterLaunch = true,
            drillPath = listOf(GEAR),
            requiredPackages = listOf("com.miui.home"),
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // П.7b: … → Страница поиска → «Игровой центр» и «Mi Видео»
        Step(
            id = "search_page",
            titleRu = "Страница поиска", titleEn = "Search page",
            descRu = "Выключаем рекламные источники на странице поиска.",
            descEn = "Turning off ad sources on the search page.",
            intents = listOf(launchIntent("com.miui.home")),
            searchTexts = listOf("Игровой центр", "Game Center"),
            additionalToggles = listOf("Mi Видео", "Mi Video", "Другие приложения", "Other apps"),
            manualHintRu = "Поиск → ⋮ → Настройки → Страница поиска → выключите «Игровой центр», «Mi Видео».",
            manualHintEn = "Search → ⋮ → Settings → Search page → turn off Game Center, Mi Video.",
            launchPackage = "com.miui.home",
            swipeUpAfterLaunch = true,
            drillPath = listOf(GEAR, listOf("Страница поиска", "Search page")),
            requiredPackages = listOf("com.miui.home"),
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // П.8: Лента виджетов → ⋮ → Управление службами → «Предложения»
        Step(
            id = "appvault_services",
            titleRu = "Лента виджетов: предложения", titleEn = "App Vault suggestions",
            descRu = "Выключаем «Предложения» в ленте виджетов.",
            descEn = "Turning off App Vault suggestions.",
            intents = listOf(launchIntent("com.miui.personalassistant")),
            searchTexts = listOf("Предложение", "Suggestions"),
            additionalToggles = listOf("Рекомендации приложений", "App recommendations"),
            manualHintRu = "Лента виджетов → ⋮ → Управление службами → выключите «Предложения».",
            manualHintEn = "App Vault → ⋮ → Service management → turn off Suggestions.",
            launchPackage = "com.miui.personalassistant",
            drillPath = listOf(OVERFLOW, listOf("Управление службами", "Service management")),
            requiredPackages = listOf("com.miui.personalassistant"),
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // П.8b: … → О ленте виджетов → «Персонализированные услуги»
        Step(
            id = "appvault_about",
            titleRu = "Лента виджетов: услуги", titleEn = "App Vault services",
            descRu = "Выключаем «Персонализированные услуги».",
            descEn = "Turning off personalized services.",
            intents = listOf(launchIntent("com.miui.personalassistant")),
            searchTexts = listOf("Персонализированные услуги", "Personalized services"),
            manualHintRu = "Лента виджетов → ⋮ → О ленте виджетов → выключите «Персонализированные услуги».",
            manualHintEn = "App Vault → ⋮ → About → turn off Personalized services.",
            launchPackage = "com.miui.personalassistant",
            drillPath = listOf(
                OVERFLOW,
                listOf("О ленте виджетов", "About App Vault", "About widget feed")
            ),
            requiredPackages = listOf("com.miui.personalassistant"),
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // ═══ БЛОК Г: УВЕДОМЛЕНИЯ (п.11) ═══

        Step(
            id = "notif_gamecenter",
            titleRu = "Уведомления Игрового центра", titleEn = "Game Center notifications",
            descRu = "Полностью выключаем уведомления Игрового центра.",
            descEn = "Turning off Game Center notifications.",
            intents = listOf(notificationsIntent("com.xiaomi.gamecenter")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Игровой центр → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Game Center → Notifications → turn off.",
            requiredPackages = listOf("com.xiaomi.gamecenter")
        ),
        Step(
            id = "notif_appvault",
            titleRu = "Уведомления Ленты виджетов", titleEn = "App Vault notifications",
            descRu = "Выключаем уведомления Ленты виджетов.",
            descEn = "Turning off App Vault notifications.",
            intents = listOf(notificationsIntent("com.miui.personalassistant")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Лента виджетов → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → App Vault → Notifications → turn off.",
            requiredPackages = listOf("com.miui.personalassistant")
        ),
        Step(
            id = "notif_themes",
            titleRu = "Уведомления Тем", titleEn = "Themes notifications",
            descRu = "Выключаем уведомления Тем.",
            descEn = "Turning off Themes notifications.",
            intents = listOf(notificationsIntent("com.android.thememanager")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Темы → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Themes → Notifications → turn off.",
            requiredPackages = listOf("com.android.thememanager")
        ),
        Step(
            id = "notif_getapps",
            titleRu = "Уведомления GetApps", titleEn = "GetApps notifications",
            descRu = "Выключаем уведомления GetApps.",
            descEn = "Turning off GetApps notifications.",
            intents = listOf(notificationsIntent("com.xiaomi.market")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → GetApps → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → GetApps → Notifications → turn off.",
            requiredPackages = listOf("com.xiaomi.market")
        ),
        Step(
            id = "notif_browser",
            titleRu = "Уведомления Mi Браузера", titleEn = "Mi Browser notifications",
            descRu = "Выключаем уведомления Mi Браузера.",
            descEn = "Turning off Mi Browser notifications.",
            intents = listOf(notificationsIntent("com.android.browser")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Mi Браузер → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Mi Browser → Notifications → turn off.",
            requiredPackages = listOf("com.android.browser", "com.mi.global.browser")
        ),
        Step(
            id = "notif_mivideo",
            titleRu = "Уведомления Mi Видео", titleEn = "Mi Video notifications",
            descRu = "Выключаем уведомления Mi Видео.",
            descEn = "Turning off Mi Video notifications.",
            intents = listOf(notificationsIntent("com.miui.videoplayer")),
            searchTexts = listOf(
                "Разрешить уведомления",
                "Allow notifications",
                "Показывать уведомления",
                "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Mi Видео → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Mi Video → Notifications → turn off.",
            requiredPackages = listOf("com.miui.videoplayer")
        )
    )

    private fun Intent.addFlags(flags: Int): Intent = this.apply { addFlags(flags) }
}