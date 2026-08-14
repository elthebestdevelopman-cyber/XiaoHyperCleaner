package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri
import android.os.Build
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

    /** Открыть страницу уведомлений приложения напрямую */
    private fun notificationsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Открыть информацию о приложении (fallback) */
    private fun appDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    val ALL: List<Step> = listOf(

        // 1. MSA — главная реклама Xiaomi
        Step(
            id = "msa",
            titleRu = "Системная реклама (MSA)",
            titleEn = "System ads (MSA)",
            descRu = "MSA — сервис Xiaomi который показывает рекламу в системных приложениях. Отключаем его.",
            descEn = "MSA is Xiaomi's ad service. Turning it off removes most system ads.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_PRIVACY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ),
            searchTexts = listOf(
                "MSA", "msa",
                "Системная реклама", "System ads",
                "Ad services", "Рекламные службы",
                "Разрешить MSA", "Allow MSA",
                "Служба MSA", "MSA service"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → MSA (выкл.)\n\nЕсли этого пути нет — пропустите шаг.",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → MSA (off)\n\nIf this path doesn't exist — skip this step."
        ),

        // 2. Персональные рекомендации
        Step(
            id = "personalized",
            titleRu = "Персональные рекомендации",
            titleEn = "Personalized recommendations",
            descRu = "Xiaomi собирает данные для таргетированной рекламы. Отключаем.",
            descEn = "Xiaomi collects data for targeted ads. Turning off.",
            intents = listOf(
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_PRIVACY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ),
            searchTexts = listOf(
                "Personalized recommendations", "Персональные рекомендации",
                "Рекомендации на основе интересов",
                "Targeted ads", "Таргетированная реклама",
                "Revoke", "Отозвать",
                "Получать рекомендации", "Receive recommendations"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → Персональные рекомендации (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → Personalized recommendations (off)"
        ),

        // 3. Программа улучшения UX (телеметрия)
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения UX",
            titleEn = "User Experience Program",
            descRu = "Отправка статистики в Xiaomi. Отключаем.",
            descEn = "Usage data sent to Xiaomi. Turning off.",
            intents = listOf(
                Intent("miui.intent.action.PRIVACY_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_PRIVACY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ),
            searchTexts = listOf(
                "User Experience Program", "Программа улучшения UX",
                "Программа улучшения качества",
                "Отправка данных", "Join User Experience Program",
                "Присоединиться к программе",
                "Передача данных", "Data collection"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Программа улучшения UX (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → User Experience Program (off)"
        ),

        // 4. GetApps — открываем НАПРЯМУЮ страницу уведомлений
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
                "All GetApps notifications"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления GetApps. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "GetApps notification settings will open. Turn off \"Allow notifications\"."
        ),

        // 5. Mi Music — напрямую уведомления
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
                "Show notifications", "Показывать уведомления"
            ),
            targetChecked = false,
            manualHintRu = "Откроются уведомления Mi Music. Выключите переключатель «Разрешить уведомления».",
            manualHintEn = "Mi Music notification settings will open. Turn off \"Allow notifications\"."
        ),

        // 6. Themes — напрямую уведомления
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

        // 7. File Manager — напрямую уведомления
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
}