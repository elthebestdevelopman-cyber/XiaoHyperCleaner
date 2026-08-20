package com.xiaohypercleaner.data

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Шаги простой автоматизации, составленные по инструкции сообщества Xiaomi
 *
 * Порядок: сначала ядро приватности (msa, рекомендации, персонализация,
 * телеметрия), затем уведомления системных приложений, которые шлют
 * рекомендации и промо-контент.
 *
 * ВАЖНО (store compliance): пользовательские тексты (title/desc/warning)
 * нейтральные — без слова «реклама». Системные ярлыки («Рекламные службы»,
 * «Показывать рекламу») присутствуют ТОЛЬКО в searchTexts — они не показываются
 * пользователю и нужны, чтобы Accessibility нашёл переключатель на экране MIUI.
 */
object SimpleSteps {

    /**
     * Уровень риска отключения уведомлений/функций
     */
    enum class RiskLevel {
        SAFE,           // ✅ Безопасно — никаких последствий
        CONDITIONAL,    // ⚠️ Условно безопасно — есть нюансы
        HIGH            // 🔴 Высокий риск — требует подтверждения
    }

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
        val warningEn: String? = null
    )

    /**
     * Создаёт intent для экрана уведомлений конкретного приложения.
     * КРИТИЧНО: на MIUI/HyperOS нужны ОБА ключа EXTRA_APP_PACKAGE,
     * иначе откроется общий экран уведомлений без нужного switch.
     */
    private fun notificationsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)            // стандартный
            putExtra("android.provider.extra.APP_PACKAGE", packageName)  // MIUI fallback
            putExtra("app_package", packageName)                         // старый вариант
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Intent для экрана "О приложении" — нужен как fallback */
    private fun appDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    val ALL: List<Step> = listOf(

        // ═══════════════════════════════════════════════════════════════
        // 1. MSA — отзыв разрешения (пункт 1 инструкции)
        // Настройки → Пароли и безопасность → Авторизация и отзыв → msa
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "msa",
            titleRu = "Системный сервис MSA",
            titleEn = "MSA system service",
            descRu = "Главный сервис системных рекомендаций MIUI. Отзываем его разрешение в разделе «Авторизация и отзыв».",
            descEn = "The main MIUI recommendations service. We revoke its permission in Authorization & revocation.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "msa", "MSA",
                "Отозвать", "Revoke",
                "Авторизация и отзыв", "Authorization & revocation",
                "Authorization and revocation",
                "Доступ к личным данным", "Access to personal data"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Отпечатки и защита → Авторизация и отзыв → найдите msa → отзовите разрешение.\n\nПоявится подтверждение с отсчётом 10 секунд — дождитесь и нажмите «Отозвать».",
            manualHintEn = "Settings → Passwords & security → Authorization & revocation → find msa → revoke.\n\nA 10-second countdown confirmation will appear — wait and tap Revoke.",
            riskLevel = RiskLevel.CONDITIONAL,
            warningRu = "⚠️ После отзыва отключатся рекомендации в системных приложениях. Подтверждение появляется с отсчётом 10 секунд — подтвердите его вручную, если автоматика не успела.",
            warningEn = "⚠️ After revoking, system app recommendations will be disabled. The confirmation has a 10-second countdown — confirm manually if automation didn't make it."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 2. «Получать рекомендации» (пункт 2 инструкции)
        // Настройки → Приложения → ⋮ → Прочие настройки → Получать рекомендации
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "personalized",
            titleRu = "Системные рекомендации",
            titleEn = "System recommendations",
            descRu = "Отключаем системный пункт «Получать рекомендации» в прочих настройках приложений.",
            descEn = "Turning off the system \"Receive recommendations\" item in app additional settings.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "Персональные рекомендации", "Personalized recommendations",
                "Рекомендации на основе интересов",
                "Отозвать", "Revoke"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Приложения → ⋮ → Прочие настройки → выключите «Получать рекомендации».",
            manualHintEn = "Settings → Apps → ⋮ → Additional settings → turn off \"Receive recommendations\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 3. Персонализация контента (пункт 3 инструкции)
        // Настройки → Конфиденциальность → Рекламные службы → персонализация
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "ads_personalization",
            titleRu = "Персонализация контента",
            titleEn = "Content personalization",
            descRu = "Отключаем персонализацию в разделе системных служб контента.",
            descEn = "Turning off personalization in the system content services section.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Персонализация рекламы", "Personalization of ads",
                "Ads personalization", "Opt out of Ads Personalization",
                "Рекламные службы", "Ad services",
                "Ограничить отслеживание", "Limit ad tracking"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Отпечатки и защита → Конфиденциальность → Рекламные службы → отключите персонализацию.",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → turn off personalization."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 4. Программа улучшения UX (телеметрия)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения UX",
            titleEn = "User Experience Program",
            descRu = "Программа улучшения UX отправляет статистику использования. Отключаем.",
            descEn = "The User Experience Program sends usage statistics. Turning it off.",
            intents = listOf(
                Intent("miui.intent.action.USER_EXPERIENCE_PROGRAM").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent("miui.intent.action.DIAGNOSTIC_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent("miui.intent.action.PRIVACY_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "User Experience Program", "Программа улучшения UX",
                "Программа улучшения качества", "Программа улучшения пользовательского опыта",
                "Отправка данных", "Join User Experience Program",
                "Присоединиться к программе", "Передача данных",
                "Data collection", "Диагностика", "Diagnostics",
                "Аналитика", "Analytics", "Отправлять данные", "Send data",
                "Использование", "Usage", "User Experience", "Программа UX",
                "Улучшить MIUI", "Improve MIUI", "Feedback program",
                "Программа обратной связи", "Отправлять статистику",
                "Join program", "Отправка данных использования"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Программа улучшения UX (выкл.)\n\nНа HyperOS: Настройки → О телефоне → Диагностика и обратная связь",
            manualHintEn = "Settings → Passwords & security → Privacy → User Experience Program (off)\n\nOn HyperOS: Settings → About phone → Diagnostics & feedback"
        ),

        // ═══════════════════════════════════════════════════════════════
        // 5. Системная аналитика
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "analytics",
            titleRu = "Системная аналитика",
            titleEn = "System Analytics",
            descRu = "Сбор статистики использования системы. Отключаем.",
            descEn = "System usage statistics collection. Turning off.",
            intents = listOf(
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent("miui.intent.action.PRIVACY_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Аналитика", "Analytics",
                "Диагностика", "Diagnostics",
                "Использование", "Usage",
                "Статистика", "Statistics",
                "Отправлять данные", "Send data",
                "Конфиденциальность", "Privacy"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Отключите сбор аналитики.",
            manualHintEn = "Settings → Passwords & security → Privacy → Turn off analytics collection."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 6. Идентификатор персонализации Google
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "ads_id",
            titleRu = "Идентификатор персонализации Google",
            titleEn = "Google personalization ID",
            descRu = "Идентификатор, который используется для подбора контента. Сбрасываем или отключаем.",
            descEn = "Identifier used for content selection. Resetting or disabling it.",
            intents = listOf(
                Intent("android.settings.ADS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Advertising ID", "Рекламный идентификатор",
                "Reset advertising ID", "Сбросить рекламный ID",
                "Delete advertising ID", "Удалить рекламный ID",
                "Opt out of Ads Personalization", "Отказаться от персонализации рекламы"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Google → найдите пункт управления идентификатором → удалите идентификатор или отключите персонализацию.",
            manualHintEn = "Settings → Google → find the ID settings → delete the ID or turn off personalization."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 7. GetApps — уведомления и рекомендации (пункты 4, 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "getapps",
            titleRu = "Уведомления GetApps",
            titleEn = "GetApps notifications",
            descRu = "Отключаем лишние уведомления и рекомендации магазина GetApps.",
            descEn = "Turning off unnecessary GetApps store notifications and recommendations.",
            intents = listOf(
                notificationsIntent("com.xiaomi.market"),
                appDetailsIntent("com.xiaomi.market")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "All GetApps notifications",
                "Allow GetApps to send notifications",
                "Разрешить GetApps отправлять уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления GetApps. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "GetApps notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 8. Темы — уведомления (пункты 4, 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "themes",
            titleRu = "Уведомления Темы",
            titleEn = "Themes notifications",
            descRu = "Отключаем лишние уведомления приложения Темы.",
            descEn = "Turning off unnecessary Themes app notifications.",
            intents = listOf(
                notificationsIntent("com.android.thememanager"),
                appDetailsIntent("com.android.thememanager")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Темы. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Themes notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 9. Mi Music — уведомления (пункты 4, 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "music",
            titleRu = "Уведомления Mi Music",
            titleEn = "Mi Music notifications",
            descRu = "Отключаем лишние уведомления Mi Music.",
            descEn = "Turning off unnecessary Mi Music notifications.",
            intents = listOf(
                notificationsIntent("com.miui.player"),
                appDetailsIntent("com.miui.player")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "Allow Mi Music to send notifications"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Mi Music. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Mi Music notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.CONDITIONAL,
            warningRu = "⚠️ Если вы используете Mi Music как основной плеер, вы потеряете уведомления о новых плейлистах и альбомах (но музыка будет работать).",
            warningEn = "⚠️ If you use Mi Music as your main player, you will lose notifications about new playlists and albums (but music playback will work normally)."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 10. Mi Браузер — уведомления (пункт 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "browser",
            titleRu = "Уведомления Mi Браузера",
            titleEn = "Mi Browser notifications",
            descRu = "Отключаем уведомления и рекомендации Mi Браузера.",
            descEn = "Turning off Mi Browser notifications and recommendations.",
            intents = listOf(
                notificationsIntent("com.android.browser"),
                appDetailsIntent("com.android.browser")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Mi Браузера. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Mi Browser notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 11. Mi Видео — уведомления (пункт 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "video",
            titleRu = "Уведомления Mi Видео",
            titleEn = "Mi Video notifications",
            descRu = "Отключаем уведомления и рекомендации Mi Видео.",
            descEn = "Turning off Mi Video notifications and recommendations.",
            intents = listOf(
                notificationsIntent("com.miui.videoplayer"),
                appDetailsIntent("com.miui.videoplayer")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Mi Видео. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Mi Video notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 12. Игровой центр — уведомления (пункт 11 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "gamecenter",
            titleRu = "Уведомления Игрового центра",
            titleEn = "Game Center notifications",
            descRu = "Отключаем уведомления и промо-контент Игрового центра.",
            descEn = "Turning off Game Center notifications and promo content.",
            intents = listOf(
                notificationsIntent("com.xiaomi.gamecenter"),
                appDetailsIntent("com.xiaomi.gamecenter")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "Game Center", "Игровой центр"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Игрового центра. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Game Center notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 13. Лента виджетов (App Vault) — уведомления (пункт 8 инструкции)
        // Пакет подтверждён: com.miui.personalassistant
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "appvault",
            titleRu = "Лента виджетов (App Vault)",
            titleEn = "App Vault (widget feed)",
            descRu = "Отключаем уведомления и предложения ленты виджетов на рабочем столе.",
            descEn = "Turning off widget feed notifications and suggestions on the home screen.",
            intents = listOf(
                notificationsIntent("com.miui.personalassistant"),
                appDetailsIntent("com.miui.personalassistant")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "App Vault", "Лента виджетов",
                "Предложения", "Suggestions"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Ленты виджетов. Выключите переключатель «Разрешить уведомления».\n\nДополнительно: Лента виджетов → ⋮ → Управление службами → выключите «Предложения».",
            manualHintEn = "App Vault notification settings will open. Turn off \"Allow notifications\".\n\nAdditionally: App Vault → ⋮ → Service management → turn off \"Suggestions\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 14. Карусель обоев — уведомления на экране блокировки
        // (пункт 6 инструкции). Пакет подтверждён: com.miui.android.fashiongallery
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "carousel",
            titleRu = "Карусель обоев",
            titleEn = "Wallpaper Carousel",
            descRu = "Отключаем уведомления сервиса «Карусель обоев» на экране блокировки.",
            descEn = "Turning off Wallpaper Carousel notifications on the lock screen.",
            intents = listOf(
                notificationsIntent("com.miui.android.fashiongallery"),
                appDetailsIntent("com.miui.android.fashiongallery")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "Карусель обоев", "Wallpaper Carousel"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления «Карусели обоев». Выключите переключатель.\n\nДополнительно: Настройки → Блокировка экрана → Карусель обоев → отключите.",
            manualHintEn = "Wallpaper Carousel notification settings will open. Turn off the switch.\n\nAdditionally: Settings → Lock screen → Wallpaper Carousel → turn off."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 15. Проводник — уведомления (пункт 5 инструкции)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "filemanager",
            titleRu = "Уведомления Проводника",
            titleEn = "File Manager notifications",
            descRu = "Отключаем лишние уведомления Проводника.",
            descEn = "Turning off unnecessary File Manager notifications.",
            intents = listOf(
                notificationsIntent("com.mi.android.globalFileexplorer"),
                notificationsIntent("com.android.fileexplorer"),
                appDetailsIntent("com.mi.android.globalFileexplorer"),
                appDetailsIntent("com.android.fileexplorer")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Проводника. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "File Manager notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 16. Xiaomi Service Framework — уведомления
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "msf",
            titleRu = "Xiaomi Service Framework",
            titleEn = "Xiaomi Service Framework",
            descRu = "Системная служба Xiaomi. Ограничиваем её уведомления.",
            descEn = "Xiaomi system service. Limiting its notifications.",
            intents = listOf(
                notificationsIntent("com.xiaomi.xmsf"),
                appDetailsIntent("com.xiaomi.xmsf")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "Xiaomi Service Framework", "Службы Xiaomi"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Xiaomi Service Framework. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Xiaomi Service Framework notification settings will open. Turn off \"Allow notifications\"."
        )
    )

    private fun Intent.addFlags(flags: Int): Intent = this.apply { addFlags(flags) }
}