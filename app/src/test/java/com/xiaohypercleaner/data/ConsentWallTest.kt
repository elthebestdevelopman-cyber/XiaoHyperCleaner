package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Маркеры, переданные мосту как avoid-список (по ним тапать запрещено). */
    private var avoidTexts: List<String> = emptyList()

    private val bridge = object : ConsentWallHandler.TapBridge {
        var tapResult = true
        override suspend fun tapByTexts(texts: List<String>): Boolean {
            if (!tapResult) return false
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
    }

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        tappedTexts.clear()
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
        val node = screen(
            "Переносить любые типы файлов Предже чем продолжить, ознакомьтесь и согласитесь " +
                "с нашими Условиями использования и Политикой конфиденциальности. " +
                "Согласиться Отклонить"
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
    fun `welcome wall is accepted with policy action`() = runTest {
        val node = screen("Welcome to Themes Terms of Service")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(node)

        val outcome = ConsentWallHandler.handleOnce(
            service, bridge, "themes", stepConsentTexts = listOf("Принять")
        )

        assertTrue(outcome.handled)
        assertEquals("welcome", outcome.kind)
        assertTrue("тап согласия должен идти первым", tappedTexts.first() == "Принять")
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
        Mockito.`when`(node.childCount).thenReturn(0)
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
        val node = screen("Welcome to Themes Terms of Service")
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
        val node = screen("Terms of Service Select all (required)")
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
}