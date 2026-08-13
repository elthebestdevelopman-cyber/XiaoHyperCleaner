// app/src/main/java/com/xiaohypercleaner/data/SimpleSteps.kt

package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri

/**
 * Шаги простой оптимизации — только переключение тумблеров через UI.
 * Не требует ADB, Shizuku, root. Только Accessibility Service.
 *
 * Работает на MIUI 12-14 и HyperOS.
 */
object SimpleSteps {

    data class Step(
        val id: String,
        val titleRu: String,
        val titleEn: String,
        val descRu: String,
        val descEn: String,
        val intent: Intent,
        /** Тексты по которым ищем тумблер (на разных MIUI могут отличаться) */
        val searchTexts: List<String>,
        /** Если true — тумблер нужно ВКЛЮЧИТЬ, false — ВЫКЛЮЧИТЬ */
        val targetChecked: Boolean = false
    )

    val ALL: List<Step> = listOf(

        // 1. MSA — главный источник системной рекламы Xiaomi
        Step(
            id = "msa",
            titleRu = "Системная реклама (MSA)",
            titleEn = "System ads (MSA)",
            descRu = "MSA — сервис Xiaomi который показывает рекламу в системных приложениях. Отключаем его.",
            descEn = "MSA is Xiaomi's ad service. Turning it off removes most system ads.",
            intent = Intent("miui.intent.action.AD_SERVICES_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf("MSA", "msa", "Системная реклама", "System ads"),
            targetChecked = false
        ),

        // 2. Персональные рекомендации
        Step(
            id = "personalized",
            titleRu = "Персональные рекомендации",
            titleEn = "Personalized recommendations",
            descRu = "Xiaomi собирает данные для таргетированной рекламы. Отключаем.",
            descEn = "Xiaomi collects data for targeted ads. Turning off.",
            intent = Intent("miui.intent.action.AD_SERVICES_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "Personalized recommendations",
                "Персональные рекомендации",
                "Рекомендации на основе интересов"
            ),
            targetChecked = false
        ),

        // 3. Программа улучшения UX (телеметрия)
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения UX",
            titleEn = "User Experience Program",
            descRu = "Отправка статистики в Xiaomi. Отключаем.",
            descEn = "Usage data sent to Xiaomi. Turning off.",
            intent = Intent("miui.intent.action.PRIVACY_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "User Experience Program",
                "Программа улучшения UX",
                "Программа улучшения качества"
            ),
            targetChecked = false
        ),

        // 4. GetApps — реклама в магазине Xiaomi
        Step(
            id = "getapps",
            titleRu = "Реклама в GetApps",
            titleEn = "GetApps ads",
            descRu = "Магазин GetApps показывает уведомления и рекламу. Отключаем.",
            descEn = "GetApps shows notifications and ads. Turning off.",
            intent = Intent().apply {
                setClassName("com.xiaomi.market", "com.xiaomi.market.ui.settings.SettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "Recommendations",
                "Рекомендации",
                "Push notifications",
                "Push-уведомления",
                "Promotions",
                "Акции"
            ),
            targetChecked = false
        ),

        // 5. Реклама в Music
        Step(
            id = "music",
            titleRu = "Реклама в Mi Music",
            titleEn = "Mi Music ads",
            descRu = "Mi Music показывает рекламу между треками. Отключаем.",
            descEn = "Mi Music shows ads between tracks. Turning off.",
            intent = Intent().apply {
                setClassName("com.miui.player", "com.miui.player.ui.settings.SettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "Show ads",
                "Показывать рекламу",
                "Ads",
                "Реклама",
                "Recommendations",
                "Рекомендации"
            ),
            targetChecked = false
        ),

        // 6. Реклама в Themes
        Step(
            id = "themes",
            titleRu = "Реклама в Темы",
            titleEn = "Themes ads",
            descRu = "Приложение Темы показывает рекламу. Отключаем.",
            descEn = "Themes app shows ads. Turning off.",
            intent = Intent().apply {
                setClassName(
                    "com.android.thememanager",
                    "com.android.thememanager.modules.settings.SettingsActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "Show ads",
                "Показывать рекламу",
                "Recommendations",
                "Рекомендации",
                "Ads",
                "Реклама"
            ),
            targetChecked = false
        ),

        // 7. Реклама в File Manager
        Step(
            id = "filemanager",
            titleRu = "Реклама в Проводнике",
            titleEn = "File Manager ads",
            descRu = "Проводник показывает рекомендации. Отключаем.",
            descEn = "File Manager shows recommendations. Turning off.",
            intent = Intent().apply {
                setClassName(
                    "com.mi.android.globalFileexplorer",
                    "com.android.fileexplorer.settings.SettingsActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            searchTexts = listOf(
                "Recommendations",
                "Рекомендации",
                "Show ads",
                "Показывать рекламу"
            ),
            targetChecked = false
        )
    )
}