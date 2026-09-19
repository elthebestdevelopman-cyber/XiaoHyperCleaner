package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Поиск папок рабочего стола для шага `folder_recommendations`.
 *
 * Имя папки задаёт пользователь, поэтому папка ищется структурно: по классу
 * (com.miui.home.launcher.Folder) или по превью с несколькими иконками. Тест
 * фиксирует оба признака — иначе рутина тапала бы по первой иконке рабочего стола.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerFolderTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner

    @Before
    fun setUp() {
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    private fun node(
        className: String,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn(className)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (desc != null) Mockito.`when`(n.contentDescription).thenReturn(desc)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `launcher folder class with a label is a candidate`() {
        val folder = node("com.miui.home.launcher.Folder", text = "Игры", clickable = true)

        assertTrue(runner.isFolderCandidate(folder))
    }

    @Test
    fun `plain icon without folder class is not a candidate`() {
        val icon = node("android.widget.TextView", text = "Камера")

        assertFalse(runner.isFolderCandidate(icon))
    }

    @Test
    fun `folder described as a folder in content description is a candidate`() {
        val folder = node("android.widget.FrameLayout", desc = "Папка Россия", clickable = true)

        assertTrue(runner.isFolderCandidate(folder))
    }

    @Test
    fun `grid cell with several labelled icons is a folder preview`() {
        val icon1 = node("android.widget.ImageView", desc = "Chrome")
        val icon2 = node("android.widget.ImageView", desc = "YouTube")
        val cell = node(
            "android.widget.FrameLayout",
            text = "Инструменты",
            clickable = true,
            children = arrayOf(icon1, icon2)
        )

        assertTrue(runner.isFolderGridCandidate(cell))
    }

    @Test
    fun `single icon cell is not a folder preview`() {
        val icon = node("android.widget.ImageView", desc = "Chrome")
        val cell = node(
            "android.widget.FrameLayout",
            text = "Chrome",
            clickable = true,
            children = arrayOf(icon)
        )

        assertFalse(runner.isFolderGridCandidate(cell))
    }

    @Test
    fun `non clickable cell is not a folder preview`() {
        val icon1 = node("android.widget.ImageView", desc = "Chrome")
        val icon2 = node("android.widget.ImageView", desc = "YouTube")
        val cell = node(
            "android.widget.FrameLayout",
            text = "Игры",
            children = arrayOf(icon1, icon2)
        )

        assertFalse(runner.isFolderGridCandidate(cell))
    }

    @Test
    fun `home icon with launcher icon structure is a probe candidate`() {
        // Дамп POCO Launcher (прогон rmu8qhjhi): иконка приложения и папка выглядят
        // одинаково — кликабельный FrameLayout с icon_container/icon_title.
        val title = node("android.widget.TextView", text = "Russia")
        val titleHolder = node("android.widget.FrameLayout", children = arrayOf(title))
        val container = node("android.widget.FrameLayout", children = arrayOf(title))
        Mockito.`when`(container.viewIdResourceName).thenReturn("com.miui.home:id/icon_container")
        val wrapper = node("android.widget.FrameLayout", children = arrayOf(container))
        val icon = node("android.widget.FrameLayout", desc = "Russia", clickable = true, children = arrayOf(wrapper))

        assertTrue(runner.isHomeIconNode(icon))
    }

    @Test
    fun `voice search button is not a probe candidate`() {
        val mic = node("android.widget.ImageView", desc = "Голосовой поиск", clickable = true)

        assertFalse("кнопка поиска не является иконкой рабочего стола", runner.isHomeIconNode(mic))
    }
}

