package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Обработчик системных диалогов: welcome-стены и runtime-permission запросы.
 *
 * Ключевое правило: permission-диалоги отклоняются по умолчанию (deny),
 * allow — только через `allowOverrides`/медиа-шаги; welcome-стена тапается
 * кнопкой согласия, и цикл ограничен maxIterationsPerStep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ConsentWallTest {

    private lateinit var service: AccessibilityService
    private val tappedTexts = mutableListOf<String>()

    /** Resource-id, по которым тапнул мост (закрытие обманки-промпта). */
    private val tappedIds = mutableListOf<String>()

    /** Маркеры, переданные мосту как avoid-список (по ним тапать запрещено). */
    private var avoidTexts: List<String> = emptyList()

    /** Resource-id, которые есть на «экране» теста: остальные боевой мост не найдёт. */
    private val knownIds = mutableSetOf<String>()

    private val bridge = object : ConsentWallHandler.TapBridge {
        var tapResult = true

        /** Порядок событий моста: `uncheck:<id>` должен идти до `tap:<подпись>`. */
        val events = mutableListOf<String>()

        override suspend fun uncheckCheckboxes(entries: List<Pair<String, String>>): List<String> {
            entries.forEach { events.add("uncheck:" + it.first) }
            return entries.map { it.second }
        }

        /** Сколько раз мост сделал свайп вверх (закрытие гайда-жеста). */
        var swipes = 0

        override suspend fun swipeUp(): Boolean {
            swipes++
            events.add("swipe:up")
            return true
        }

        override suspend fun tapByTexts(texts: List<String>): Boolean {
            if (!tapResult) return false
            events.add("tap:" + (texts.firstOrNull() ?: ""))
            tappedTexts.addAll(texts)
            return true
        }

        override suspend fun tapDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean {
            this@ConsentWallTest.avoidTexts = avoidTexts
            return tapByTexts(texts)
        }

        override suspend fun tapByIds(ids: List<String>): Boolean {
            // Пустой список id — «идти текстовым путём», как в боевом мосте.
            if (ids.isEmpty() || !tapResult) return false
            // Боевой мост тапает только по узлу, который РЕАЛЬНО есть в дереве:
            // неизвестный id возвращает false, и хендлер идёт текстовым путём.
            val present = ids.filter { it in knownIds }
            if (present.isEmpty()) return false
            tappedIds.addAll(present)
            return true
        }
    }

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        tappedTexts.clear()
        tappedIds.clear()
        knownIds.clear()
        bridge.swipes = 0
        bridge.events.clear()
        service = Mockito.mock(AccessibilityService::class.java)
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    /** Mock-узел с текстом экрана. */
    private fun screen(text: String): AccessibilityNodeInfo {
        val node = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(node.text).thenReturn(text)
        Mockito.`when`(node.childCount).thenReturn(0)
        return node
    }

    /**
     * Стена с ЖИВОЙ кнопкой согласия: welcome принимается только при наличии
     * enabled-кнопки из welcomeActions (иначе рабочие экраны Проводника, где слова
     * «Еще»/«Настройки» совпадают с действиями стены, ломали маршрут — прогон rmumuqr53).
     */
    private fun wallScreen(
        text: String,
        button: String,
        extraChild: AccessibilityNodeInfo? = null
    ): AccessibilityNodeInfo {
        val root = screen(text)
        val btn = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(btn.text).thenReturn(button)
        Mockito.`when`(btn.isEnabled).thenReturn(true)
        Mockito.`when`(btn.isClickable).thenReturn(true)
        Mockito.`when`(btn.childCount).thenReturn(0)
        val children = listOfNotNull(extraChild, btn)
        Mockito.`when`(root.childCount).thenReturn(children.size)
        children.forEachIndexed { index, child ->
            Mockito.`when`(root.getChild(index)).thenReturn(child)
        }
        Mockito.`when`(btn.parent).thenReturn(root)
        return root
    }

    @Test
    fun `wall with a label-only button is accepted by tapping the label`() = runTest {
        // WebView-стены (Загрузки, магазин) отдают подпись согласия БЕЗ кликабельного
        // узла: прежний гейт «живой кнопки» уводил стену в skipped_no_button, и
        // приложение не пускало дальше (прогон rmuvlyyor: downloads).
        withLocale("ru") {
            val labelOnly = Mockito.mock(AccessibilityNodeInfo::class.java)
            Mockito.`when`(labelOnly.text).thenReturn("Принять и продолжить")
            Mockito.`when`(labelOnly.isEnabled).thenReturn(true)
            Mockito.`when`(labelOnly.isClickable).thenReturn(false)
            Mockito.`when`(labelOnly.childCount).thenReturn(0)
            val node = screen("Условия использования Принять и продолжить")
            Mockito.`when`(node.childCount).thenReturn(1)
            Mockito.`when`(node.getChild(0)).thenReturn(labelOnly)
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
            var labelTaps = 0
            val labelBridge = object : ConsentWallHandler.TapBridge {
                override suspend fun tapByTexts(texts: List<String>): Boolean = false

                override suspend fun tapLabelByTexts(
                    texts: List<String>,
                    avoidTexts: List<String>
                ): Boolean {
                    labelTaps++
                    tappedTexts.addAll(texts)
                    return true
                }
            }

            val outcome = ConsentWallHandler.handleOnce(service, labelBridge, "downloads")

            assertTrue("стена принята тапом по подписи", outcome.handled)
            assertEquals("welcome", outcome.kind)
            assertEquals("тап идёт по подписи согласия", 1, labelTaps)
        }
    }

    @Test
    fun `no dialog leads to no action`() = runTest {
        val node = screen("Настройки Отпечатки")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "carousel")

        assertFalse(outcome.handled)
        assertTrue(tappedTexts.isEmpty())
    }

    @Test
    fun `case inflected privacy wording is recognised as a welcome wall`() = runTest {
        // MiDrop/ShareMe: «…ознакомьтесь и согласитесь с нашими Условиями
        // использования и Политикой конфиденциальности» — маркеры в именительном
        // падеже эту стену не ловили (прогон rmua2sd7x, shareme).
        val node = wallScreen(
            "Переносить любые типы файлов Предже чем продолжить, ознакомьтесь и согласитесь " +
                "с нашими Условиями использования и Политикой конфиденциальности. " +
                "Согласиться Отклонить",
            "Согласиться"
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = withLocale("ru") {
            ConsentWallHandler.handleOnce(service, bridge, "shareme")
        }

        assertTrue("стена должна распознаваться", outcome.handled)
        assertEquals("welcome", outcome.kind)
        assertTrue(
            "тап идёт по кнопке согласия",
            tappedTexts.any { it.equals("Согласиться", ignoreCase = true) }
        )
    }

    @Test
    fun `appvault toggle dialog is owned by the step and not dismissed`() = runTest {
        // Дамп av_toggle_dialog.xml (01.10, MIUI 13): после тапа по «Персонализированные
        // услуги» встаёт диалог alertTitle «Персонализированные услуги», message
        // «…Отключить службы?», button2 «Отключить» (положительная, слева) и button1
        // «Нет, спасибо» (отказ). Шаг объявляет confirmTexts «Отключить», поэтому диалог
        // принадлежит шагу — хендлер стен обязан отойти (ownsDialog), иначе он гасит
        // диалог отказом и тумблер остаётся включённым (прогон rmuod5cmm:
        // consent: kind=dialog decision=dismissed cause=alert step=appvault_about).
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Персонализированные услуги Предлагаемые материалы могут быть " +
                    "менее интересны вам, если вы отключите службы персонализации. " +
                    "Отключить службы? Отключить Нет, спасибо",
                ownerPackage = "com.mi.android.globalminusscreen",
                stepPackages = listOf("com.mi.android.globalminusscreen"),
                stepId = "appvault_about",
                stepConfirmTexts = listOf("Отключить"),
                stepConsentTexts = emptyList(),
                alertDialog = true,
                confirmButtonTexts = listOf("Отключить", "Нет, спасибо")
            )
            assertNull("диалог шага не обрабатывает хендлер стен: kind=${action?.kind}", action)
        }
    }

    @Test
    fun `confirm text only in body text is not owned by the step`() = runTest {
        // C4/R2-6: ужесточение ownsDialog — та же фраза в ОПИСАНИИ экрана больше не делает
        // диалог «своим» шага: опознание идёт по подписи КНОПОЧНОГО узла.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Персонализированные услуги Отключить службы? Отмена",
                ownerPackage = "com.mi.android.globalminusscreen",
                stepPackages = listOf("com.mi.android.globalminusscreen"),
                stepId = "appvault_about",
                stepConfirmTexts = listOf("Отключить"),
                stepConsentTexts = emptyList(),
                alertDialog = true,
                confirmButtonTexts = listOf("Отмена")
            )
            assertTrue("без своей кнопки диалог не считается своим: ${action?.kind}", action != null)
        }
    }

    @Test
    fun `msa revoke dialog is not dismissed by a foreign step handler`() = runTest {
        // Прогон rmupuud3s: на шаге sys_recommendations висел диалог отзыва msa
        // («Отзыв разрешения … Отозвать (5 с)»), и alert-политика чужого шага погасила
        // его отказом — отзыв msa отменился. Диалог принадлежит msa (его confirmTexts
        // «Отозвать»), поэтому хендлер обязан отойти и не трогать его.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Отзыв разрешения После отзыва разрешения приложение прекратит " +
                    "сбор данных и удалит все свои данные с серверов. Это может сделать " +
                    "приложение практически непригодным для использования. Отозвать " +
                    "разрешение? Отмена Отозвать (5 с)",
                ownerPackage = "com.android.settings",
                stepPackages = listOf("com.miui.securitycenter"),
                stepId = "sys_recommendations",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = true,
                confirmButtonTexts = listOf("Отмена", "Отозвать (5 с)")
            )
            assertNull("чужой confirm-диалог не гасится: kind=${action?.kind}", action)
        }
    }

    @Test
    fun `security first-run terms wall is accepted though it echoes the msa revoke text`() = runTest {
        // Первый запуск «Безопасности»: стена «Условия использования / Добро пожаловать
        // в Безопасность» с кнопкой «Согласиться» (дамп diagnostic_snapshot_security_sys_*).
        // Её текст содержит «Отзыв разрешения», совпадая с confirmTexts шага msa, — прежний
        // foreign-guard уводил стену в skip, и security_sys падал drill_failed (прогон
        // rmuu1hsq6). Владелец стены — пакет шага, поэтому это app-owned welcome, а не чужой
        // confirm: такая стена принимается.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Условия использования Добро пожаловать в \"Безопасность\"! " +
                    "Это приложение проверяет приложения и выполняет Отзыв разрешения. " +
                    "Отмена Согласиться",
                ownerPackage = "com.miui.securitycenter",
                stepPackages = listOf("com.miui.securitycenter"),
                stepId = "security_sys",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = SemanticCatalog.consentTexts("security_sys"),
                alertDialog = true
            )

            assertEquals("стена Безопасности принимается, а не пропускается", "welcome", action?.kind)
            assertEquals("app_owned", action?.cause)
            assertEquals("accepted", action?.decision)
        }
    }

    @Test
    fun `foreign confirm dialog is never closed by another step`() = runTest {
        // Диалог «Отключить службы?» принадлежит appvault_about; на чужом шаге он тоже
        // не должен закрываться отказом — иначе служба остаётся включённой.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Персонализированные услуги Предлагаемые материалы могут быть " +
                    "менее интересны вам, если вы отключите службы персонализации. " +
                    "Отключить службы? Отключить Нет, спасибо",
                ownerPackage = "com.mi.android.globalminusscreen",
                stepPackages = listOf("com.android.settings"),
                stepId = "sys_recommendations",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = true,
                confirmButtonTexts = listOf("Отключить", "Нет, спасибо")
            )
            assertNull("чужой диалог не закрывается", action)
        }
    }

    @Test
    fun `welcome wall is accepted with policy action`() = runTest {
        val node = wallScreen("Welcome to Themes Terms of Service", "Принять")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(
            service, bridge, "themes", stepConsentTexts = listOf("Принять")
        )

        assertTrue(outcome.handled)
        assertEquals("welcome", outcome.kind)
        assertTrue("тап согласия должен идти первым", tappedTexts.first() == "Принять")
    }

    @Test
    fun `downloads wall is accepted by its own button and skip is not tapped`() = runTest {
        // Стена Загрузок — системный PrivacyGrantDialog с кнопками «Отмена»/«Согласен»;
        // её текст упоминает «Загрузки», поэтому шаг объявляет welcomeDecision=accept,
        // а «Пропуск»/«Пропустить» согласием на этом шаге не считаются.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Условия использования Добро пожаловать в Загрузки! " +
                    "Приложению Загрузки требуется осуществлять сбор информации. Отмена Согласен",
                ownerPackage = "com.android.settings",
                stepPackages = listOf("com.android.providers.downloads.ui"),
                stepId = "downloads",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = true
            )

            assertEquals("welcome", action?.kind)
            assertEquals("accepted", action?.decision)
            assertEquals("Согласен", action?.texts?.firstOrNull())
            assertFalse("пропуск не согласие", action!!.texts.contains("Пропуск"))
            assertFalse("пропуск не согласие", action.texts.contains("Пропустить"))
        }
    }

    @Test
    fun `personalization checkbox is unchecked before the wall is accepted`() = runTest {
        // Стены Браузера и Тём: чекбокс персонализации отмечен по умолчанию — снимаем
        // его ДО тапа согласия (иначе согласие включало бы сбор данных).
        withLocale("ru") {
            val node = wallScreen(
                "Условия использования Добро пожаловать в Mi Браузер Персонализация услуг",
                "Принять и продолжить"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

            val outcome = ConsentWallHandler.handleOnce(
                service,
                bridge,
                "browser_sys",
                stepPackages = listOf("com.mi.globalbrowser")
            )

            assertTrue(outcome.handled)
            assertEquals("welcome", outcome.kind)
            assertEquals(listOf("uncheck:cb_service"), bridge.events.filter { it.startsWith("uncheck:") })
            assertTrue(
                "снятие отметки обязано идти до тапа согласия: ${bridge.events}",
                bridge.events.indexOfFirst { it.startsWith("uncheck:") } <
                    bridge.events.indexOfFirst { it.startsWith("tap:")
                }
            )
        }
    }

    @Test
    fun `music shortcuts dialog is closed by its button id`() = runTest {
        // Mi Music: «Ярлыки функций доступны сейчас» поверх настроек, единственная
        // кнопка `tv_ok` (+OK). Крестика нет, подпись локализуется — закрываем по id
        // (прогон rmuoaz4jm: диалог висел, шаг уходил not_applicable).
        withLocale("ru") {
            val node = wallScreen(
                "Вернуться Аккаунт и настройки Показывать рекламу Отзыв согласия " +
                    "Ярлыки функций доступны сейчас",
                "OK"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
            // Кнопка есть в дереве: боевой мост найдёт её по id.
            knownIds.add("tv_ok")

            val outcome = ConsentWallHandler.handleOnce(
                service,
                bridge,
                "music_sys",
                stepPackages = listOf("com.miui.player")
            )

            assertTrue(outcome.handled)
            assertTrue(
                "диалог закрывается по resource-id: ${tappedIds.joinToString()}",
                tappedIds.contains("tv_ok")
            )
        }
    }

    @Test
    fun `route step accepts its own first-run wall but not working screen words`() = runTest {
        // Проводник: стена первого запуска (`confirm_btn` «Принять и продолжить»)
        // должна приниматься, несмотря на `welcomeAllowed=false` — иначе route 2/4
        // упирается в неё (прогон rmuoaz4jm). Рабочий экран с «Еще»/«Настройки»
        // при этом стеной не считается: узкий набор шага их не содержит.
        withLocale("ru") {
            val wall = wallScreen(
                "Добро пожаловать в Проводник! Помимо основных функций, это приложение " +
                    "также предоставляет следующие службы: Категоризация недавно использованных объектов",
                "Принять и продолжить"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(wall)
            val outcome = ConsentWallHandler.handleOnce(
                service, bridge, "filemanager",
                stepPackages = listOf("com.mi.android.globalFileexplorer"),
                allowWelcome = false
            )
            assertTrue("стена Проводника должна приниматься", outcome.handled)
            assertEquals("welcome", outcome.kind)
            assertEquals("Принять и продолжить", tappedTexts.firstOrNull())
        }

        tappedTexts.clear()
        bridge.events.clear()

        withLocale("ru") {
            val listScreen = wallScreen(
                "Недавние Память Документы Настройки Очистить",
                "Еще"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(listScreen)
            val outcome = ConsentWallHandler.handleOnce(
                service, bridge, "filemanager",
                stepPackages = listOf("com.mi.android.globalFileexplorer"),
                allowWelcome = false
            )
            assertFalse("рабочий экран не стена", outcome.handled)
        }
    }

    @Test
    fun `swipe guide is dismissed by swipe up`() = runTest {
        // Mi Video: «Проведите вверх для просмотра других видео» перекрывает вкладки,
        // кнопки у гайда нет — закрываем свайпом (прогон rmuoaz4jm, дамп
        // diag-dumps/stumble/vid_feed.xml).
        withLocale("ru") {
            val node = screen("Проведите вверх для просмотра других видео")
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

            val outcome = ConsentWallHandler.handleOnce(
                service,
                bridge,
                "mivideo",
                stepPackages = listOf("com.miui.videoplayer")
            )

            assertTrue(outcome.handled)
            assertEquals("guide", outcome.kind)
            assertEquals("свайп вверх должен быть сделан", 1, bridge.swipes)
        }
    }

    @Test
    fun `permission dialog is denied by default`() = runTest {
        val node = screen("Allow app to access files permission request")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "filemanager")

        assertTrue(outcome.handled)
        assertEquals("permission", outcome.kind)
        assertEquals("deny", outcome.decision)
        assertTrue(
            "по умолчанию тапаем deny-тексты",
            tappedTexts.all { it in SemanticCatalog.denyTexts() }
        )
    }

    @Test
    fun `media step permission is allowed`() = runTest {
        val node = screen("Allow app to access music permission request")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "music_sys")

        assertEquals("allow", outcome.decision)
        assertTrue(
            "аудио-разрешение медиа-шага исторически разрешено",
            tappedTexts.all { it in SemanticCatalog.allowTexts() }
        )
    }

    @Test
    fun `iteration limit stops the welcome loop`() = runTest {
        // Экран стены меняется после каждого тапа — иначе сработал бы анти-повтор.
        val node = Mockito.mock(AccessibilityNodeInfo::class.java)
        var counter = 0
        Mockito.`when`(node.text).thenAnswer { "Welcome to Themes Terms of Service ${counter++}" }
        // Живая кнопка согласия: без неё стена теперь не принимается (guard F7).
        val acceptBtn = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(acceptBtn.text).thenReturn("Принять")
        Mockito.`when`(acceptBtn.isEnabled).thenReturn(true)
        Mockito.`when`(acceptBtn.isClickable).thenReturn(true)
        Mockito.`when`(acceptBtn.childCount).thenReturn(0)
        Mockito.`when`(node.childCount).thenReturn(1)
        Mockito.`when`(node.getChild(0)).thenReturn(acceptBtn)
        Mockito.`when`(acceptBtn.parent).thenReturn(node)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val handled = ConsentWallHandler.handleUntilSettled(
            service = service,
            bridge = bridge,
            stepId = "themes",
            maxIterations = 3
        )

        assertEquals("цикл ограничен maxIterationsPerStep", 3, handled)
    }

    @Test
    fun `stubborn wall is not tapped twice on unchanged screen`() = runTest {
        // Реальный баг прогона rmu8lzcu9: одна и та же стена «принималась» трижды
        // (browser_sys/music_sys), потому что цикл не проверял прогресс.
        val node = wallScreen("Welcome to Themes Terms of Service", "Принять")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val handled = ConsentWallHandler.handleUntilSettled(
            service = service,
            bridge = bridge,
            stepId = "themes",
            maxIterations = 3
        )

        assertEquals("неизменившийся экран — прогресса нет", 1, handled)
    }

    /** Узел с Android-id диалога (alertTitle/message/button1/button2). */
    private fun dialogNode(
        id: String,
        text: String? = null,
        clickable: Boolean = false
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.viewIdResourceName).thenReturn(id)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.childCount).thenReturn(0)
        return n
    }

    private fun container(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `default browser prompt is dismissed by negative button`() = runTest {
        val root = container(
            dialogNode("android:id/alertTitle", "Mi Браузер"),
            dialogNode("android:id/message", "Установите Mi Браузер в качестве браузера по умолчанию"),
            dialogNode("android:id/button2", "Отмена", clickable = true),
            dialogNode("android:id/button1", "OK", clickable = true)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "browser_sys")

        assertTrue("промпт «по умолчанию» должен закрываться", outcome.handled)
        assertEquals("dialog", outcome.kind)
        assertEquals("dismissed", outcome.decision)
        assertEquals("первой тапается отрицательная кнопка", "Отмена", tappedTexts.first())
    }

    @Test
    fun `crash report dialog is dismissed instead of blocking the step`() = runTest {
        val root = container(
            dialogNode("android:id/alertTitle", "В приложении \"GetApps\" снова произошел сбой"),
            dialogNode("android:id/message", "Отправить отчет об ошибке в Xiaomi?"),
            dialogNode("android:id/button2", "Отмена", clickable = true),
            dialogNode("android:id/button1", "Отправить отчет", clickable = true)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "notif_getapps")

        assertTrue(outcome.handled)
        assertEquals("dialog", outcome.kind)
        assertTrue("отчёт об ошибке не отправляем", "Отправить отчет" !in tappedTexts)
    }

    @Test
    fun `dialog owned by the step is left to the step confirm logic`() = runTest {
        // msa: диалог «Отозвать» ведёт confirmDelayedRevoke — generic-закрытие
        // «Отменой» сломало бы отзыв.
        val root = container(
            dialogNode("android:id/alertTitle", "Отозвать доступ к личным данным?"),
            dialogNode("android:id/button1", "Отозвать", clickable = true),
            dialogNode("android:id/button2", "Отмена", clickable = true)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val outcome = ConsentWallHandler.handleOnce(
            service, bridge, "msa", stepConfirmTexts = listOf("Отозвать", "ОК")
        )

        assertFalse("диалог шага не закрывается generic-путём", outcome.handled)
        assertTrue("ничего не тапали", tappedTexts.isEmpty())
    }

    @Test
    fun `welcome markers inside the target screen are not a consent wall`() = runTest {
        // carousel: ссылка «Условия использования» на СВОЁМ экране настроек
        // считалась стеной и тапала согласие (прогон rmu8lzcu9).
        val node = screen("Wallpaper Carousel Terms of Service")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "carousel")

        assertFalse("целевой экран шага — не стена согласия", outcome.handled)
        assertTrue(tappedTexts.isEmpty())
    }

    @Test
    fun `welcome wall with checkboxes marks them before the enabled button`() = runTest {
        val checkbox = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(checkbox.text).thenReturn(SemanticCatalog.checkboxTexts().first())
        Mockito.`when`(checkbox.isClickable).thenReturn(true)
        Mockito.`when`(checkbox.childCount).thenReturn(0)
        val node = wallScreen("Terms of Service Select all (required)", "Принять", checkbox)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
        var enabledTaps = 0
        val bridge = object : ConsentWallHandler.TapBridge {
            override suspend fun tapByTexts(texts: List<String>): Boolean {
                tappedTexts.addAll(texts)
                return true
            }

            override suspend fun tapEnabledByTexts(texts: List<String>): Boolean {
                enabledTaps++
                tappedTexts.addAll(texts)
                return true
            }
        }

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "themes")

        assertTrue(outcome.handled)
        assertEquals("кнопка согласия нажимается по enabled-пути", 1, enabledTaps)
        assertTrue(
            "чекбоксы отмечаются до кнопки",
            tappedTexts.indexOf(SemanticCatalog.checkboxTexts().first()) <
                tappedTexts.indexOf(SemanticCatalog.welcomeActions().first())
        )
    }

    @Test
    fun `network error dialog is dismissed inside the step loop`() = runTest {
        val node = screen("Network error occurred")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "browser_sys")

        assertTrue("диалог-заглушка закрыт", outcome.handled)
        assertEquals("dismiss", outcome.kind)
        assertTrue(
            "тапнули кнопку закрытия диалога",
            tappedTexts.any { it in SemanticCatalog.dismissTexts() }
        )
    }

    @Test
    fun `no thanks dialog after carousel toggle is dismissed`() = runTest {
        val node = screen("No, thanks")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(service, bridge, "carousel")

        assertTrue(outcome.handled)
        assertEquals("dismiss", outcome.kind)
    }

    @Test
    fun `failed tap is reported as not found`() = runTest {
        val node = screen("Allow app to access files permission request")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
        val failingBridge = object : ConsentWallHandler.TapBridge {
            override suspend fun tapByTexts(texts: List<String>): Boolean = false
        }

        val outcome = ConsentWallHandler.handleOnce(service, failingBridge, "filemanager")

        assertFalse("ничего не нажали — не сообщаем об обработке", outcome.handled)
        assertEquals("deny", outcome.decision)
    }

    @Test
    fun `mi apps promo master is skipped by tapping the label without a clickable node`() = runTest {
        // Прогон rmuvijbl1: мастер Mi Apps («Mi фаны рекомендуют», дамп
        // diag-dumps/fresh/getapps_recommend.xml) отдаёт кнопку «Пропустить» БЕЗ
        // кликабельного узла (WebView) — прежний гейт «живой кнопки» её не видел, и
        // шаг getapps уходил ENTRY timeout / not_applicable.
        withLocale("ru") {
            val node = screen(
                "Mi Apps Основные приложения Mi фаны рекомендуют 18 приложений " +
                    "СКАЧАТЬ(3077.1MB) Пропустить"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
            var labelTaps = 0
            val labelBridge = object : ConsentWallHandler.TapBridge {
                override suspend fun tapByTexts(texts: List<String>): Boolean = false

                override suspend fun tapLabelByTexts(
                    texts: List<String>,
                    avoidTexts: List<String>
                ): Boolean {
                    labelTaps++
                    tappedTexts.addAll(texts)
                    return true
                }
            }

            val outcome = ConsentWallHandler.handleOnce(service, labelBridge, "getapps")

            assertTrue("мастер распознан и закрыт", outcome.handled)
            assertEquals("master", outcome.kind)
            assertEquals("skipped", outcome.decision)
            assertEquals("тап идёт по подписи пропуска", 1, labelTaps)
            assertTrue("подписи содержат «Пропустить»", tappedTexts.any { it == "Пропустить" })
        }
    }

    @Test
    fun `update decoy without a close button is dismissed by back`() = runTest {
        // GetApps UpgradeDialogActivity (дамп diag-dumps/fresh/getapps_after_skip.xml):
        // в дереве только «Обновить» (нажимать нельзя), крестика нет — закрываем BACK.
        val node = screen(
            "Доступно обновление Версия 6021644 64.3M Улучшена стабильность и " +
                "производительность. Также были исправлены ошибки. Обновить"
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
        var backs = 0
        val backBridge = object : ConsentWallHandler.TapBridge {
            override suspend fun tapByTexts(texts: List<String>): Boolean = false

            override suspend fun tapByIds(ids: List<String>): Boolean = false

            override suspend fun tapDialogButtonByTexts(
                texts: List<String>,
                avoidTexts: List<String>
            ): Boolean = false

            override suspend fun pressBack(): Boolean {
                backs++
                return true
            }
        }

        val outcome = withLocale("ru") {
            ConsentWallHandler.handleOnce(service, backBridge, "getapps")
        }

        assertTrue("обманка распознана и закрыта", outcome.handled)
        assertEquals("decoy", outcome.kind)
        assertTrue("обманка без кнопки закрытия закрывается BACK: $backs", backs >= 1)
    }

    /** Прогон кейса в конкретной локали (локаль каталога берётся из Locale.getDefault). */
    private suspend fun <T> withLocale(lang: String, block: suspend () -> T): T {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale(lang))
            return block()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `force stop dialog is closed by a button and its own title is avoided`() = runTest {
        // Прогон rmu8lzcu9: «Закрыть принудительно?» считалось закрытым тапом по
        // собственному заголовку («Закрыть» ⊂ «Закрыть принудительно?») — шаг крутился
        // девять итераций и падал.
        withLocale("ru") {
            val node = screen(
                "Закрыть принудительно? При принудительном закрытии приложения " +
                    "его работа может нарушиться Отмена"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

            val outcome = ConsentWallHandler.handleOnce(service, bridge, "filemanager")

            assertTrue(outcome.handled)
            assertEquals("dialog", outcome.kind)
            assertEquals("dismissed", outcome.decision)
            assertTrue(
                "заголовок диалога передан как avoid-маркер",
                avoidTexts.any { it.contains("Закрыть принудительно") }
            )
            assertTrue(
                "по заголовку диалога не тапаем",
                tappedTexts.none { it.contains("Закрыть принудительно") }
            )
        }
    }

    @Test
    fun `force stop classification keeps the negative path`() = runTest {
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Закрыть принудительно? Принудительное закрытие приложения Отмена",
                ownerPackage = "android",
                stepPackages = listOf("com.android.fileexplorer"),
                stepId = "filemanager",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("force_stop", action?.cause)
            assertEquals("dismissed", action?.decision)
            assertTrue(
                "тексты тапа — кнопки, а не заголовок",
                action!!.texts.none { it.contains("принудительно") }
            )
        }
    }

    @Test
    fun `default app prompt is dismissed even when the dialog belongs to the app`() = runTest {
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Установите Mi Браузер в качестве браузера по умолчанию Отмена",
                ownerPackage = "com.miui.globalbrowser",
                stepPackages = listOf("com.miui.globalbrowser"),
                stepId = "browser_sys",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = true
            )
            assertEquals("dialog", action?.kind)
            assertEquals("default_app", action?.cause)
            assertEquals("dismissed", action?.decision)
        }
    }

    @Test
    fun `app owned welcome wall is accepted instead of cancelled`() = runTest {
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Проводник Добро пожаловать в Проводник Условия использования Согласиться",
                ownerPackage = "com.android.fileexplorer",
                stepPackages = listOf("com.android.fileexplorer"),
                stepId = "filemanager",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("welcome", action?.kind)
            assertEquals("app_owned", action?.cause)
            assertEquals("accepted", action?.decision)
        }
    }

    @Test
    fun `permission dialog owned by the app is allowed`() = runTest {
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Разрешить приложению доступ к файлам Запрос разрешения Отклонить",
                ownerPackage = "com.android.fileexplorer",
                stepPackages = listOf("com.android.fileexplorer"),
                stepId = "filemanager",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("permission", action?.kind)
            assertEquals("app_owned", action?.cause)
            assertEquals("allow", action?.decision)
        }
    }

    @Test
    fun `unowned permission dialog is denied`() = runTest {
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Разрешить приложению доступ к файлам Запрос разрешения",
                ownerPackage = "com.android.permissioncontroller",
                stepPackages = listOf("com.android.fileexplorer"),
                stepId = "filemanager",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("permission", action?.kind)
            assertEquals("deny", action?.decision)
        }
    }

    @Test
    fun `welcome without an enabled accept button is not accepted`() = runTest {
        // Прогон rmumuqr53: consent: kind=welcome decision=accepted на РАБОЧИХ экранах
        // Проводника («Недавние Память Еще Поиск…», drawer) — слова «Еще»/«Настройки»
        // совпадают с welcomeActions. Guard: маркер стены без живой кнопки согласия —
        // не стена.
        withLocale("ru") {
            val listScreen = screen("Недавние Память Еще Поиск Документы Настройки Очистить")
            Mockito.`when`(service.rootInActiveWindow).thenReturn(listScreen)

            val outcome = ConsentWallHandler.handleOnce(service, bridge, "filemanager")

            assertFalse("без кнопки согласия welcome не принимается", outcome.handled)
            assertTrue("ничего не тапаем", tappedTexts.isEmpty())
        }
    }

    @Test
    fun `route step accepts only its own wall button`() = runTest {
        // У шагов с RouteScript (Проводник) общий набор welcomeActions выключен:
        // рабочая страница со словами «Еще»/«Продолжить» стеной не становится
        // (route 3/4 и 4/4 = ok=false в прогоне rmumuqr53). Принимается только
        // собственная кнопка стены первого запуска («Принять и продолжить»,
        // прогон rmuoaz4jm: без неё route 2/4 упирался в стену).
        withLocale("ru") {
            val foreignWall = wallScreen("Добро пожаловать в Проводник", "Продолжить")
            Mockito.`when`(service.rootInActiveWindow).thenReturn(foreignWall)

            val outcome = ConsentWallHandler.handleOnce(
                service,
                bridge,
                "filemanager",
                stepPackages = listOf("com.mi.android.globalFileexplorer"),
                allowWelcome = false
            )

            assertFalse("чужая кнопка стену не принимает", outcome.handled)
        }
    }

    @Test
    fun `open with chooser is dismissed and never resolved`() = runTest {
        // Прогон rmumuqr53: appvault_services и appvault_about упёрлись в системный выбор
        // «Открыть с помощью приложения: Mi Браузер … Отмена» и ушли в
        // not_applicable/timeout. Диалог закрывается, приложение-получатель не выбирается.
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Открыть с помощью приложения: Mi Браузер Ещё Запомнить выбор Отмена",
                ownerPackage = "android",
                stepPackages = listOf("com.mi.android.globalminusscreen"),
                stepId = "appvault_services",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("выбор получателя — диалог-заглушка", "dismiss", action?.kind)
            assertEquals("closed", action?.decision)
            assertFalse(
                "«Mi Браузер» из списка приложений не нажимается",
                action!!.texts.any { it.contains("Mi Браузер") }
            )
        }
    }

    @Test
    fun `update prompt is closed by the cross id and never by the update button`() = runTest {
        // Прогон rmulhb4yq (GetApps): апдейт-промпт «Доступно обновление … Обновить»
        // перекрывал экран, политика его не видела — drill не находил «Профиль», и шаг
        // объявлялся неприменимым (drill_level_absent) вместо закрытия промпта.
        withLocale("ru") {
            val node = screen(
                "Доступно обновление Версия 6021644 64.3M Улучшена стабильность и " +
                    "производительность. Также были исправлены ошибки. Обновить"
            )
            Mockito.`when`(service.rootInActiveWindow).thenReturn(node)
            // Крестик есть в дереве: боевой мост найдёт его по id.
            knownIds.add("upgrade_x_out")

            val outcome = ConsentWallHandler.handleOnce(service, bridge, "getapps")

            assertTrue("промпт-обманка должен обрабатываться", outcome.handled)
            assertEquals("decoy", outcome.kind)
            assertEquals("dismissed", outcome.decision)
            assertTrue(
                "крестик закрывается по id: $tappedIds",
                tappedIds.any { it.equals("upgrade_x_out", ignoreCase = true) }
            )
            assertTrue(
                "кнопка «Обновить» не нажимается никогда",
                tappedTexts.none { it.contains("Обновить") }
            )
        }
    }

    @Test
    fun `mi browser guide page is recognised as a wall and stepped over`() = runTest {
        // Дамп fresh_browser_guide2: вторая страница мастера «Статусы WhatsApp» не имела
        // ни одного маркера политики — стена удерживала экран, drill не находил
        // «Профиль» (прогон rmulhb4yq, browser_sys → not_applicable).
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Статусы WhatsApp Сохраняйте изображения и видео из статусов " +
                    "в WhatsApp Пропуск Далее",
                ownerPackage = "com.mi.globalbrowser",
                stepPackages = listOf("com.mi.globalbrowser"),
                stepId = "browser_sys",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("стена мастера закрывается «Пропуском» (dismiss-путь)", "dismiss", action?.kind)
            assertEquals("closed", action?.decision)
            assertTrue(
                "кнопка «Пропуск» мастера — в текстах отказа",
                action!!.texts.any { it.equals("Пропуск", ignoreCase = true) }
            )
        }
    }

    @Test
    fun `file manager first run wall is accepted by its own button`() = runTest {
        // Дамп before/filemanager: «Добро пожаловать в Проводник» перекрывал маршрут
        // «Еще → Настройки → Информация» (route 2–4 ok=false, прогон rmulhb4yq).
        withLocale("ru") {
            val action = ConsentWallHandler.classify(
                screenText = "Добро пожаловать в Проводник Добро пожаловать в Проводник! " +
                    "Помимо основных функций, это приложение также предоставляет следующие " +
                    "службы Категоризация недавно использованных объектов Просмотр и " +
                    "редактирование файлов Принять и продолжить Отклонить",
                ownerPackage = "com.mi.android.globalFileexplorer",
                stepPackages = listOf("com.mi.android.globalFileexplorer"),
                stepId = "filemanager",
                stepConfirmTexts = emptyList(),
                stepConsentTexts = emptyList(),
                alertDialog = false
            )
            assertEquals("welcome", action?.kind)
            assertEquals("app_owned", action?.cause)
            assertEquals("accepted", action?.decision)
            assertTrue(
                "согласие мастера тапается кнопкой «Принять и продолжить»",
                action!!.texts.any { it.equals("Принять и продолжить", ignoreCase = true) }
            )
        }
    }
}