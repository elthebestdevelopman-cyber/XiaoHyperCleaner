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
}