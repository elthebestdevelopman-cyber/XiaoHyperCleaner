package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Разрешение кнопки диалога подтверждения (downloads: «Отключить рекомендации?» → «OK»).
 *
 * Прогон rmulhb4yq: `confirmTexts.downloads.ru = ["Отключить"]` совпадал с ЗАГОЛОВКОМ
 * диалога, поиск возвращал кликабельный контейнер без собственной подписи, тап по его
 * центру ничего не нажимал — в логе `confirm: tapped 'null'`. Матчер обязан брать узел
 * со СВОЕЙ подписью, а узел без подписи — не возвращать вовсе (шаг не тапает вслепую).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerConfirmTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner

    /** Тексты шага downloads: подпись кнопки (7 локалей) + legacy-текст кнопки-действия. */
    private val confirmTexts = listOf("OK", "ОК", "Отключить")

    @Before
    fun setUp() {
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    private fun node(
        className: String,
        text: String? = null,
        clickable: Boolean = false,
        id: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.text).thenReturn(text)
        Mockito.`when`(n.contentDescription).thenReturn(null)
        Mockito.`when`(n.viewIdResourceName).thenReturn(id)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(true)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    @Test
    fun `own label of the button wins over the dialog title`() {
        val ok = node("android.widget.Button", text = "OK", clickable = true)
        val title = node("android.widget.TextView", text = "Отключить рекомендации?")
        val panel = node("android.widget.LinearLayout", clickable = true, children = arrayOf(title, ok))

        val found = runner.findDialogConfirmButton(panel, confirmTexts)

        assertEquals(
            "кнопка берётся по своей подписи, а не по заголовку диалога",
            "OK",
            found?.text?.toString()
        )
    }

    @Test
    fun `clickable container without its own label is not a confirmation button`() {
        // Контейнер диалога (parentPanel) кликабелен, но своей подписи не имеет: тап по
        // нему диалог не закрывает — матчер обязан вернуть null, и шаг напишет
        // `confirm: label unresolved` вместо ложного `tapped 'null'`.
        val title = node("android.widget.TextView", text = "Отключить рекомендации?")
        val panel = node("android.widget.LinearLayout", clickable = true, children = arrayOf(title))

        val found = runner.findDialogConfirmButton(panel, confirmTexts)

        assertNull("узел без собственной подписи не должен считаться кнопкой", found)
    }
}