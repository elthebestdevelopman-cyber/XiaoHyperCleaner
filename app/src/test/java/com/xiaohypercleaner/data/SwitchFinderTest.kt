package com.xiaohypercleaner.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Поиск переключателей и чтение фактического состояния (checked_before).
 *
 * Область повышенного риска: от трёх типов узлов (Switch/CheckBox/RadioButton)
 * и корректности checked_before зависит честность отката.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SwitchFinderTest {

    @Suppress("DEPRECATION")
    private fun node(
        className: String,
        checked: Boolean = false,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        checkable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.isCheckable).thenReturn(checkable)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (desc != null) Mockito.`when`(n.contentDescription).thenReturn(desc)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    /** Проставляет parent-ссылки: реальное дерево Accessibility их всегда имеет. */
    private fun link(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        children.forEach { child -> Mockito.`when`(child.parent).thenReturn(parent) }
    }

    @Test
    fun `switch types are detected including radio and checkable views`() {
        assertTrue(SwitchFinder.isSwitchLike(node("android.widget.Switch", checkable = true)))
        assertTrue(SwitchFinder.isSwitchLike(node("android.widget.CheckBox", checkable = true)))
        assertTrue(SwitchFinder.isSwitchLike(node("android.widget.RadioButton", checkable = true)))
        assertTrue(SwitchFinder.isSwitchLike(node("android.widget.ToggleButton", checkable = true)))
        // Нестандартный класс MIUI, но checkable — тоже тумблер.
        assertTrue(SwitchFinder.isSwitchLike(node("miui.widget.CustomToggle", checkable = true)))
        assertFalse(SwitchFinder.isSwitchLike(node("android.widget.TextView")))
    }

    @Test
    fun `checked state is read for checkbox and switch`() {
        assertTrue(SwitchFinder.isChecked(node("android.widget.CheckBox", checked = true, checkable = true)))
        assertFalse(SwitchFinder.isChecked(node("android.widget.Switch", checked = false, checkable = true)))
    }

    @Test
    fun `switch is found by its own text`() {
        val switch = node("android.widget.Switch", checked = false, text = "Получать рекомендации", checkable = true)
        val root = node("android.widget.LinearLayout", clickable = false, children = arrayOf(switch))

        val found = SwitchFinder.findSwitch(root, listOf("Получать рекомендации"))

        assertEquals(switch, found)
    }

    @Test
    fun `switch is found next to its label in sibling container`() {
        val label = node("android.widget.TextView", text = "Рекламные службы")
        val switch = node("android.widget.Switch", checkable = true)
        val widgetFrame = node("android.widget.LinearLayout", clickable = false, children = arrayOf(switch))
        val row = node("android.widget.LinearLayout", clickable = true, children = arrayOf(label, widgetFrame))
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(row))
        link(row, label, widgetFrame)
        link(root, row)
        link(widgetFrame, switch)

        val found = SwitchFinder.findSwitch(root, listOf("Рекламные службы"))

        assertNotNull("тумблер рядом с подписью должен быть найден", found)
        assertTrue(SwitchFinder.isSwitchLike(found!!))
    }

    @Test
    fun `no switch is reported for unrelated screen`() {
        val root = node(
            "android.widget.LinearLayout",
            clickable = false,
            children = arrayOf(node("android.widget.TextView", text = "О телефоне"))
        )

        assertNull(SwitchFinder.findSwitch(root, listOf("Получать рекомендации")))
    }

    @Test
    fun `ambiguous row with two switches is rejected`() {
        // Ложный OK как класс: в контейнере два тумблера — это не строка настройки,
        // переключать «первый попавшийся» запрещено.
        val label = node("android.widget.TextView", text = "Получать рекомендации")
        val first = node("android.widget.Switch", checkable = true, checked = true)
        val second = node("android.widget.Switch", checkable = true, checked = false)
        val row = node("android.widget.LinearLayout", clickable = true, children = arrayOf(label, first, second))
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(row))
        link(row, label, first, second)
        link(root, row)

        assertNull(
            "контейнер с двумя переключателями не является строкой настройки",
            SwitchFinder.findSwitch(root, listOf("Получать рекомендации"))
        )
    }

    @Test
    fun `switch from another row is rejected`() {
        // Реальный кейс ложного OK: подпись матчится в одной строке, а ближайший
        // контейнер с тумблером — соседняя строка (чужой тумблер).
        val label = node("android.widget.TextView", text = "Рекламные службы")
        val labelRow = node("android.widget.LinearLayout", clickable = true, children = arrayOf(label))
        val stranger = node("android.widget.Switch", checkable = true, checked = true, text = "Показывать пароли")
        val strangerRow = node("android.widget.LinearLayout", clickable = true, children = arrayOf(stranger))
        val screen = node("android.widget.FrameLayout", clickable = false, children = arrayOf(labelRow, strangerRow))
        link(screen, labelRow, strangerRow)
        link(labelRow, label)
        link(strangerRow, stranger)

        assertNull(
            "тумблер чужой строки не должен быть выбран",
            SwitchFinder.findSwitch(screen, listOf("Рекламные службы"))
        )
    }

    @Test
    fun `miui row with outer switch and inner checkbox picks the row switch`() {
        // Реальная структура строки MIUI (дамп carousel, прогон rmu8lzcu9): внешний
        // Switch строки + внутренний `Switch id=checkbox` в widget_frame. Прежнее
        // правило «ровно один тумблер» отклоняло такую строку как ambiguous.
        val label = node("android.widget.TextView", text = "Персонализированная реклама")
        val outer = node("android.widget.Switch", checkable = true, checked = true)
        val inner = node("android.widget.Switch", checkable = true, checked = true)
        val row = node("android.widget.LinearLayout", clickable = true, children = arrayOf(label, outer, inner))
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(row))
        link(row, label, outer, inner)
        link(root, row)
        withBounds(label, 60, 1000, 700, 1060)
        withBounds(outer, 900, 990, 1050, 1070)
        withBounds(inner, 930, 1005, 1020, 1055)

        val found = SwitchFinder.findSwitch(root, listOf("Персонализированная реклама"))

        assertNotNull("тумблер строки должен быть найден по геометрии", found)
        assertTrue(SwitchFinder.isSwitchLike(found!!))
        assertEquals("выбирается тумблер строки (крупнее внутреннего checkbox)", outer, found)
    }

    @Test
    fun `switch of a foreign row is rejected by geometry`() {
        // С подписями и bounds: тумблер соседней строки не пересекается по вертикали
        // с подписью и не должен приниматься (защита от ложного OK).
        val label = node("android.widget.TextView", text = "Персонализированная реклама")
        val labelRow = node("android.widget.LinearLayout", clickable = true, children = arrayOf(label))
        val stranger = node("android.widget.Switch", checkable = true, checked = true)
        val strangerRow = node("android.widget.LinearLayout", clickable = true, children = arrayOf(stranger))
        val screen = node("android.widget.FrameLayout", clickable = false, children = arrayOf(labelRow, strangerRow))
        link(screen, labelRow, strangerRow)
        link(labelRow, label)
        link(strangerRow, stranger)
        withBounds(label, 60, 1000, 700, 1060)
        withBounds(stranger, 900, 1200, 1050, 1260)

        assertNull(
            "тумблер чужой строки не должен быть выбран",
            SwitchFinder.findSwitch(screen, listOf("Персонализированная реклама"))
        )
    }

    @Test
    fun `miui notification row with widget_frame checkbox is found`() {
        // Реальная структура (дамп notif_msa, прогон rmu8lzcu9):
        // LinearLayout[clickable](row) → RelativeLayout(TextView title) + widget_frame(CheckBox).
        // CheckBox не clickable и без текста — раньше такой тумблер не находился вовсе.
        val title = node("android.widget.TextView", text = "Показывать уведомления")
        val titleHolder = node("android.widget.RelativeLayout", children = arrayOf(title))
        val checkbox = node("android.widget.CheckBox", checked = false, checkable = true)
        val widgetFrame = node("android.widget.LinearLayout", children = arrayOf(checkbox))
        val row = node(
            "android.widget.LinearLayout",
            clickable = true,
            children = arrayOf(titleHolder, widgetFrame)
        )
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(row))
        link(row, titleHolder, widgetFrame)
        link(titleHolder, title)
        link(widgetFrame, checkbox)
        link(root, row)

        val found = SwitchFinder.findSwitch(root, listOf("Показывать уведомления"))

        assertEquals("тумблер строки уведомлений должен находиться", checkbox, found)
        assertFalse("checked_before читается как есть", SwitchFinder.isChecked(found!!))
    }

    /** Проставляет bounds узлу: без геометрии правила строки не работают. */
    private fun withBounds(node: AccessibilityNodeInfo, left: Int, top: Int, right: Int, bottom: Int) {
        Mockito.doAnswer { invocation ->
            (invocation.arguments[0] as Rect).set(left, top, right, bottom)
            null
        }.`when`(node).getBoundsInScreen(Mockito.any(Rect::class.java))
    }

    @Test
    fun `describe captures checked_before label and bounds`() {
        val switch = node("android.widget.Switch", checked = false, text = "Карусель", checkable = true)
        Mockito.doAnswer { invocation ->
            (invocation.arguments[0] as Rect).set(10, 20, 30, 40)
            null
        }.`when`(switch).getBoundsInScreen(Mockito.any(Rect::class.java))

        val hit = SwitchFinder.describe(switch, fallbackLabel = "fallback")

        assertFalse("checked_before = фактическое состояние до тумблера", hit.checkedBefore)
        assertEquals("Карусель", hit.label)
        assertEquals(10, hit.bounds.left)
        assertEquals(40, hit.bounds.bottom)
    }
}