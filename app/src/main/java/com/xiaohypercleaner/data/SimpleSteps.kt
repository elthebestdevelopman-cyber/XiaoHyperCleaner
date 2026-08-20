package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

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
        val warningRu: String? = null,  // Текст предупреждения для CONDITIONAL/HIGH
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
        // 1. MSA — системный сервис рекомендаций MIUI
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "msa",
            titleRu = "Системные рекомендации (MSA)",
            titleEn = "System recommendations (MSA)",
            descRu = "Системный сервис рекомендаций MIUI. Отключаем его для повышения приватности.",
            descEn = "MIUI system recommendations service. Turning it off improves privacy.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            // searchTexts — системные ярлыки для поиска элементов на экранах MIUI,
            // пользователю не показываются
            searchTexts = listOf(
                "MSA", "msa",
                "Системная реклама", "System ads",
                "Ad services", "Рекламные службы",
                "Разрешить MSA", "Allow MSA",
                "Служба MSA", "MSA service",
                "miui-ad", "xiaomi-ad"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → найдите пункт MSA и отключите его.\n\nЕсли такого пункта нет — пропустите шаг.",
            manualHintEn = "Settings → Passwords & security → Privacy → find the MSA item and turn it off.\n\nIf this item doesn't exist — skip this step."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 2. Персональные рекомендации (персонализация)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "personalized",
            titleRu = "Персональные рекомендации",
            titleEn = "Personalized recommendations",
            descRu = "Системный сервис персональных рекомендаций. Отключаем для повышения приватности.",
            descEn = "System personalized recommendations service. Turning it off improves privacy.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "Personalized recommendations", "Персональные рекомендации",
                "Рекомендации на основе интересов",
                "Targeted ads", "Таргетированная реклама",
                "Revoke", "Отозвать",
                "Получать рекомендации", "Receive recommendations",
                "Ad services", "Рекламные службы"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Персональные рекомендации (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → Personalized recommendations (off)"
        ),

        // ═══════════════════════════════════════════════════════════════
        // 3. Программа улучшения UX (телеметрия)
        // На HyperOS/Android 14 путь глубже — добавили больше intent-ов
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
        // 4. GetApps — лишние уведомления магазина
        // ✅ Безопасно: обновления приложений идут через системный механизм
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "getapps",
            titleRu = "Уведомления GetApps",
            titleEn = "GetApps notifications",
            descRu = "Отключаем лишние уведомления магазина GetApps.",
            descEn = "Turning off unnecessary GetApps store notifications.",
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
            manualHintEn = "GetApps notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 5. Mi Music — лишние уведомления
        // ⚠️ Условно безопасно: если используете Mi Music как основной плеер,
        //    потеряете уведомления о новых плейлистах/альбомах (но музыка работает)
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
                "Show notifications", "Показывать уведомлений",
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
        // 6. Themes — лишние уведомления
        // ✅ Безопасно: темы можно применять вручную через приложение
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
            manualHintEn = "Themes notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 7. File Manager — лишние уведомления
        // ✅ Безопасно: файловый менеджер работает полностью без уведомлений
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
            manualHintEn = "File Manager notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 8. Xiaomi Service Framework (MSF) — системная служба Xiaomi
        // ✅ Безопасно: отключаем только уведомления, сервисы работают
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
            manualHintEn = "Xiaomi Service Framework notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 9. Системная аналитика — приватность
        // ✅ Безопасно: отключаем сбор статистики, система работает нормально
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
            manualHintEn = "Settings → Passwords & security → Privacy → Turn off analytics collection.",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 10. Идентификатор персонализации Google
        // ✅ Безопасно: можно сбросить или отключить без последствий
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
                "Ads", "Реклама",
                "Advertising ID", "Рекламный идентификатор",
                "Reset advertising ID", "Сбросить рекламный ID",
                "Delete advertising ID", "Удалить рекламный ID",
                "Opt out of Ads Personalization", "Отказаться от персонализации рекламы"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Google → найдите пункт управления идентификатором → удалите идентификатор или отключите персонализацию.",
            manualHintEn = "Settings → Google → find the ID settings → delete the ID or turn off personalization.",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        ),

        // ═══════════════════════════════════════════════════════════════
        // 11. Smart Assistant (Mi AI) — голосовой помощник
        // ⚠️ Условно безопасно: если используете голосовые команды, потеряете уведомления
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "smart_assistant",
            titleRu = "Smart Assistant (Mi AI)",
            titleEn = "Smart Assistant (Mi AI)",
            descRu = "Голосовой помощник Xiaomi. Отключаем его уведомления.",
            descEn = "Xiaomi voice assistant. Turning off its notifications.",
            intents = listOf(
                notificationsIntent("com.miui.voiceassist"),
                appDetailsIntent("com.miui.voiceassist")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Smart Assistant", "Mi AI",
                "Голосовой помощник", "Voice assistant",
                "Mi AI", "小爱同学"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Smart Assistant. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Smart Assistant notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.CONDITIONAL,
            warningRu = "⚠️ Если вы используете голосовые команды Mi AI, вы можете потерять некоторые уведомления (но голосовое управление продолжит работать).",
            warningEn = "⚠️ If you use Mi AI voice commands, you may lose some notifications (but voice control will continue to work)."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 12. Game Turbo — лишние уведомления
        // ✅ Безопасно: игровой режим работает, отключаем только уведомления
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "game_turbo",
            titleRu = "Уведомления Game Turbo",
            titleEn = "Game Turbo notifications",
            descRu = "Отключаем лишние уведомления и рекомендации Game Turbo.",
            descEn = "Turning off unnecessary Game Turbo notifications and recommendations.",
            intents = listOf(
                notificationsIntent("com.miui.gamebooster"),
                appDetailsIntent("com.miui.gamebooster")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications",
                "Allow notifications", "Разрешить уведомления",
                "Show notifications", "Показывать уведомления",
                "Game Turbo", "Игровой режим",
                "Game Booster", "Игры",
                "Recommendations", "Рекомендации"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Game Turbo. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Game Turbo notification settings will open. Turn off \"Allow notifications\".",
            riskLevel = RiskLevel.SAFE,
            warningRu = null,
            warningEn = null
        )
    )

    private fun Intent.addFlags(flags: Int): Intent = this.apply { addFlags(flags) }
}