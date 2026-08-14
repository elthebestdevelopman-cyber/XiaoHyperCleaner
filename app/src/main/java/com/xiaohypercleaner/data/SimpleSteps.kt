package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Шаги простой оптимизации — только переключение тумблеров через UI.
 * Не требует ADB, Shizuku, root. Только Accessibility Service.
 *
 * Использует ТОЛЬКО стандартные Android intent-ы + fallback-и.
 * Работает на всех версиях MIUI/HyperOS/чистого Android.
 */
object SimpleSteps {

    data class Step(
        val id: String,
        val titleRu: String,
        val titleEn: String,
        val descRu: String,
        val descEn: String,
        /** Массив intent-ов: пробуем по очереди, первый рабочий используем */
        val intents: List<Intent>,
        /** Тексты по которым ищем тумблер (на разных MIUI могут отличаться) */
        val searchTexts: List<String>,
        /** Если true — тумблер нужно ВКЛЮЧИТЬ, false — ВЫКЛЮЧИТЬ */
        val targetChecked: Boolean = false,
        /** Подсказка для пользователя если авто-отключение не сработало */
        val manualHintRu: String,
        val manualHintEn: String
    )

    private fun appDetailsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    val ALL: List<Step> = listOf(

        // 1. MSA — главный источник системной рекламы Xiaomi
        Step(
            id = "msa",
            titleRu = "Системная реклама (MSA)",
            titleEn = "System ads (MSA)",
            descRu = "MSA — сервис Xiaomi который показывает рекламу в системных приложениях. Отключаем его.",
            descEn = "MSA is Xiaomi's ad service. Turning it off removes most system ads.",
            intents = listOf(
                // Сначала пробуем MIUI-specific intent
                Intent("miui.intent.action.AD_SERVICES_SETTINGS").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                // Fallback: открываем главные настройки, там Accessibility найдёт MSA
                Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ),
            searchTexts = listOf(
                "MSA",
                "msa",
                "Системная реклама",
                "System ads",
                "Ad services",
                "Рекламные службы"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Рекламные службы → MSA (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → Ad services → MSA (off)"
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
                "Рекомендации на основе интересов", "Targeted ads", "Таргетированная реклама",
                "Revoke authorization", "Отозвать авторизацию"
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
                "Программа улучшения качества", "Отправка данных",
                "Join User Experience Program", "Присоединиться к программе"
            ),
            targetChecked = false,
            manualHintRu = "Настройки → Пароли и безопасность → Конфиденциальность → Программа улучшения UX (выкл.)",
            manualHintEn = "Settings → Passwords & security → Privacy → User Experience Program (off)"
        ),

        // 4. GetApps — реклама в магазине Xiaomi
        // Открываем экран информации о приложении — там есть "Уведомления"
        Step(
            id = "getapps",
            titleRu = "Реклама в GetApps",
            titleEn = "GetApps ads",
            descRu = "Магазин GetApps показывает уведомления и рекламу. Отключаем.",
            descEn = "GetApps shows notifications and ads. Turning off.",
            intents = listOf(
                appDetailsIntent("com.xiaomi.market")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications", "Показывать уведомления",
                "Allow notifications", "Push-уведомления", "Рекомендации"
            ),
            targetChecked = false,
            manualHintRu = "Откроется экран приложения GetApps. Отключите «Уведомления».",
            manualHintEn = "GetApps app info will open. Turn off \"Notifications\"."
        ),

        // 5. Реклама в Mi Music
        Step(
            id = "music",
            titleRu = "Реклама в Mi Music",
            titleEn = "Mi Music ads",
            descRu = "Mi Music показывает рекламу между треками. Отключаем.",
            descEn = "Mi Music shows ads between tracks. Turning off.",
            intents = listOf(
                appDetailsIntent("com.miui.player")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications", "Показывать уведомления",
                "Allow notifications", "Реклама", "Ads"
            ),
            targetChecked = false,
            manualHintRu = "Откроется экран Mi Music. Отключите «Уведомления».",
            manualHintEn = "Mi Music app info will open. Turn off \"Notifications\"."
        ),

        // 6. Реклама в Themes
        Step(
            id = "themes",
            titleRu = "Реклама в Темы",
            titleEn = "Themes ads",
            descRu = "Приложение Темы показывает рекламу. Отключаем.",
            descEn = "Themes app shows ads. Turning off.",
            intents = listOf(
                appDetailsIntent("com.android.thememanager")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications", "Показывать уведомления",
                "Allow notifications", "Рекомендации", "Реклама"
            ),
            targetChecked = false,
            manualHintRu = "Откроется экран приложения Темы. Отключите «Уведомления».",
            manualHintEn = "Themes app info will open. Turn off \"Notifications\"."
        ),

        // 7. Реклама в File Manager
        Step(
            id = "filemanager",
            titleRu = "Реклама в Проводнике",
            titleEn = "File Manager ads",
            descRu = "Проводник показывает рекомендации. Отключаем.",
            descEn = "File Manager shows recommendations. Turning off.",
            intents = listOf(
                appDetailsIntent("com.mi.android.globalFileexplorer"),
                // Fallback для других вариантов package name
                appDetailsIntent("com.android.fileexplorer")
            ),
            searchTexts = listOf(
                "Уведомления", "Notifications", "Показывать уведомления",
                "Allow notifications", "Рекомендации"
            ),
            targetChecked = false,
            manualHintRu = "Откроется экран Проводника. Отключите «Уведомления».",
            manualHintEn = "File Manager app info will open. Turn off \"Notifications\"."
        )
    )
}