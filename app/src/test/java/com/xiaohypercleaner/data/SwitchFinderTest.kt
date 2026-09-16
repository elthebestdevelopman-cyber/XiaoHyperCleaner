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