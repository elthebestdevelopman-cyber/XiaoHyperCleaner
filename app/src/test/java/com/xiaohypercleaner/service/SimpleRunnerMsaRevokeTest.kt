package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.SemanticCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Отсчётный диалог msa: нажимается КНОПКА, а не сообщение диалога.
 *
 * Прогон rmua0pt7i: `findEnabledClickableByText` брал первый узел с текстом,
 * содержащим «Отозвать», — им оказывалось сообщение «…Отозвать разрешение?»,
 * тап уходил по его координатам, диалог оставался («Отозвать (6 с)») и шаг падал
 * `revoke_not_confirmed`. Плюс кнопка неактивна, пока идёт отсчёт.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerMsaRevokeTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun node(
        text: String? = null,
        id: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (id != null) Mockito.`when`(n.viewIdResourceName).thenReturn(id)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(enabled)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `dialog message is never chosen instead of the button`() {
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            className = "android.widget.TextView"
        )
        val revoke = node(
            text = "Отозвать",
            id = "android:id/button1",
            className = "android.widget.Button",
            clickable = true
        )
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(message, revoke))

        val found = runner.findDialogConfirmButton(root, listOf("Отозвать"))

        assertEquals("должна нажиматься кнопка, а не сообщение", revoke, found)
    }

    @Test
    fun `countdown label is recognised before tapping`() {
        assertTrue(runner.isCountdownLabel("Отозвать (9 с)"))
        assertTrue(runner.isCountdownLabel("Revoke (9s)"))
        assertFalse(runner.isCountdownLabel("Отозвать"))
        assertTrue(runner.isCountdownConfirmLabel("Отозвать (9 с)", listOf("Отозвать")))
        assertFalse(
            "сообщение диалога не считается кнопкой",
            runner.isCountdownConfirmLabel(
                "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
                listOf("Отозвать")
            )
        )
    }

    @Test
    fun `no button means no plain-text tap`() {
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            clickable = true
        )
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(message))

        assertNull(runner.findDialogConfirmButton(root, listOf("Отозвать")))
    }
}