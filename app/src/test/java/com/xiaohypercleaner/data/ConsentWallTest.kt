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

    private val bridge = object : ConsentWallHandler.TapBridge {
        var tapResult = true
        override suspend fun tapByTexts(texts: List<String>): Boolean {
            if (!tapResult) return false
            tappedTexts.addAll(texts)
            return true
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
        val node = screen("Welcome to Themes Terms of Service")
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
}