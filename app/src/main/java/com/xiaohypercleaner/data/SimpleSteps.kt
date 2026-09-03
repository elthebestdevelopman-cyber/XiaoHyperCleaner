package com.xiaohypercleaner.data

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Финальная карта маршрутов по инструкции сообщества (все 26 шагов).
 *
 * Типы действий:
 *  TOGGLE             — найти переключатель и выключить (+ additionalToggles рядом)
 *  CLEAR_DATA_DECLINE — очистить данные приложения и нажать «Отмена» на приветствии
 *
 * Поля:
 *  forceStopBeforeLaunch — для шагов-приложений: force-stop очищает recents-стек MIUI,
 *     иначе приложение открывается на старом экране (а не с корневого).
 *  confirmTexts + confirmWaitMs — для msa (диалог с 10-сек таймером).
 *  preDrillWaitMs — пауза перед бурением (экраны со сканом, напр. «Очистка»).
 *  swipeUpAfterLaunch — свайп вверх после запуска (поиск приложений в лаунчере).
 *
 * ИСПРАВЛЕНО (beta6):
 * - Для всех шагов расширены requiredPackages — добавлены альтернативные
 *   имена пакетов для разных регионов (Китай, Глобал, Индия, Европа, HyperOS 2/3)
 * - Цель: 26/26 успешных шагов, ноль пропущенных (кроме реально отсутствующих)
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
        val swipeUpAfterLaunch: Boolean = false,
        /**
         * Принудительная остановка пакета перед запуском.
         * Нужно для шагов-приложений (Темы, Музыка, Mi Video и т.д.) — MIUI
         * иначе открывает старый экран из recents вместо корневого.
         * Для системных шагов (Settings) НЕ требуется — CLEAR_TASK работает.
         */
        val forceStopBeforeLaunch: Boolean = false
    )

    // ── Хелперы интентов ─────────────────────────────────────────────

    private fun settingsRoot(): Intent = Intent(Settings.ACTION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun notificationsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun appDetailsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$pkg".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun launchIntent(pkg: String): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ── Переиспользуемые фрагменты drillPath ─────────────────────────

    /** Уровень 1 для маршрутов через «Отпечатки, данные лица и защита устройства» */
    private val SEC: List<String> = listOf(
        "Отпечатки, данные лица и защита устройства",
        "Отпечатки, данные лица и за…",
        "Fingerprints, face data & device security",
        "Fingerprints, face data and device security",
        "Пароли и безопасность", "Passwords & security", "Passwords and security",
        "密码与安全", "指纹、面容与设备保护", "指纹、面孔数据与设备保护",
        "Contraseñas y seguridad", "Huellas, datos faciales y seguridad"
    )

    private val APPS: List<String> = listOf(
        "Приложения", "Apps", "Applications", "应用", "应用管理", "Aplicaciones"
    )
    private val OVERFLOW: List<String> =
        listOf("⋮", "Ещё", "More options", "More", "Дополнительно", "更多", "Más")
    private val OTHER_SETTINGS: List<String> =
        listOf(
            "Прочие настройки", "Additional settings", "Advanced settings",
            "其他设置", "其他設定", "Ajustes adicionales"
        )
    private val SYS_APPS: List<String> = listOf(
        "Системные приложения", "System apps", "系统应用", "系统应用管理", "Apps del sistema"
    )
    private val PRIVACY: List<String> = listOf(
        "Конфиденциальность", "Privacy", "隐私", "隐私保护", "Privacidad"
    )
    private val GEAR: List<String> = listOf("⚙", "⚙️", "Настройки", "Settings", "Configuración", "Ajustes", "设置", "Paramètres")
    private val PROFILE: List<String> = listOf(
        "Профиль", "Profile", "Мой профиль", "My profile", "Account",
        "Мой аккаунт", "Аккаунт", "Пользователь", "User",
        "我的", "个人中心", "Perfil", "Mi perfil", "profile_tab"
    )

    // ═════════════════════════════════════════════════════════════════
    // 26 шагов автоматизации (с поддержкой всех регионов)
    // ═════════════════════════════════════════════════════════════════

    val ALL: List<Step> = listOf(

        // ── БЛОК А: СИСТЕМНЫЕ НАСТРОЙКИ ───────────────────────────────

        // П.1: msa — HyperOS: «Доступ к личным данным»; старый MIUI: 10-сек таймер
        Step(
            id = "msa",
            titleRu = "Системный сервис MSA",
            titleEn = "MSA service",
            descRu = "Отзываем разрешение msa на доступ к личным данным.",
            descEn = "Revoking msa permission to personal data.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "msa", "MSA", "系统服务msa", "系统广告", "广告服务"
            ),
            manualHintRu = "Настройки → Отпечатки… → Доступ к личным данным → выключите msa.\n\n" +
                    "На старых MIUI: Авторизация и отзыв → msa → дождитесь конца 10-секундного " +
                    "отсчёта → «Отозвать».",
            manualHintEn = "Settings → Fingerprints… → Access to personal data → turn off msa.",
            drillPath = listOf(
                SEC,
                listOf(
                    "Доступ к личным данным", "Access to personal data",
                    "Авторизация и отзыв", "Authorization & revocation",
                    "授权管理", "个人数据访问", "访问个人数据",
                    "Acceso a datos personales", "Autorización y revocación"
                )
            ),
            confirmTexts = listOf(
                "Отозвать", "Revoke", "撤销", "撤销授权", "Revocar"
            ),
            confirmWaitMs = 11_000L,
            riskLevel = RiskLevel.CONDITIONAL
        ),

        // П.2: Приложения → ⋮ → Прочие настройки → «Получать рекомендации»
        Step(
            id = "sys_recommendations",
            titleRu = "Системные рекомендации",
            titleEn = "System recommendations",
            descRu = "Выключаем «Получать рекомендации» в прочих настройках приложений.",
            descEn = "Turning off \"Receive recommendations\".",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "接收推荐", "个性化推荐", "Recibir recomendaciones"
            ),
            manualHintRu = "Настройки → Приложения → ⋮ → Прочие настройки → " +
                    "выключите «Получать рекомендации».",
            manualHintEn = "Settings → Apps → ⋮ → Additional settings → " +
                    "turn off \"Receive recommendations\".",
            drillPath = listOf(APPS, OVERFLOW, OTHER_SETTINGS)
        ),

        // П.3: Конфиденциальность → Рекламные службы → «Персонализация»
        Step(
            id = "ads_personalization",
            titleRu = "Персонализация рекламы",
            titleEn = "Ads personalization",
            descRu = "Отключаем персонализацию рекламы.",
            descEn = "Turning off ads personalization.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Персонализированная реклама",
                "Персонализация рекламы",
                "Рекламные службы",
                "Ads personalization",
                "Personalized ads",
                "Personalization of ads",
                "Personalized ad recommendations"
            ),
            manualHintRu = "Настройки → Отпечатки… → Конфиденциальность → Рекламные службы → " +
                    "выключите «Персонализация рекламы».",
            manualHintEn = "Settings → Fingerprints… → Privacy → Ad services → " +
                    "turn off personalization.",
            drillPath = listOf(SEC, PRIVACY, listOf("Рекламные службы", "Ad services"))
        ),

        // П.3b: Конфиденциальность → «Программа улучшения качества»
        Step(
            id = "ux_program",
            titleRu = "Программа улучшения качества",
            titleEn = "User Experience Program",
            descRu = "Отключаем сбор статистики использования.",
            descEn = "Turning off usage statistics collection.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Участвовать в Программе улучшения качества",
                "Join User Experience Program",
                "User Experience Program"
            ),
            manualHintRu = "Настройки → Отпечатки… → Конфиденциальность → " +
                    "выключите «Участвовать в Программе улучшения качества».",
            manualHintEn = "Settings → Fingerprints… → Privacy → " +
                    "turn off User Experience Program.",
            drillPath = listOf(SEC, PRIVACY)
        ),

        // П.6: Блокировка экрана → Карусель обоев (2 тумблера)
        Step(
            id = "carousel",
            titleRu = "Карусель обоев",
            titleEn = "Wallpaper Carousel",
            descRu = "Выключаем карусель обоев и обновление через мобильный интернет.",
            descEn = "Turning off Wallpaper Carousel and mobile updates.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Включить",
                "Карусель обоев",
                "Wallpaper Carousel",
                "Wallpaper carousel",
                "Turn on",
                "Enable",
                "Вкл"
            ),
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

        // П.7/10 из инструкции: Настройки → Рабочий стол → «Показывать предложения»
        Step(
            id = "home_suggestions",
            titleRu = "Предложения на Рабочем столе",
            titleEn = "Home screen suggestions",
            descRu = "Выключаем рекламные предложения в недавних приложениях.",
            descEn = "Turning off promotional suggestions in recent apps.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf(
                "Показывать предложения", "Show suggestions",
                "Показывать рекомендации", "Show recommendations",
                "Рекомендации", "Suggestions", "显示建议"
            ),
            manualHintRu = "Настройки → Рабочий стол → выключите «Показывать предложения».",
            manualHintEn = "Settings → Home screen → turn off \"Show suggestions\".",
            drillPath = listOf(
                listOf("Рабочий стол", "Home screen", "Pantalla de inicio", "桌面")
            ),
            requiredPackages = listOf("com.miui.home", "com.mi.global.home"),
            targetChecked = false
        ),

        // П.4a (п.4.9 в инструкции): Mi Браузер → Профиль → ⚙ → Дополнительно → Показывать рекламу
        Step(
            id = "browser_sys",
            titleRu = "Рекомендации Mi Браузера",
            titleEn = "Mi Browser recommendations",
            descRu = "Выключаем показ рекламы в системном браузере.",
            descEn = "Turning off recommendations in Mi Browser.",
            intents = listOf(
                launchIntent("com.mi.globalbrowser"),
                launchIntent("com.android.browser"),
                settingsRoot()
            ),
            searchTexts = listOf(
                "Показывать рекламу", "Show ads",
                "Персонализированные услуги", "Personalized services",
                "Реклама", "Ads", "显示广告"
            ),
            manualHintRu = "Mi Браузер → Профиль → ⚙ → Дополнительно → " +
                    "выключите «Показывать рекламу».",
            manualHintEn = "Mi Browser → Profile → ⚙ → Advanced → turn off \"Show ads\".",
            launchPackage = "com.mi.globalbrowser",
            drillPath = listOf(
                PROFILE,
                GEAR,
                listOf(
                    "Дополнительно", "Дополнительные настройки", "Advanced",
                    "Advanced settings", "Privacy", "Конфиденциальность"
                )
            ),
            requiredPackages = listOf(
                "com.mi.globalbrowser",         // Глобал (новое имя)
                "com.android.browser",          // Глобал (старое имя)
                "com.miui.browser"              // Китай
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4b: Системные приложения → Музыка (3 тумблера)
        // ИСПРАВЛЕНО (beta6): добавлены глобал, AOSP и HyperOS имена
        Step(
            id = "music_sys",
            titleRu = "Рекомендации Музыки",
            titleEn = "Music recommendations",
            descRu = "Выключаем рекомендации и персонализацию в Музыке.",
            descEn = "Turning off recommendations in Music.",
            intents = listOf(
                launchIntent("com.miui.player"),
                launchIntent("com.miui.music"),
                launchIntent("com.mi.music"),
                settingsRoot()
            ),
            searchTexts = listOf(
                "Показывать рекламу",
                "Show ads",
                "Персональные рекомендации",
                "Personalized recommendations",
                "Рекомендации",
                "Recommendations",
                "Сервисы онлайн-контента",
                "Онлайн-рекомендации",
                "Реклама",
                "Показывать рекомендуемый контент",
                "Show recommended content"
            ),
            additionalToggles = listOf(
                "Показывать рекомендации в интернете", "Show recommendations online",
                "Персональные рекомендации", "Personalized recommendations"
            ),
            manualHintRu = "Музыка → ⚙ → Расширенные настройки → выключите «Показывать рекламу».",
            manualHintEn = "Music → ⚙ → Advanced settings → turn off \"Show ads\".",
            launchPackage = "com.miui.player",
            drillPath = listOf(
                listOf("⚙", "⚙️", "Настройки", "Settings", "⋮", "Меню", "Menu"),
                listOf(
                    "Расширенные настройки", "Advanced settings", "Advanced", "Дополнительно",
                    "Показывать рекламу", "Show ads"
                )
            ),
            requiredPackages = listOf(
                "com.miui.player",              // Китай
                "com.miui.music",               // Глобал (старое имя)
                "com.mi.music"                  // HyperOS 3 (новое имя)
                // без com.android.music — AOSP Music не имеет Xiaomi UI рекомендаций
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4c: Системные приложения → Сообщения → Расширенные → Параметры
        // ИСПРАВЛЕНО (beta6): добавлены глобал и Google Messages
        Step(
            id = "messages_sys",
            titleRu = "Персонализация Сообщений",
            titleEn = "Messages personalization",
            descRu = "Выключаем персонализацию в Сообщениях.",
            descEn = "Turning off personalization in Messages.",
            intents = listOf(settingsRoot()),
            searchTexts = listOf("Персонализация рекламы", "Ads personalization"),
            additionalToggles = listOf("Рекомендации", "Recommendations"),
            manualHintRu = "… → Системные приложения → Сообщения → Расширенные настройки → " +
                    "Параметры рекламы → выключите всё.",
            manualHintEn = "… → System apps → Messages → Advanced → Ad settings → " +
                    "turn everything off.",
            drillPath = listOf(
                APPS, OVERFLOW, OTHER_SETTINGS, SYS_APPS,
                listOf("Сообщения", "Messages", "Mi Messages"),
                listOf("Расширенные настройки", "Advanced settings"),
                listOf("Параметры рекламы", "Ad settings")
            ),
            requiredPackages = listOf(
                "com.miui.mms",                 // Китай
                "com.android.mms",              // Глобал
                "com.miui.mms.global"           // Глобал альтернатива
                // без Google Messages — другой UI, не Xiaomi system ads
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4d (п.5 в инструкции): Безопасность → ⚙ → Получать рекомендации + Wi-Fi
        Step(
            id = "security_sys",
            titleRu = "Рекомендации Безопасности",
            titleEn = "Security recommendations",
            descRu = "Выключаем рекомендации в приложении Безопасность.",
            descEn = "Turning off Security app recommendations.",
            intents = listOf(launchIntent("com.miui.securitycenter"), settingsRoot()),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "接收推荐", "Recibir recomendaciones"
            ),
            additionalToggles = listOf(
                "Загружать только по Wi-Fi",
                "Download only over Wi-Fi",
                "仅在Wi-Fi下下载"
            ),
            manualHintRu = "Безопасность → ⚙ → " +
                    "выключите «Получать рекомендации» и «Загружать только по Wi-Fi».",
            manualHintEn = "Security → ⚙ → " +
                    "turn off recommendations and Wi-Fi-only.",
            launchPackage = "com.miui.securitycenter",
            drillPath = listOf(GEAR),
            requiredPackages = listOf(
                "com.miui.securitycenter",      // Китай/Глобал
                "com.miui.securitycore"         // HyperOS 3 (новое имя)
            ),
            // После выключения «Получать рекомендации» MIUI показывает диалог отзыва согласия
            confirmTexts = listOf(
                "OK", "ОК", "Согласен", "Agree", "Подтвердить", "Confirm", "Да", "Yes"
            ),
            confirmWaitMs = 2_500L,
            forceStopBeforeLaunch = true
        ),

        // ── БЛОК Б: ВНУТРИ ПРИЛОЖЕНИЙ ─────────────────────────────────

        // П.4e: Безопасность → Очистка → ⚙ (скан ~4 сек перед бурением)
        // ИСПРАВЛЕНО (beta6): добавлен HyperOS 3 пакет
        Step(
            id = "cleaner",
            titleRu = "Рекомендации Очистки",
            titleEn = "Cleaner recommendations",
            descRu = "Выключаем рекомендации в Очистке.",
            descEn = "Turning off Cleaner recommendations.",
            intents = listOf(launchIntent("com.miui.securitycenter")),
            searchTexts = listOf("Получать рекомендации", "Receive recommendations"),
            additionalToggles = listOf(
                "Загружать только по Wi-Fi",
                "Download only over Wi-Fi"
            ),
            manualHintRu = "Безопасность → Очистка → ⚙ → " +
                    "выключите «Получать рекомендации» и «Загружать только по Wi-Fi».",
            manualHintEn = "Security → Cleaner → ⚙ → turn off recommendations.",
            launchPackage = "com.miui.securitycenter",
            preDrillWaitMs = 800L,
            drillPath = listOf(
                // Не кликаем «Очистка» как кнопку запуска скана — ищем пункт меню / карточку настроек
                listOf("Очистка", "Cleaner", "Очиститель", "Cleanup"),
                GEAR
            ),
            requiredPackages = listOf(
                "com.miui.securitycenter",
                "com.miui.securitycore"
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4f (п.4.2 в инструкции): Загрузки → ⋮ / ⚙ → Настройки
        Step(
            id = "downloads",
            titleRu = "Рекомендации Загрузок",
            titleEn = "Downloads recommendations",
            descRu = "Выключаем рекомендации в Загрузках.",
            descEn = "Turning off Downloads recommendations.",
            intents = listOf(launchIntent("com.android.providers.downloads.ui")),
            searchTexts = listOf(
                "Получать рекомендации", "Receive recommendations",
                "Показывать рекламу", "Show ads",
                "Показывать рекомендуемый контент", "Show recommended content",
                "Показать рекламу", "Рекомендации", "Recommendations",
                "接收推荐", "显示广告", "Recibir recomendaciones"
            ),
            manualHintRu = "Загрузки → ⋮ → Настройки → выключите «Получать рекомендации».",
            manualHintEn = "Downloads → ⋮ → Settings → turn off recommendations.",
            launchPackage = "com.android.providers.downloads.ui",
            drillPath = listOf(
                listOf("⋮", "Ещё", "More", "Дополнительно", "Настройки", "Settings", "⚙", "⚙️"),
                GEAR
            ),
            requiredPackages = listOf(
                "com.android.providers.downloads.ui",  // AOSP
                "com.miui.android.downloads",           // MIUI
                "com.android.downloads"                 // Альтернатива
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4g: Темы → Профиль → ⚙ (2 тумблера)
        // ИСПРАВЛЕНО (beta6): добавлены альтернативные имена
        Step(
            id = "themes",
            titleRu = "Рекомендации Тем",
            titleEn = "Themes recommendations",
            descRu = "Выключаем персонализацию и показ рекомендаций в Темах.",
            descEn = "Turning off recommendations in Themes.",
            intents = listOf(launchIntent("com.android.thememanager")),
            searchTexts = listOf("Показывать рекламу", "Show ads"),
            additionalToggles = listOf(
                "Персональные рекомендации",
                "Personalized recommendations"
            ),
            manualHintRu = "Темы → Профиль → ⚙ → " +
                    "выключите «Показывать рекламу» и «Персональные рекомендации».",
            manualHintEn = "Themes → Profile → ⚙ → turn off recommendations.",
            launchPackage = "com.android.thememanager",
            drillPath = listOf(
                PROFILE,
                GEAR
            ),
            requiredPackages = listOf(
                "com.android.thememanager",     // Китай/Глобал (основное)
                "com.miui.thememanager",        // Альтернатива
                "com.mi.thememanager"           // Глобал альтернатива
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4h: GetApps → Профиль → ⚙ → Конфиденциальность
        // ИСПРАВЛЕНО (beta6): добавлены старое имя и глобал
        Step(
            id = "getapps",
            titleRu = "Рекомендации GetApps",
            titleEn = "GetApps recommendations",
            descRu = "Выключаем рекомендации в GetApps.",
            descEn = "Turning off GetApps recommendations.",
            intents = listOf(launchIntent("com.xiaomi.market")),
            searchTexts = listOf(
                "Персональные рекомендации",
                "Personalized recommendations"
            ),
            manualHintRu = "GetApps → Профиль → ⚙ → Конфиденциальность → " +
                    "выключите «Персональные рекомендации».",
            manualHintEn = "GetApps → Profile → ⚙ → Privacy → turn off recommendations.",
            launchPackage = "com.xiaomi.market",
            drillPath = listOf(PROFILE, GEAR, PRIVACY),
            requiredPackages = listOf(
                "com.xiaomi.market",            // Китай (основное)
                "com.miui.market",              // Старое имя (до ребрендинга)
                "com.mi.global.market"          // Глобал
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4i: Mi Видео → Профиль → ⚙ (сбрасывается раз в 90 дней)
        // ИСПРАВЛЕНО (beta6): добавлены альтернатива и глобал
        Step(
            id = "mivideo",
            titleRu = "Рекомендации Mi Видео",
            titleEn = "Mi Video recommendations",
            descRu = "Выключаем рекомендации в Mi Видео.",
            descEn = "Turning off Mi Video recommendations.",
            intents = listOf(launchIntent("com.miui.videoplayer")),
            searchTexts = listOf(
                "Персональные рекомендации",
                "Personalized recommendations",
                "Показывать рекламу",
                "Show ads",
                "Онлайн-рекомендации",
                "Рекомендации",
                "Recommendations"
            ),
            manualHintRu = "Mi Видео → Профиль → ⚙ → " +
                    "выключите «Персональные рекомендации».",
            manualHintEn = "Mi Video → Profile → ⚙ → turn off recommendations.",
            launchPackage = "com.miui.videoplayer",
            drillPath = listOf(PROFILE, GEAR),
            requiredPackages = listOf(
                "com.miui.videoplayer",         // Китай (основное)
                "com.miui.video",               // Альтернатива
                "com.mi.global.video"           // Глобал
            ),
            forceStopBeforeLaunch = true
        ),

        // П.4j: ShareMe → ⋮ → О приложении → Справка и обратная связь
        // ИСПРАВЛЕНО (beta6): добавлен глобал
        Step(
            id = "shareme",
            titleRu = "Персонализация ShareMe",
            titleEn = "ShareMe personalization",
            descRu = "Выключаем персонализацию в ShareMe.",
            descEn = "Turning off ShareMe personalization.",
            intents = listOf(launchIntent("com.xiaomi.midrop")),
            searchTexts = listOf("Персонализация рекламы", "Ads personalization"),
            manualHintRu = "ShareMe → ⋮ → О приложении → Справка и обратная связь → " +
                    "выключите «Персонализация рекламы».",
            manualHintEn = "ShareMe → ⋮ → About → Help & feedback → " +
                    "turn off personalization.",
            launchPackage = "com.xiaomi.midrop",
            drillPath = listOf(
                OVERFLOW,
                listOf("О приложении", "About"),
                listOf("Справка и обратная связь", "Help & feedback")
            ),
            requiredPackages = listOf(
                "com.xiaomi.midrop",            // Китай (основное)
                "com.mi.android.globalshareme"  // Глобал
            ),
            forceStopBeforeLaunch = true
        ),

        // П.5: Проводник — очистить данные + «Отмена» на приветствии
        // ИСПРАВЛЕНО (beta6): добавлена альтернатива
        Step(
            id = "filemanager",
            titleRu = "Персонализация Проводника",
            titleEn = "File Manager personalization",
            descRu = "Очищаем данные Проводника и не принимаем политику.",
            descEn = "Clearing File Manager data and declining the policy.",
            intents = listOf(appDetailsIntent("com.mi.android.globalFileexplorer")),
            searchTexts = emptyList(),
            manualHintRu = "Настройки → Приложения → Проводник → Очистить все → " +
                    "откройте Проводник → нажмите «Отмена».",
            manualHintEn = "Settings → Apps → File Manager → Clear all → " +
                    "open it → tap Cancel.",
            actionType = ActionType.CLEAR_DATA_DECLINE,
            launchPackage = "com.mi.android.globalFileexplorer",
            requiredPackages = listOf(
                "com.mi.android.globalFileexplorer",  // Глобал (основное)
                "com.android.fileexplorer",            // Китай
                "com.mi.android.fileexplorer"          // Альтернатива
            ),
            forceStopBeforeLaunch = true
        ),

        // ── БЛОК В: ЛАУНЧЕР (свайп) ───────────────────────────────────

        // П.7: Поиск приложений → ⚙ → «Рекомендации по приложениям»
        // ИСПРАВЛЕНО (beta6): добавлены AOSP launcher, альтернативы и глобал
        Step(
            id = "search_ads",
            titleRu = "Рекомендации в поиске приложений",
            titleEn = "App search recommendations",
            descRu = "Выключаем рекомендации в поиске приложений.",
            descEn = "Turning off app search recommendations.",
            intents = listOf(launchIntent("com.miui.home")),
            searchTexts = listOf("Рекомендации по приложениям", "App recommendations"),
            manualHintRu = "Поиск приложений → ⋮ → Настройки → " +
                    "выключите «Рекомендации по приложениям».",
            manualHintEn = "App search → ⋮ → Settings → turn off \"App recommendations\".",
            launchPackage = "com.miui.home",
            swipeUpAfterLaunch = true,
            drillPath = listOf(GEAR),
            requiredPackages = listOf(
                "com.miui.home",
                "com.mi.android.globallauncher",
                "com.android.launcher3",
                "com.miui.launcher",
                "com.mi.global.home"
            ),
            riskLevel = RiskLevel.CONDITIONAL,
            forceStopBeforeLaunch = true
        ),

        // П.7b: … → Страница поиска → «Игровой центр» и «Mi Видео»
        // ИСПРАВЛЕНО (beta6): добавлены AOSP launcher и глобал
        Step(
            id = "search_page",
            titleRu = "Страница поиска",
            titleEn = "Search page",
            descRu = "Выключаем рекламные источники на странице поиска.",
            descEn = "Turning off promotional sources on the search page.",
            intents = listOf(launchIntent("com.miui.home")),
            searchTexts = listOf("Игровой центр", "Game Center"),
            additionalToggles = listOf(
                "Mi Видео", "Mi Video",
                "Другие приложения", "Other apps"
            ),
            manualHintRu = "Поиск → ⋮ → Настройки → Страница поиска → " +
                    "выключите «Игровой центр», «Mi Видео».",
            manualHintEn = "Search → ⋮ → Settings → Search page → " +
                    "turn off Game Center, Mi Video.",
            launchPackage = "com.miui.home",
            swipeUpAfterLaunch = true,
            drillPath = listOf(GEAR, listOf("Страница поиска", "Search page")),
            requiredPackages = listOf(
                "com.miui.home",
                "com.mi.android.globallauncher",
                "com.android.launcher3",
                "com.miui.launcher",
                "com.mi.global.home"
            ),
            riskLevel = RiskLevel.CONDITIONAL,
            forceStopBeforeLaunch = true
        ),

        // П.8 в инструкции: Лента виджетов → ⋮ → Управление службами → «Предложения»
        // ИСПРАВЛЕНО (beta6): добавлены глобал и AOSP имена
        Step(
            id = "appvault_services",
            titleRu = "Лента виджетов: предложения",
            titleEn = "App Vault suggestions",
            descRu = "Выключаем «Предложения» в ленте виджетов.",
            descEn = "Turning off App Vault suggestions.",
            intents = listOf(launchIntent("com.miui.personalassistant")),
            searchTexts = listOf(
                "Предложения", "Предложение", "Suggestions", "Suggestion",
                "Рекомендации", "Recommendations",
                "Рекомендации приложений", "App recommendations"
            ),
            additionalToggles = listOf(
                "Реклама", "Ads", "Promotions",
                "Рекомендации приложений", "App recommendations"
            ),
            manualHintRu = "Лента виджетов → ⋮ → Управление службами → " +
                    "выключите «Предложения».",
            manualHintEn = "App Vault → ⋮ → Service management → turn off Suggestions.",
            launchPackage = "com.miui.personalassistant",
            drillPath = listOf(
                OVERFLOW,
                listOf("Управление службами", "Service management")
            ),
            requiredPackages = listOf(
                "com.miui.personalassistant",                    // Китай (основное)
                "com.mi.android.global.personalassistant",       // Глобал
                "com.android.personalassistant"                  // AOSP
            ),
            riskLevel = RiskLevel.CONDITIONAL,
            forceStopBeforeLaunch = true
        ),

        // П.8b: … → О ленте виджетов → «Персонализированные услуги»
        // ИСПРАВЛЕНО (beta6): добавлены глобал и AOSP имена
        Step(
            id = "appvault_about",
            titleRu = "Лента виджетов: услуги",
            titleEn = "App Vault services",
            descRu = "Выключаем «Персонализированные услуги».",
            descEn = "Turning off personalized services.",
            intents = listOf(launchIntent("com.miui.personalassistant")),
            searchTexts = listOf(
                "Персонализированные услуги",
                "Personalized services"
            ),
            manualHintRu = "Лента виджетов → ⋮ → О ленте виджетов → " +
                    "выключите «Персонализированные услуги».",
            manualHintEn = "App Vault → ⋮ → About → turn off Personalized services.",
            launchPackage = "com.miui.personalassistant",
            drillPath = listOf(
                OVERFLOW,
                listOf("О ленте виджетов", "About App Vault", "About widget feed")
            ),
            requiredPackages = listOf(
                "com.miui.personalassistant",
                "com.mi.android.global.personalassistant",
                "com.android.personalassistant"
            ),
            riskLevel = RiskLevel.CONDITIONAL,
            forceStopBeforeLaunch = true
        ),

        // ── БЛОК Г: УВЕДОМЛЕНИЯ (п.11) ────────────────────────────────

        // П.11 в инструкции: Отключение уведомлений системного сервиса рекламы MSA
        Step(
            id = "notif_msa",
            titleRu = "Уведомления сервиса MSA",
            titleEn = "MSA notifications",
            descRu = "Полностью выключаем уведомления системного сервиса рекламы MSA.",
            descEn = "Turning off notifications for MSA system ad service.",
            intents = listOf(notificationsIntent("com.miui.msa.global")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications",
                "允许通知", "Permitir notificaciones"
            ),
            manualHintRu = "Настройки → Приложения → msa → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → msa → Notifications → turn off.",
            requiredPackages = listOf(
                "com.miui.msa.global",
                "com.miui.msa.core"
            ),
            targetChecked = false
        ),

        // ИСПРАВЛЕНО (beta6): добавлены альтернатива и GameLoop
        Step(
            id = "notif_gamecenter",
            titleRu = "Уведомления Игрового центра",
            titleEn = "Game Center notifications",
            descRu = "Полностью выключаем уведомления Игрового центра.",
            descEn = "Turning off Game Center notifications.",
            intents = listOf(notificationsIntent("com.xiaomi.gamecenter")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Игровой центр → " +
                    "Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Game Center → Notifications → turn off.",
            requiredPackages = listOf(
                "com.xiaomi.gamecenter",        // Китай (основное)
                "com.miui.gamecenter",          // Альтернатива
                "com.xiaomi.glgm"               // GameLoop (глобал)
            ),
            targetChecked = false
        ),

        // ИСПРАВЛЕНО (beta6): добавлен глобал
        Step(
            id = "notif_appvault",
            titleRu = "Уведомления Ленты виджетов",
            titleEn = "App Vault notifications",
            descRu = "Выключаем уведомления Ленты виджетов.",
            descEn = "Turning off App Vault notifications.",
            intents = listOf(notificationsIntent("com.miui.personalassistant")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Лента виджетов → " +
                    "Уведомления → выключите.",
            manualHintEn = "Settings → Apps → App Vault → Notifications → turn off.",
            requiredPackages = listOf(
                "com.miui.personalassistant",
                "com.mi.android.global.personalassistant"
            )
        ),

        // ИСПРАВЛЕНО (beta6): добавлена альтернатива
        Step(
            id = "notif_themes",
            titleRu = "Уведомления Тем",
            titleEn = "Themes notifications",
            descRu = "Выключаем уведомления Тем.",
            descEn = "Turning off Themes notifications.",
            intents = listOf(notificationsIntent("com.android.thememanager")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Темы → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Themes → Notifications → turn off.",
            requiredPackages = listOf(
                "com.android.thememanager",
                "com.miui.thememanager"
            )
        ),

        // ИСПРАВЛЕНО (beta6): добавлено старое имя
        Step(
            id = "notif_getapps",
            titleRu = "Уведомления GetApps",
            titleEn = "GetApps notifications",
            descRu = "Выключаем уведомления GetApps.",
            descEn = "Turning off GetApps notifications.",
            intents = listOf(notificationsIntent("com.xiaomi.market")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → GetApps → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → GetApps → Notifications → turn off.",
            requiredPackages = listOf(
                "com.xiaomi.market",
                "com.miui.market",
                "com.mi.global.market"
            )
        ),

        // ИСПРАВЛЕНО (beta6): добавлен Китай
        Step(
            id = "notif_browser",
            titleRu = "Уведомления Mi Браузера",
            titleEn = "Mi Browser notifications",
            descRu = "Выключаем уведомления Mi Браузера.",
            descEn = "Turning off Mi Browser notifications.",
            intents = listOf(notificationsIntent("com.android.browser")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Mi Браузер → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Mi Browser → Notifications → turn off.",
            requiredPackages = listOf(
                "com.android.browser",
                "com.mi.globalbrowser",
                "com.miui.browser"
            )
        ),

        // ИСПРАВЛЕНО (beta6): добавлена альтернатива
        Step(
            id = "notif_mivideo",
            titleRu = "Уведомления Mi Видео",
            titleEn = "Mi Video notifications",
            descRu = "Выключаем уведомления Mi Видео.",
            descEn = "Turning off Mi Video notifications.",
            intents = listOf(notificationsIntent("com.miui.videoplayer")),
            searchTexts = listOf(
                "Разрешить уведомления", "Allow notifications",
                "Показывать уведомления", "Show notifications"
            ),
            manualHintRu = "Настройки → Приложения → Mi Видео → Уведомления → выключите.",
            manualHintEn = "Settings → Apps → Mi Video → Notifications → turn off.",
            requiredPackages = listOf(
                "com.miui.videoplayer",
                "com.miui.video",
                "com.mi.global.video"
            )
        )
    )
}