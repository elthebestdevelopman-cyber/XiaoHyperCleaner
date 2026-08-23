package com.xiaohypercleaner.data

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Шаги простой автоматизации по инструкции сообщества Xiaomi
 * (new.c.mi.com/ru/post/466351).
 *
 * ВАЖНО: drillPath: List<List<String>> — каждый уровень это список
 * альтернативных текстов (RU/EN/иконки). Ищется ПЕРВЫЙ найденный.
 */
object SimpleSteps {

    enum class RiskLevel { SAFE, CONDITIONAL, HIGH }

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
        /** Путь бурения: каждый уровень — список альтернативных текстов */
        val drillPath: List<List<String>> = emptyList(),
        val launchPackage: String? = null,
        val requiredPackages: List<String> = emptyList()
    )

    private fun notificationsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra("android.provider.extra.APP_PACKAGE", packageName)
            putExtra("app_package", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun launchIntent(packageName: String): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    val ALL: List<Step> = listOf(

        // ═══ ЯДРО ПРИВАТНОСТИ (стартуют с корня Настроек) ═══

        Step(
            id = "msa",
            titleRu = "Системный сервис MSA",
            titleEn = "MSA system service",
            descRu = "Главный сервис рекомендаций MIUI. Отзываем его разрешение.",
            descEn = "The main MIUI recommendations service. Revoking its permission.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "msa", "MSA", "Отозвать", "Revoke",
                "Авторизация и отзыв", "Authorization & revocation"
            ),
            drillPath = listOf(
                listOf(
                    "Пароли и безопасность",
                    "Passwords & security",
                    "Отпечатки, данные лица и защита"
                ),
                listOf("Авторизация и отзыв", "Authorization & revocation", "Разрешения и отзыв"),
                listOf("msa", "MSA"),
                listOf("Отозвать", "Revoke")
            ),
            manualHintRu = "Настройки → Пароли и безопасность → Авторизация и отзыв → msa → Отозвать.\n\nПоявится подтверждение с отсчётом 10 секунд — дождитесь и нажмите «Отозвать».",
            manualHintEn = "Settings → Passwords & security → Authorization & revocation → msa → Revoke.\n\nA 10-second countdown will appear — wait and tap Revoke.",
            riskLevel = RiskLevel.CONDITIONAL,
            warningRu = "⚠️ После отзыва отключатся рекомендации в системных приложениях. Подтверждение имеет 10-секундный отсчёт.",
            warningEn = "⚠️ System app recommendations will be disabled. The confirmation has a 10-second countdown."
        ),

        Step(
            id = "personalized",
            titleRu = "Системные рекомендации",
            titleEn = "System recommendations",
            descRu = "Отключаем «Получать рекомендации» в Рекламных службах.",
            descEn = "Turning off \"Receive recommendations\" in Ad services.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "Персональные рекомендации", "Personalized recommendations"
            ),
            drillPath = listOf(
                listOf(
                    "Пароли и безопасность",
                    "Passwords & security",
                    "Отпечатки, данные лица и защита"
                ),
                listOf("Конфиденциальность", "Privacy"),
                listOf("Рекламные службы", "Ad services", "Реклама", "Ads")
            ),
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → выключите «Получать рекомендации».",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → turn off \"Receive recommendations\"."
        ),

        Step(
            id = "ads_personalization",
            titleRu = "Персонализация рекламы",
            titleEn = "Ads personalization",
            descRu = "Отключаем персонализацию рекламы в Рекламных службах.",
            descEn = "Turning off ads personalization in Ad services.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Персонализация рекламы", "Ads personalization",
                "Персонализация", "Personalization",
                "Ограничить отслеживание", "Limit ad tracking"
            ),
            drillPath = listOf(
                listOf(
                    "Пароли и безопасность",
                    "Passwords & security",
                    "Отпечатки, данные лица и защита"
                ),
                listOf("Конфиденциальность", "Privacy"),
                listOf("Рекламные службы", "Ad services", "Реклама", "Ads")
            ),
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → выключите «Персонализация рекламы».",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → turn off \"Ads personalization\"."
        ),

        Step(
            id = "ux_program",
            titleRu = "Программа улучшения UX",
            titleEn = "User Experience Program",
            descRu = "Программа улучшения UX отправляет статистику. Отключаем.",
            descEn = "The User Experience Program sends usage statistics. Turning it off.",
            intents = listOf(
                Intent("miui.intent.action.USER_EXPERIENCE_PROGRAM").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "User Experience Program", "Программа улучшения UX",
                "Программа улучшения качества", "Join User Experience Program"
            ),
            drillPath = listOf(
                listOf(
                    "Пароли и безопасность",
                    "Passwords & security",
                    "Отпечатки, данные лица и защита"
                ),
                listOf("Конфиденциальность", "Privacy"),
                listOf(
                    "Программа улучшения UX",
                    "User Experience Program",
                    "Программа улучшения качества"
                )
            ),
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Программа улучшения UX (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → User Experience Program (off)"
        ),

        Step(
            id = "analytics",
            titleRu = "Системная аналитика",
            titleEn = "System Analytics",
            descRu = "Сбор статистики использования. Отключаем.",
            descEn = "System usage statistics collection. Turning off.",
            intents = listOf(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Аналитика", "Analytics", "Диагностика", "Diagnostics",
                "Использование", "Usage", "Статистика", "Statistics"
            ),
            drillPath = listOf(
                listOf(
                    "Пароли и безопасность",
                    "Passwords & security",
                    "Отпечатки, данные лица и защита"
                ),
                listOf("Конфиденциальность", "Privacy"),
                listOf(
                    "Использование и диагностика",
                    "Usage & diagnostics",
                    "Диагностика",
                    "Diagnostics"
                )
            ),
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Использование и диагностика → отключите.",
            manualHintEn = "Settings → Passwords & security → Privacy → Usage & diagnostics → turn off."
        ),

        // ═══ ШАГИ, ТРЕБУЮЩИЕ НАЛИЧИЯ ПРИЛОЖЕНИЯ ═══

        Step(
            id = "security",
            titleRu = "Рекомендации Безопасности",
            titleEn = "Security app recommendations",
            descRu = "Отключаем рекомендации внутри приложения «Безопасность».",
            descEn = "Turning off recommendations inside the Security app.",
            intents = listOf(launchIntent("com.miui.securitycenter")),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "Загружать только по Wi-Fi", "Download only over Wi-Fi"
            ),
            manualHintRu = "Откройте «Безопасность» → ⚙️ → выключите «Получать рекомендации».",
            manualHintEn = "Open Security → ⚙️ → turn off \"Receive recommendations\".",
            launchPackage = "com.miui.securitycenter",
            drillPath = listOf(
                listOf("⚙", "⚙️", "Настройки", "Settings")
            ),
            requiredPackages = listOf("com.miui.securitycenter")
        ),

        Step(
            id = "downloads",
            titleRu = "Рекомендации Загрузок",
            titleEn = "Downloads recommendations",
            descRu = "Отключаем рекомендации в приложении «Загрузки».",
            descEn = "Turning off recommendations in the Downloads app.",
            intents = listOf(launchIntent("com.android.providers.downloads.ui")),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "Рекомендации", "Recommendations"
            ),
            manualHintRu = "Откройте «Загрузки» → ⋮ → Настройки → выключите «Получать рекомендации».",
            manualHintEn = "Open Downloads → ⋮ → Settings → turn off \"Receive recommendations\".",
            launchPackage = "com.android.providers.downloads.ui",
            drillPath = listOf(
                listOf("⋮", "Ещё", "More options", "Меню", "Menu"),
                listOf("Настройки", "Settings")
            ),
            requiredPackages = listOf(
                "com.android.providers.downloads.ui",
                "com.miui.android.downloads"
            )
        ),

        Step(
            id = "browser",
            titleRu = "Настройки Mi Браузера",
            titleEn = "Mi Browser settings",
            descRu = "Отключаем рекламу внутри Mi Браузера.",
            descEn = "Turning off ads inside Mi Browser.",
            intents = listOf(launchIntent("com.android.browser")),
            searchTexts = listOf(
                "Показывать рекламу", "Show ads",
                "Уведомления", "Notifications"
            ),
            manualHintRu = "Откройте Mi Браузер → меню → Дополнительные настройки → отключите «Показывать рекламу».",
            manualHintEn = "Open Mi Browser → menu → Additional settings → turn off \"Show ads\".",
            launchPackage = "com.android.browser",
            drillPath = listOf(
                listOf("Меню", "Menu", "⋮", "Профиль", "Profile"),
                listOf("Дополнительные настройки", "Additional settings")
            ),
            requiredPackages = listOf("com.android.browser", "com.mi.global.browser")
        ),

        Step(
            id = "music",
            titleRu = "Уведомления Mi Music",
            titleEn = "Mi Music notifications",
            descRu = "Отключаем лишние уведомления Mi Music.",
            descEn = "Turning off unnecessary Mi Music notifications.",
            intents = listOf(launchIntent("com.miui.player")),
            searchTexts = listOf(
                "Показывать рекомендации", "Show recommendations",
                "Персональные рекомендации", "Personalized recommendations"
            ),
            manualHintRu = "Откройте Mi Music → меню → Настройки → отключите рекомендации.",
            manualHintEn = "Open Mi Music → menu → Settings → turn off recommendations.",
            riskLevel = RiskLevel.CONDITIONAL,
            launchPackage = "com.miui.player",
            drillPath = listOf(
                listOf("Меню", "Menu", "⋮", "Профиль", "Profile"),
                listOf("Настройки", "Settings")
            ),
            requiredPackages = listOf("com.miui.player")
        ),

        Step(
            id = "messages",
            titleRu = "Настройки Mi Сообщений",
            titleEn = "Mi Messages settings",
            descRu = "Отключаем персонализацию внутри Mi Сообщений.",
            descEn = "Turning off personalization inside Mi Messages.",
            intents = listOf(launchIntent("com.miui.mms")),
            searchTexts = listOf(
                "Персонализация",
                "Personalization",
                "Параметры рекламы",
                "Ad settings"
            ),
            manualHintRu = "Откройте Сообщения → ⋮ → Расширенные настройки → Параметры рекламы.",
            manualHintEn = "Open Messages → ⋮ → Advanced settings → Ad settings.",
            launchPackage = "com.miui.mms",
            drillPath = listOf(
                listOf("⋮", "Ещё", "More options"),
                listOf("Расширенные настройки", "Advanced settings", "Настройки", "Settings"),
                listOf("Параметры рекламы", "Ad settings", "Персонализация", "Personalization")
            ),
            requiredPackages = listOf("com.miui.mms")
        ),

        Step(
            id = "themes",
            titleRu = "Уведомления Темы",
            titleEn = "Themes notifications",
            descRu = "Отключаем рекламу приложения Темы.",
            descEn = "Turning off Themes app ads.",
            intents = listOf(launchIntent("com.android.thememanager")),
            searchTexts = listOf("Показывать рекламу", "Show ads", "Персональные рекомендации"),
            manualHintRu = "Откройте Темы → ⚙️ → отключите «Показывать рекламу».",
            manualHintEn = "Open Themes → ⚙️ → turn off \"Show ads\".",
            launchPackage = "com.android.thememanager",
            drillPath = listOf(listOf("⚙", "⚙️", "Настройки", "Settings")),
            requiredPackages = listOf("com.android.thememanager")
        ),

        Step(
            id = "getapps",
            titleRu = "Уведомления GetApps",
            titleEn = "GetApps notifications",
            descRu = "Отключаем рекламу магазина GetApps.",
            descEn = "Turning off GetApps store ads.",
            intents = listOf(launchIntent("com.xiaomi.market")),
            searchTexts = listOf(
                "Персональные рекомендации",
                "Personalized recommendations",
                "Уведомления"
            ),
            manualHintRu = "Откройте GetApps → Профиль → ⚙️ → Конфиденциальность → отключите рекомендации.",
            manualHintEn = "Open GetApps → Profile → ⚙️ → Privacy → turn off recommendations.",
            launchPackage = "com.xiaomi.market",
            drillPath = listOf(
                listOf("Профиль", "Profile", "Я", "Me"),
                listOf("⚙", "⚙️", "Настройки", "Settings"),
                listOf("Конфиденциальность", "Privacy")
            ),
            requiredPackages = listOf("com.xiaomi.market")
        ),

        Step(
            id = "video",
            titleRu = "Уведомления Mi Видео",
            titleEn = "Mi Video notifications",
            descRu = "Отключаем персональные рекомендации Mi Видео.",
            descEn = "Turning off personalized recommendations in Mi Video.",
            intents = listOf(launchIntent("com.miui.videoplayer")),
            searchTexts = listOf("Персональные рекомендации", "Personalized recommendations"),
            manualHintRu = "Откройте Mi Видео → Профиль → ⚙️ → отключите «Персональные рекомендации».",
            manualHintEn = "Open Mi Video → Profile → ⚙️ → turn off \"Personalized recommendations\".",
            launchPackage = "com.miui.videoplayer",
            drillPath = listOf(
                listOf("Профиль", "Profile", "Я", "Me"),
                listOf("⚙", "⚙️", "Настройки", "Settings")
            ),
            requiredPackages = listOf("com.miui.videoplayer")
        ),

        Step(
            id = "gamecenter",
            titleRu = "Уведомления Игрового центра",
            titleEn = "Game Center notifications",
            descRu = "Отключаем уведомления и промо-контент Игрового центра.",
            descEn = "Turning off Game Center notifications and promo content.",
            intents = listOf(notificationsIntent("com.xiaomi.gamecenter")),
            searchTexts = listOf("Уведомления", "Notifications", "Allow notifications"),
            manualHintRu = "Откроются уведомления Игрового центра. Выключите «Разрешить уведомления».",
            manualHintEn = "Game Center notification settings will open. Turn off \"Allow notifications\".",
            requiredPackages = listOf("com.xiaomi.gamecenter")
        ),

        Step(
            id = "appvault",
            titleRu = "Лента виджетов (App Vault)",
            titleEn = "App Vault (widget feed)",
            descRu = "Отключаем предложения Ленты виджетов.",
            descEn = "Turning off App Vault suggestions.",
            intents = listOf(launchIntent("com.miui.personalassistant")),
            searchTexts = listOf("Предложения", "Suggestions", "Управление службами"),
            manualHintRu = "Лента виджетов → ⋮ → Управление службами → отключите «Предложения».",
            manualHintEn = "App Vault → ⋮ → Service management → turn off \"Suggestions\".",
            launchPackage = "com.miui.personalassistant",
            drillPath = listOf(
                listOf("⋮", "Ещё", "More options"),
                listOf("Управление службами", "Service management", "Настройки", "Settings")
            ),
            requiredPackages = listOf("com.miui.personalassistant")
        ),

        Step(
            id = "carousel",
            titleRu = "Карусель обоев",
            titleEn = "Wallpaper Carousel",
            descRu = "Отключаем уведомления Карусели обоев на экране блокировки.",
            descEn = "Turning off Wallpaper Carousel notifications on the lock screen.",
            intents = listOf(
                Intent("miui.intent.action.WALLPAPER_CAROUSEL_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Карусель обоев",
                "Wallpaper Carousel",
                "Уведомления",
                "Notifications"
            ),
            manualHintRu = "Настройки → Блокировка экрана → Карусель обоев → отключите.",
            manualHintEn = "Settings → Lock screen → Wallpaper Carousel → turn off.",
            drillPath = listOf(
                listOf("Блокировка экрана", "Lock screen"),
                listOf("Карусель обоев", "Wallpaper Carousel", "Glance")
            ),
            requiredPackages = listOf("com.miui.android.fashiongallery")
        ),

        Step(
            id = "shareme",
            titleRu = "Настройки ShareMe",
            titleEn = "ShareMe settings",
            descRu = "Отключаем персонализацию внутри ShareMe.",
            descEn = "Turning off personalization inside ShareMe.",
            intents = listOf(launchIntent("com.xiaomi.midrop")),
            searchTexts = listOf("Персонализация рекламы", "Ads personalization"),
            manualHintRu = "Откройте ShareMe → ⋮ → О приложении → Справка и обратная связь.",
            manualHintEn = "Open ShareMe → ⋮ → About → Help & feedback.",
            launchPackage = "com.xiaomi.midrop",
            drillPath = listOf(
                listOf("⋮", "Ещё", "More options"),
                listOf("О приложении", "About"),
                listOf("Справка и обратная связь", "Help & feedback")
            ),
            requiredPackages = listOf("com.xiaomi.midrop")
        ),

        Step(
            id = "filemanager",
            titleRu = "Уведомления Проводника",
            titleEn = "File Manager notifications",
            descRu = "Отключаем лишние уведомления Проводника.",
            descEn = "Turning off unnecessary File Manager notifications.",
            intents = listOf(notificationsIntent("com.mi.android.globalFileexplorer")),
            searchTexts = listOf("Уведомления", "Notifications", "Allow notifications"),
            manualHintRu = "Откроются уведомления Проводника. Выключите «Разрешить уведомления».",
            manualHintEn = "File Manager notification settings will open. Turn off \"Allow notifications\".",
            requiredPackages = listOf(
                "com.mi.android.globalFileexplorer",
                "com.android.fileexplorer"
            )
        ),

        Step(
            id = "msf",
            titleRu = "Xiaomi Service Framework",
            titleEn = "Xiaomi Service Framework",
            descRu = "Системная служба Xiaomi. Ограничиваем её уведомления.",
            descEn = "Xiaomi system service. Limiting its notifications.",
            intents = listOf(notificationsIntent("com.xiaomi.xmsf")),
            searchTexts = listOf("Уведомления", "Notifications", "Allow notifications"),
            manualHintRu = "Откроются уведомления Xiaomi Service Framework. Выключите «Разрешить уведомления».",
            manualHintEn = "Xiaomi Service Framework notification settings will open. Turn off \"Allow notifications\".",
            requiredPackages = listOf("com.xiaomi.xmsf")
        )
    )

    private fun Intent.addFlags(flags: Int): Intent = this.apply { addFlags(flags) }
}