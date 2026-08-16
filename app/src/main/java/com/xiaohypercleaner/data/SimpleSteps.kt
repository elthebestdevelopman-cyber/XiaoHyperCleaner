package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri
import android.provider.Settings

object SimpleSteps {

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
        val manualHintEn: String
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
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    val ALL: List<Step> = listOf(

        // ═══════════════════════════════════════════════════════════════
        // 1. MSA — главная служба рекламы Xiaomi
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "msa",
            titleRu = "Системная реклама (MSA)",
            titleEn = "System ads (MSA)",
            descRu = "MSA — сервис Xiaomi который показывает рекламу в системных приложениях. Отключаем его.",
            descEn = "MSA is Xiaomi's ad service. Turning it off removes most system ads.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
            searchTexts = listOf(
                "MSA", "msa",
                "Системная реклама", "System ads",
                "Ad services", "Рекламные службы",
                "Разрешить MSA", "Allow MSA",
                "Служба MSA", "MSA service",
                "miui-ad", "xiaomi-ad"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → MSA (выкл.)\n\nЕсли этого пути нет — пропустите шаг.",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → MSA (off)\n\nIf this path doesn't exist — skip this step."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 2. Персональные рекомендации (таргетинг)
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "personalized",
            titleRu = "Персональные рекомендации",
            titleEn = "Personalized recommendations",
            descRu = "Xiaomi собирает данные для таргетированной рекламы. Отключаем.",
            descEn = "Xiaomi collects data for targeted ads. Turning off.",
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
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → Персональные рекомендации (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → Personalized recommendations (off)"
        ),

        // ═══════════════════════════════════════════════════════════════
        // 3. Программа улучшения UX (телеметрия)
        // На HyperOS/Android 14 путь глубже — добавили больше intent-ов
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения UX",
            titleEn = "User Experience Program",
            descRu = "Отправка статистики в Xiaomi. Отключаем.",
            descEn = "Usage data sent to Xiaomi. Turning off.",
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
        // 4. GetApps — уведомления-спам от магазина
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "getapps",
            titleRu = "Уведомления GetApps",
            titleEn = "GetApps notifications",
            descRu = "Магазин GetApps спамит уведомлениями. Отключаем.",
            descEn = "GetApps spams notifications. Turning off.",
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
        // 5. Mi Music — рекламные уведомления
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "music",
            titleRu = "Уведомления Mi Music",
            titleEn = "Mi Music notifications",
            descRu = "Mi Music спамит рекламой в уведомлениях. Отключаем.",
            descEn = "Mi Music spams ads in notifications. Turning off.",
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
            manualHintEn = "Mi Music notification settings will open. Turn off \"Allow notifications\"."
        ),

        // ═══════════════════════════════════════════════════════════════
        // 6. Themes — рекламные уведомления
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "themes",
            titleRu = "Уведомления Темы",
            titleEn = "Themes notifications",
            descRu = "Приложение Темы спамит уведомлениями. Отключаем.",
            descEn = "Themes app spams notifications. Turning off.",
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
        // 7. File Manager — уведомления-спам
        // ═══════════════════════════════════════════════════════════════
        Step(
            id = "filemanager",
            titleRu = "Уведомления Проводника",
            titleEn = "File Manager notifications",
            descRu = "Проводник спамит рекомендациями. Отключаем.",
            descEn = "File Manager spams recommendations. Turning off.",
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
        )
    )

    private fun Intent.addFlags(flags: Int): Intent = this.apply { addFlags(flags) }
}