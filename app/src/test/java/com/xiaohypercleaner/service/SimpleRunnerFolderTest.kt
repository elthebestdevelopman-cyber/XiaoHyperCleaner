package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `folder preview grid marks the cell as a folder candidate`() {
        // Дамп рабочего стола POCO Launcher: папка с рекомендациями — кликабельный
        // FrameLayout desc='Russia' БЕЗ слова «folder» в классе/описании. Единственный
        // признак — сетка превью item1..itemN в preview_icons_container.
        val item1 = node("android.widget.ImageView")
        Mockito.`when`(item1.viewIdResourceName).thenReturn("com.miui.home:id/item1")
        val item2 = node("android.widget.ImageView")
        Mockito.`when`(item2.viewIdResourceName).thenReturn("com.miui.home:id/item2")
        val preview = node("android.widget.LinearLayout", children = arrayOf(item1, item2))
        Mockito.`when`(preview.viewIdResourceName).thenReturn("com.miui.home:id/preview_icons_container")
        val container = node("android.widget.FrameLayout", children = arrayOf(preview))
        Mockito.`when`(container.viewIdResourceName).thenReturn("com.miui.home:id/icon_container")
        val title = node("android.widget.TextView", text = "Russia")
        val folder = node(
            "android.widget.FrameLayout",
            desc = "Russia",
            clickable = true,
            children = arrayOf(container, title)
        )

        assertTrue("сетка превью — признак папки", runner.isFolderGridCandidate(folder))
        assertTrue("папка без класса Folder должна быть кандидатом", runner.isFolderCandidate(folder))
    }

    @Test
    fun `plain app icon with icon structure is not a folder candidate`() {
        val cover = node("android.widget.ImageView")
        val iconImage = node("android.widget.ImageView", children = arrayOf(cover))
        val container = node("android.widget.FrameLayout", children = arrayOf(iconImage))
        Mockito.`when`(container.viewIdResourceName).thenReturn("com.miui.home:id/icon_container")
        val title = node("android.widget.TextView", text = "Камера")
        val icon = node(
            "android.widget.FrameLayout",
            desc = "Камера",
            clickable = true,
            children = arrayOf(container, title)
        )

        assertFalse("иконка приложения — не папка", runner.isFolderCandidate(icon))
        assertFalse(runner.isFolderGridCandidate(icon))
    }

    @Test
    fun `folder with preview grid is probed before plain icons`() {
        // Прогон rmua2sd7x: candidates=17, но первые 6 проб ушли на обычные иконки
        // (Проводник, Заметки, Календарь, ShareMe, Погода, Безопасность), а папка
        // с рекомендациями стояла 7-й и до неё дело не дошло. Папка обязана идти первой.
        val plain = (1..3).map { i ->
            val img = node("android.widget.ImageView", desc = "App$i")
            node("android.widget.FrameLayout", desc = "App$i", clickable = true, children = arrayOf(img))
        }
        val items = (1..4).map { i ->
            val item = node("android.widget.ImageView")
            Mockito.`when`(item.viewIdResourceName).thenReturn("com.miui.home:id/item$i")
            item
        }
        val preview = node("android.widget.LinearLayout", children = items.toTypedArray())
        Mockito.`when`(preview.viewIdResourceName).thenReturn("com.miui.home:id/preview_icons_container")
        val container = node("android.widget.FrameLayout", children = arrayOf(preview))
        Mockito.`when`(container.viewIdResourceName).thenReturn("com.miui.home:id/icon_container")
        val title = node("android.widget.TextView", text = "Russia")
        val folder = node(
            "android.widget.FrameLayout",
            desc = "Russia",
            clickable = true,
            children = arrayOf(container, title)
        )
        val root = node("android.widget.FrameLayout", children = (plain + folder).toTypedArray())
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val candidates = runner.homeFolderCandidates()

        assertEquals(
            "папка с превью-сеткой должна проверяться первой",
            "Russia",
            candidates.firstOrNull()?.contentDescription?.toString()
        )
    }

    @Test
    fun `voice search button is not a probe candidate`() {
        val mic = node("android.widget.ImageView", desc = "Голосовой поиск", clickable = true)

        assertFalse("кнопка поиска не является иконкой рабочего стола", runner.isHomeIconNode(mic))
    }

    @Test
    fun `app icons are not probe candidates for the folder step`() {
        // Аудит 2026-09-28: в список проб попадали иконки приложений (структура иконки
        // лаунчера), из-за чего робот «перебирал папки» вместо целевой и жёг бюджет.
        val iconImage = node("android.widget.ImageView")
        val container = node("android.widget.FrameLayout", children = arrayOf(iconImage))
        Mockito.`when`(container.viewIdResourceName).thenReturn("com.miui.home:id/icon_container")
        val title = node("android.widget.TextView", text = "Проводник")
        val icon = node(
            "android.widget.FrameLayout",
            desc = "Проводник",
            clickable = true,
            children = arrayOf(container, title)
        )
        val root = node("android.widget.FrameLayout", children = arrayOf(icon))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertTrue(
            "иконка приложения не должна быть кандидатом-папкой",
            runner.homeFolderCandidates().isEmpty()
        )
    }

    @Test
    fun `folder hint matching is locale-friendly and hint-only`() {
        // Подсказка ускоряет порядок проверки и ничего не подтверждает сама по себе:
        // истина — секция рекомендаций внутри папки (структурный поиск).
        assertTrue(runner.matchesFolderHint("Russia", listOf("Russia")))
        assertTrue(
            "мягкое сравнение по локали",
            runner.matchesFolderHint("Рекомендации", listOf("Рекомендации"))
        )
        assertFalse(
            "чужая папка не подсказка",
            runner.matchesFolderHint("Инструменты", listOf("Russia", "Рекомендации"))
        )
        assertFalse(
            "пустой список подсказок ничего не подтверждает",
            runner.matchesFolderHint("Russia", emptyList())
        )
    }

    @Test
    fun `popover title node wins over desktop icon label`() {
        // Прогон rmulg783z: тап «названия папки» уходил в иконку рабочего стола позади
        // поповера (у неё та же подпись), редактор не открывался, шаг шёл дальше.
        val iconTitle = node("android.widget.TextView", text = "Russia")
        Mockito.`when`(iconTitle.viewIdResourceName).thenReturn("com.miui.home:id/icon_title")
        val popoverTitle = node("android.widget.TextView", text = "Russia", clickable = true)
        Mockito.`when`(popoverTitle.viewIdResourceName).thenReturn("com.miui.home:id/title")
        Mockito.`when`(popoverTitle.packageName).thenReturn("com.miui.home")
        val root = node("android.widget.FrameLayout", children = arrayOf(iconTitle, popoverTitle))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val picked = runner.findFolderTitleNode()

        assertNotNull("заголовок поповера найден", picked)
        assertEquals("com.miui.home:id/title", picked?.viewIdResourceName)
    }

    @Test
    fun `desktop icon labels are excluded from title search`() {
        val iconTitleContainer = node("android.widget.FrameLayout")
        Mockito.`when`(iconTitleContainer.viewIdResourceName)
            .thenReturn("com.miui.home:id/icon_title_container")
        val iconLabel = node("android.widget.TextView", text = "Russia")
        Mockito.`when`(iconLabel.parent).thenReturn(iconTitleContainer)
        val shell = node("android.widget.FrameLayout")
        val popoverTitle = node("android.widget.TextView", text = "Russia")
        Mockito.`when`(popoverTitle.parent).thenReturn(shell)

        assertTrue("подпись иконки — это иконка рабочего стола", runner.belongsToDesktopIcon(iconLabel))
        assertFalse("заголовок поповера — не иконка", runner.belongsToDesktopIcon(popoverTitle))
    }

    @Test
    fun `foreign app title node is not a folder title`() {
        // Прогон rmulgzmdg: виджет «Поиск в Chrome» имеет id com.android.chrome:id/title.
        val chromeTitle = node("android.widget.TextView", text = "Поиск в Chrome", clickable = true)
        Mockito.`when`(chromeTitle.viewIdResourceName).thenReturn("com.android.chrome:id/title")
        Mockito.`when`(chromeTitle.packageName).thenReturn("com.android.chrome")
        val popoverTitle = node("android.widget.TextView", text = "Russia", clickable = true)
        Mockito.`when`(popoverTitle.viewIdResourceName).thenReturn("com.miui.home:id/title")
        Mockito.`when`(popoverTitle.packageName).thenReturn("com.miui.home")
        val root = node("android.widget.FrameLayout", children = arrayOf(chromeTitle, popoverTitle))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse("чужой заголовок не подходит", runner.isFolderTitleNode(chromeTitle))
        assertEquals("com.miui.home:id/title", runner.findFolderTitleNode()?.viewIdResourceName)
    }
}

