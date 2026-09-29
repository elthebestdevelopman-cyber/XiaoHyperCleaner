package com.xiaohypercleaner.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Семантическая таблица: пакеты-цели шагов (P0: ложные `app_not_installed`).
 *
 * Регресс-кейс: GetApps на Global — `com.xiaomi.mipicks`, App Vault Global —
 * `com.mi.android.globalminusscreen`; без них план ложно исключал 5 шагов.
 * messages_sys остаётся без Google Messages: шаг настраивает Xiaomi Messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SemanticCatalogTest {

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    @Test
    fun `catalog loads all steps`() {
        assertEquals(29, SemanticCatalog.all().size)
    }

    @Test
    fun `getapps targets mipicks first`() {
        val packages = SemanticCatalog.requiredPackages("getapps")
        assertEquals("com.xiaomi.mipicks", packages.first())
        assertTrue("legacy market-имена остаются фолбэками", packages.contains("com.mi.global.market"))
        assertEquals("com.xiaomi.mipicks", SemanticCatalog.launchPackage("getapps"))
    }

    @Test
    fun `notif_getapps targets mipicks`() {
        val packages = SemanticCatalog.requiredPackages("notif_getapps")
        assertEquals("com.xiaomi.mipicks", packages.first())
        assertEquals("com.xiaomi.mipicks", SemanticCatalog.launchPackage("notif_getapps"))
    }

    @Test
    fun `appvault steps target globalminusscreen`() {
        listOf("appvault_services", "appvault_about", "notif_appvault").forEach { id ->
            val packages = SemanticCatalog.requiredPackages(id)
            assertEquals("$id должен начинаться с фактического пакета", "com.mi.android.globalminusscreen", packages.first())
            assertEquals(
                "$id: launchPackage из каталога",
                "com.mi.android.globalminusscreen",
                SemanticCatalog.launchPackage(id)
            )
        }
    }

    @Test
    fun `messages step does not target google messages`() {
        assertTrue(
            "шаг остаётся skipped на устройстве без Xiaomi Messages",
            SemanticCatalog.requiredPackages("messages_sys").isEmpty()
        )
    }

    @Test
    fun `steps without packages stay empty`() {
        assertTrue(SemanticCatalog.requiredPackages("msa").isEmpty())
        assertFalse(SemanticCatalog.keywords("msa").isEmpty())
    }

    @Test
    fun `google diagnostics declares its own labels for every locale`() {
        // Шаг закрывает Google-канал программы улучшения (MIUI-пункт «Программа улучшения
        // качества» на POCO/MIUI 13 отсутствует — дамп ux_owner_screen). Регресс: без
        // подписей и маркеров во всех локалях шаг молча уходил в skipped(low_confidence).
        val original = Locale.getDefault()
        try {
            SemanticCatalog.LOCALES.forEach { lang ->
                Locale.setDefault(Locale(lang))
                assertTrue("$lang: подпись тумблера", SemanticCatalog.itemTexts("google_diagnostics").isNotEmpty())
                assertTrue("$lang: маркеры экрана", SemanticCatalog.screenMarkers("google_diagnostics").isNotEmpty())
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `carousel on miui12_14 turns the carousel off through the lock screen route`() {
        // Владелец + разведка агента на POCO X3 Pro (MIUI 13), прогон rmumuqr53:
        // вход компонентой SettingActivity (verdict OK), на экране ПЯТЬ тумблеров:
        // «Карусель экрана блокировки» (главный, диалог «Добавить в выбранные фото?» →
        // «Отмена»), «Проведите вправо по Экрану блокировки» (диалог подтверждается),
        // «Обновлять через мобильный Интернет» и подменю «Политика конфиденциальности»:
        // «Реклама на Экране блокировки» + «Включить персонализированные услуги».
        // Дампы: after/car_main.xml, after/car_dlg2.xml, after/car_priv.xml,
        // p_car_dlg_cancel.xml.
        val variant = SemanticCatalog.step("carousel")?.variants
            .orEmpty()
            .first { it.id == "miui12_14" }

        assertEquals("Блокировка экрана", variant.drillPath.first().first())
        assertEquals("Карусель обоев", variant.drillPath.last().first())
        assertEquals(
            "первым идёт проверенный интент экрана настроек",
            "com.miui.android.fashiongallery/com.miui.cw.feature.ui.setting.SettingActivity",
            variant.route.first().intent
        )
        assertEquals(listOf("Карусель экрана блокировки"), variant.itemTexts["ru"])
        assertEquals(listOf("Отмена"), variant.toggleDeclineTexts["ru"])
        assertTrue(
            "маркеры экрана — строки устройства: ${variant.screenMarkers["ru"]}",
            variant.screenMarkers["ru"].orEmpty().containsAll(
                listOf(
                    "Карусель обоев",
                    "Карусель экрана блокировки",
                    "Дополнительные настройки",
                    "Предпочтения",
                    "Политика конфиденциальности"
                )
            )
        )

        assertTrue("до главного тумблера — все зависимые строки", variant.extraTargets.all { it.beforeMain })
        val targets = variant.extraTargets.filter { it.beforeMain }
        assertEquals("три цели (пять тумблеров вместе с главным)", 3, targets.size)
        assertEquals(
            listOf(
                "Реклама на Экране блокировки",
                "Обновлять через мобильный Интернет",
                "Проведите вправо по Экрану блокировки"
            ),
            targets.map { it.itemTexts["ru"].orEmpty().first() }
        )
        assertEquals(
            "в подменю приватности гасятся обе строки",
            listOf("Реклама на Экране блокировки", "Включить персонализированные услуги"),
            targets.first().itemTexts["ru"].orEmpty()
        )
        assertEquals(
            listOf(listOf("Политика конфиденциальности", "Privacy policy")),
            targets.first().drillPath
        )
        assertEquals(
            listOf("Подтвердить"),
            targets.last().confirmTexts["ru"].orEmpty()
        )
        assertTrue("все цели выключаются", targets.all { !it.targetChecked })
    }

    @Test
    fun `carousel on hyperos keeps the personal wallpapers mode`() {
        // HyperOS-ветка на устройстве не проверялась: поведение оставлено прежним
        // (карусель со своими обоями), менять его без дампа нельзя.
        val variant = SemanticCatalog.step("carousel")?.variants
            .orEmpty()
            .first { it.id == "hyperos1_3" }

        assertTrue(
            "hyperos1_3: тумблер режима",
            variant.itemTexts["ru"].orEmpty().any { it.contains("Пользовательские обои") }
        )
    }

    @Test
    fun `consent policy declares dialog markers and media overrides`() {
        assertTrue(
            "маркеры force-stop есть в каталоге",
            SemanticCatalog.forceStopMarkers().any { it.contains("Force stop") }
        )
        assertTrue(SemanticCatalog.crashReportMarkers().isNotEmpty())
        assertTrue(SemanticCatalog.defaultAppMarkers().isNotEmpty())
        assertTrue(SemanticCatalog.alertMarkerTexts().isNotEmpty())
        assertEquals("accept", SemanticCatalog.appOwnedDecision())
        assertTrue(
            "аудио-разрешение media-шагов исторически разрешено",
            SemanticCatalog.shouldAllow("music_sys")
        )
        assertFalse(
            "filemanager остаётся deny по умолчанию",
            SemanticCatalog.shouldAllow("filemanager")
        )
    }

    @Test
    fun `folder step declares switch labels for every locale`() {
        val original = Locale.getDefault()
        try {
            SemanticCatalog.LOCALES.forEach { lang ->
                Locale.setDefault(Locale(lang))
                assertTrue("$lang: подпись тумблера", SemanticCatalog.itemTexts("folder_recommendations").isNotEmpty())
                assertTrue("$lang: маркеры редактора папки", SemanticCatalog.screenMarkers("folder_recommendations").isNotEmpty())
                assertTrue("$lang: пункт «Изменить папку»", SemanticCatalog.overflowMenuLabels("folder_recommendations").isNotEmpty())
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `installer step declares the settings route for every locale`() {
        assertEquals(
            "пакеты установщика: на POCO/MIUI 13 global экран настроек живёт в " +
                "com.miui.global.packageinstaller (прогон rmuiiy2an: без него шаг уходил " +
                "в installer_settings_not_found)",
            listOf(
                "com.miui.global.packageinstaller",
                "com.miui.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.packageinstaller"
            ),
            SemanticCatalog.requiredPackages("installer_recommendations")
        )
        val original = Locale.getDefault()
        try {
            SemanticCatalog.LOCALES.forEach { lang ->
                Locale.setDefault(Locale(lang))
                assertTrue("$lang: подпись тумблера", SemanticCatalog.itemTexts("installer_recommendations").isNotEmpty())
                assertTrue("$lang: маркеры экрана настроек", SemanticCatalog.screenMarkers("installer_recommendations").isNotEmpty())
            }
        } finally {
            Locale.setDefault(original)
        }
    }
@Test
    fun `shareme screen with ad personalization passes the gate`() {
        // Дамп прогона rmuecq65x: экран «Справка и обратная связь» с тумблером
        // «Персонализация рекламы» — прежние keywords/маркеры экран не пропускали
        // (low_confidence), хотя переключатель на нём был.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            val decision = SemanticGate.decide(
                keywords = SemanticCatalog.keywords("shareme"),
                screenText = "Назад Справка и обратная связь Персонализация рекламы",
                screenMarkers = SemanticCatalog.screenMarkers("shareme"),
                switchFound = true,
                hasTapFallback = false
            )
            assertTrue("гейт должен пропустить экран ShareMe: ${decision.detail}", decision.act)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `music sys ads screen passes the gate under advanced settings`() {
        // Дамп прогона rmuecq65x: тумблеры Музыки живут под «Расширенными настройками»,
        // а маркеры промежуточного экрана «Аккаунт и настройки» прерывали drill до
        // раскрытия секции (low_confidence при видимом экране настроек).
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            val decision = SemanticGate.decide(
                keywords = SemanticCatalog.keywords("music_sys"),
                screenText = "Расширенные настройки Показывать рекламу Персональные рекомендации",
                screenMarkers = SemanticCatalog.screenMarkers("music_sys"),
                switchFound = true,
                hasTapFallback = false
            )
            assertTrue("гейт должен пропустить экран рекламы Музыки: ${decision.detail}", decision.act)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `consent policy keeps locale parity for welcome and decoy machine fields`() {
        val policy = SemanticCatalog.policy()
        assertNotNull("политика согласий должна загружаться", policy)
        val locales = SemanticCatalog.LOCALES.sorted()
        assertEquals("welcomeMarkers: 7 локалей", locales, policy!!.welcomeMarkers.keys.sorted())
        assertEquals("welcomeActions: 7 локалей", locales, policy.welcomeActions.keys.sorted())
        assertEquals("decoyMarkers: 7 локалей", locales, policy.decoyMarkers.keys.sorted())
        assertTrue(
            "крестик апдейт-промпта GetApps закрывается по id",
            policy.decoyCloseIds.contains("upgrade_x_out")
        )
        assertFalse(
            "среди маркеров обманки нет кнопки «Обновить» — обновление не нажимаем",
            policy.decoyMarkers.values.flatten().any { it.trim() == "Обновить" }
        )
    }

    @Test
    fun `browser route enters settings by component and skips the profile tab`() {
        // Прогон rmumuqr53 (второй, 23:49): drill «Профиль» ударил по action_my (тап
        // ушёл в оверлей без passthrough), затем по action_tabs («Закрытие всех вкладок»)
        // → drill_failed. Вердикты p_browser_comp_settings/p_browser_act_open = OK:
        // BrowserSettingsActivity открывает «Основные настройки» напрямую, вкладка
        // «Профиль» не нужна. Строки уровней — owner_recon/browser_settings2.xml
        // («Прочее» → «Дополнительные настройки»), browser_advanced.xml, browser_security.xml.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            SemanticCatalog.selectVariant(
                RomProfile(
                    region = RomRegion.GLOBAL,
                    miuiVersion = "V13.0.5.0",
                    hyperOsHint = false,
                    isTablet = false
                )
            )
            val route = SemanticCatalog.route("browser_sys")
            assertEquals(
                "первым идёт проверенная компонента экрана настроек",
                "com.mi.globalbrowser/com.android.browser.BrowserSettingsActivity",
                route.first().intent
            )
            val routeWhat = route.map { it.intent ?: it.tapText ?: it.tapDesc ?: it.tapId ?: "scroll" }
            val routeScroll = route.any { it.scroll }
            val routeTap = route.any { it.tapText == "Дополнительные настройки" }
            assertTrue(
                "дальше — скролл и «Дополнительные настройки»: $routeWhat",
                routeScroll && routeTap
            )
            assertFalse(
                "маршрут через вкладку «Профиль» убран целиком",
                route.any { (it.tapText ?: it.tapDesc ?: "").contains("Профиль") }
            )
            assertFalse(
                "и из drill-подсказки тоже",
                SemanticCatalog.variantDrillPath("browser_sys")
                    .any { level -> level.any { it.equals("Профиль", ignoreCase = true) } }
            )
            val extra = SemanticCatalog.extraTargetsAfterMain("browser_sys").first()
            assertEquals(
                "второй экран — «Безопасность» из меню настроек",
                listOf(listOf("Безопасность", "Security", "安全")),
                extra.drillPath
            )
            assertEquals(1, extra.back)
            assertEquals(listOf("Персонализация услуг"), extra.itemTexts)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `carousel step covers all five toggles and both dialogs`() {
        // Прогон rmumuqr53 (второй, 23:49): карусель ушла в skip из-за внешнего промпта,
        // а зависимые строки гасли ПОСЛЕ главного тумблера — активность уже закрывалась,
        // и drill «Политика конфиденциальности» шёл по чужим экранам. Контракт: все
        // зависимые строки — ДО главного тумблера, главный последний; диалоги: «Отмена»
        // на «Добавить в выбранные фото?», «Подтвердить» на «Выключить карусель…?».
        // Строки — из дампов after/car_main.xml, car_dlg2.xml, car_priv.xml.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            SemanticCatalog.selectVariant(
                RomProfile(
                    region = RomRegion.GLOBAL,
                    miuiVersion = "V13.0.5.0",
                    hyperOsHint = false,
                    isTablet = false
                )
            )
            assertEquals(
                "главный тумблер — именно «Карусель экрана блокировки»",
                listOf("Карусель экрана блокировки"),
                SemanticCatalog.itemTexts("carousel")
            )
            assertTrue(
                "целей после главного тумблера нет: активность закрывается сразу",
                SemanticCatalog.extraTargetsAfterMain("carousel").isEmpty()
            )
            val targets = SemanticCatalog.extraTargetsBeforeMain("carousel")
            assertEquals("три цели до главного тумблера", 3, targets.size)
            assertEquals(
                "порядок: подменю приватности -> мобильные данные -> свайп",
                listOf(
                    "Реклама на Экране блокировки",
                    "Обновлять через мобильный Интернет",
                    "Проведите вправо по Экрану блокировки"
                ),
                targets.map { it.itemTexts.first() }
            )
            assertEquals(
                "в подменю приватности гасятся ОБЕ строки",
                listOf("Реклама на Экране блокировки", "Включить персонализированные услуги"),
                targets.first().itemTexts
            )
            assertEquals(
                listOf(listOf("Политика конфиденциальности", "Privacy policy")),
                targets.first().drillPath
            )
            assertEquals(
                "у «Проведите вправо…» диалог подтверждается, а не отменяется",
                listOf("Подтвердить"),
                targets.last().confirmTexts
            )
            assertTrue(
                "у остальных целей подтверждения нет (иначе «Отмена» отменит саму цель)",
                targets.dropLast(1).all { it.confirmTexts.isEmpty() }
            )
            assertEquals(
                "главный тумблер закрывается отказом «Отмена»",
                listOf("Отмена"),
                SemanticCatalog.toggleDeclineTexts("carousel")
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `carousel promo dialog is dismissed by the decline button`() {
        // Промпт «Наслаждайтесь еще лучшим экраном блокировки» (кнопки «Отклонить»/
        // «Согласиться») — из дампа after/diagnostic_snapshot_carousel_*: он и занял экран
        // в прогоне rmumuqr53. Классифицируется как обманка (rule decoy), а «Отклонить»
        // живёт в негативных кнопках alert-путей — не в dismissTexts, иначе dismiss
        // перехватывает welcome-стены Проводника и permission-диалоги (регресс тестов
        // ConsentWallTest).
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            assertTrue(
                "маркер промпта карусели — среди обманок: ${SemanticCatalog.decoyMarkers()}",
                SemanticCatalog.decoyMarkers().any {
                    it.contains("Наслаждайтесь еще лучшим экраном блокировки")
                }
            )
            assertFalse(
                "«Отклонить» не должно быть в dismissTexts",
                SemanticCatalog.dismissTexts().any { it.equals("Отклонить", ignoreCase = true) }
            )
            assertTrue(
                "«Отклонить» доступно как негативная кнопка диалога",
                ConsentWallHandler.alertNegativeTextsForTest()
                    .any { it.equals("Отклонить", ignoreCase = true) }
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `downloads confirm text is the dialog button, not the dialog title`() {
        // Прогон rmulhb4yq: `confirmTexts.downloads.ru = ["Отключить"]` совпал с ЗАГОЛОВКОМ
        // диалога «Отключить рекомендации?» — тап уходил в контейнер диалога без подписи
        // (`confirm: tapped 'null'` ×2, диалог закрывался побочно). Первым текстом обязана
        // идти подпись КНОПКИ диалога.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            val texts = SemanticCatalog.confirmTexts("downloads")
            assertEquals("подпись кнопки диалога идёт первой", "OK", texts.first())
            assertTrue("подпись кнопки MIUI тоже принимается", texts.contains("ОК"))
        } finally {
            Locale.setDefault(original)
        }
    }
}