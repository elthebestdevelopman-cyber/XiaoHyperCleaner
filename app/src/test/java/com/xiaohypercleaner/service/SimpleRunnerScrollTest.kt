package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Прокрутка экрана в раннере.
 *
 * Регрессия прогона rmu8lzcu9: `findScrollableContainer` поднимался ВВЕРХ по
 * `parent` от окна и всегда возвращал null — drill не находил «Приложения» на
 * корне Настроек, а тумблеры ниже сгиба (карусель, реклама) не находились вовсе.
 * Контейнер ищется вниз по дереву.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerScrollTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner

    @Before
    fun setUp() {
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    private fun node(
        className: String,
        scrollable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isScrollable).thenReturn(scrollable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    @Test
    fun `nested scrollable container is found under the window root`() {
        val list = node("androidx.recyclerview.widget.RecyclerView", scrollable = true)
        val frame = node("android.widget.FrameLayout", children = arrayOf(list))
        val root = node("android.widget.FrameLayout", children = arrayOf(frame))

        assertEquals(
            "контейнер прокрутки должен находиться вниз по дереву",
            list,
            runner.findScrollableContainer(root)
        )
    }

    @Test
    fun `screen without scrollable container returns null`() {
        val text = node("android.widget.TextView")
        val root = node("android.widget.FrameLayout", children = arrayOf(text))

        assertNull(runner.findScrollableContainer(root))
    }

    /** Узел с текстом: по нему проверяется фактический сдвиг экрана. */
    private fun textNode(text: String): AccessibilityNodeInfo {
        val n = node("android.widget.TextView")
        Mockito.`when`(n.text).thenReturn(text)
        return n
    }

    @Test
    fun `row below the fold is reached by scrolling until it appears`() = runTest {
        // Прогон rmulhb4yq: строка «Получать рекомендации» (Cleaner) лежала ниже сгиба,
        // четырёх прокруток не хватало — шаг уходил в low_confidence/keyword_mismatch,
        // хотя на устройстве строка есть (bounds=[77,2118][625,2179] при экране 2400).
        // Ленивый список отдаёт строку только после двух прокруток.
        val scrolls = intArrayOf(0)
        val list = node("androidx.recyclerview.widget.RecyclerView", scrollable = true)
        Mockito.`when`(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            .thenAnswer {
                scrolls[0]++
                true
            }
        val row = textNode("Получать рекомендации")
        Mockito.`when`(row.isClickable).thenReturn(true)
        val screen0 = node(
            "android.widget.FrameLayout",
            children = arrayOf(list, textNode("НАПОМИНАНИЯ ОБ ОЧИСТКЕ КЭША WECHAT"))
        )
        val screen1 = node(
            "android.widget.FrameLayout",
            children = arrayOf(list, textNode("Расписание проверки"))
        )
        val screen2 = node("android.widget.FrameLayout", children = arrayOf(list, row))
        val screens = arrayOf(screen0, screen1, screen2)
        Mockito.`when`(service.rootInActiveWindow)
            .thenAnswer { screens[minOf(scrolls[0], screens.size - 1)] }

        val found = runner.findClickableByTextWithScroll(
            texts = listOf("Получать рекомендации"),
            logLabel = "Получать рекомендации"
        )

        assertNotNull("строка ниже сгиба должна находиться прокруткой", found)
        assertEquals("Получать рекомендации", found?.text?.toString())
        assertTrue("прокруток должно быть больше одной: ${scrolls[0]}", scrolls[0] >= 2)
    }

    @Test
    fun `scrolling stops when the screen does not move`() = runTest {
        // Инъекция может не дойти до приложения: если экран не сдвинулся, дальнейшие
        // прокрутки бесполезны — цикл обязан выйти, а не жечь бюджет шага.
        val list = node("androidx.recyclerview.widget.RecyclerView", scrollable = true)
        Mockito.`when`(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            .thenReturn(false)
        val root = node(
            "android.widget.FrameLayout",
            children = arrayOf(list, textNode("НАПОМИНАНИЯ ОБ ОЧИСТКЕ КЭША WECHAT"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)
        // swipeUp() берёт границы экрана: у mock-сервиса ресурсы не заданы.
        Mockito.`when`(service.resources).thenReturn(RuntimeEnvironment.getApplication().resources)

        val found = runner.findClickableByTextWithScroll(
            texts = listOf("Получать рекомендации"),
            attempts = 8,
            logLabel = "Получать рекомендации"
        )

        assertNull(found)
        // Вложенный поиск тумблера/`gesture`-ветка: цикл вышел после двух «пустых»
        // прокруток, а не отработал все восемь попыток.
        Mockito.verify(service, Mockito.atMost(14)).rootInActiveWindow
    }
}