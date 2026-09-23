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

    @Test
    fun `switch below the label inside a row sized container is accepted`() {
        // Реальный кейс ads_personalization (MIUI 13, дамп прогона rmu8qhjhi):
        // подпись [80,477][834,544], пояснение ниже, CheckBox [866,638][1000,790] —
        // вертикального пересечения с подписью нет, но контейнер соразмерен строке.
        val label = node("android.widget.TextView", text = "Персонализированная реклама")
        val summary = node("android.widget.TextView", text = "Включение или отключение функции")
        val checkbox = node("android.widget.CheckBox", checked = true, checkable = true, clickable = true)
        val row = node("android.widget.RelativeLayout", clickable = true, children = arrayOf(label, summary, checkbox))
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(row))
        link(root, row)
        link(row, label, summary, checkbox)
        withBounds(label, 80, 477, 834, 544)
        withBounds(summary, 80, 548, 846, 952)
        withBounds(checkbox, 866, 638, 1000, 790)
        withBounds(row, 80, 477, 1000, 952)

        assertEquals(
            "тумблер строки «подпись + пояснение» должен находиться",
            checkbox,
            SwitchFinder.findSwitch(root, listOf("Персонализированная реклама"))
        )
    }

    @Test
    fun `switch far below the label inside a full list is rejected`() {
        // Защита от ложного OK: контейнер во всю высоту экрана строкой не является.
        val label = node("android.widget.TextView", text = "Персонализированная реклама")
        val checkbox = node("android.widget.CheckBox", checked = false, checkable = true, clickable = true)
        val list = node("androidx.recyclerview.widget.RecyclerView", clickable = false, children = arrayOf(label, checkbox))
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(list))
        link(root, list)
        link(list, label, checkbox)
        withBounds(label, 80, 477, 834, 544)
        withBounds(checkbox, 866, 1800, 1000, 1952)
        withBounds(list, 0, 0, 1080, 2400)

        assertNull(
            "тумблер ниже сгиба в общем списке не принимается",
            SwitchFinder.findSwitch(root, listOf("Персонализированная реклама"))
        )
    }

    @Test
    fun `switch is found when the first matching label is a title or a hint`() {
        // Реальный кейс msa (дамп прогона rmu8qhjhi): тексты шага совпадают и с
        // заголовком экрана, и с подсказкой внизу списка, и с подписью строки.
        // Прежний поиск останавливался на ПЕРВОМ совпавшем узле: от заголовка он
        // поднимался к контейнеру всего списка и сдавался с `ambiguous row`.
        val title = node("android.widget.TextView", text = "Доступ к личным данным")
        val rowLabel = node("android.widget.TextView", text = "msa")
        val checkbox = node("android.widget.CheckBox", checked = true, checkable = true)
        val row = node(
            "android.widget.LinearLayout",
            clickable = true,
            children = arrayOf(rowLabel, checkbox)
        )
        val hint = node(
            "android.widget.TextView",
            text = "Для обеспечения безопасности аккаунта выполните вход, если вы хотите отозвать разрешения"
        )
        val list = node("android.widget.ListView", children = arrayOf(title, row, hint))
        val root = node("android.widget.FrameLayout", children = arrayOf(list))
        link(root, list)
        link(list, title, row, hint)
        link(row, rowLabel, checkbox)
        withBounds(title, 193, 133, 887, 209)
        withBounds(rowLabel, 253, 278, 825, 345)
        withBounds(checkbox, 866, 251, 1000, 388)
        withBounds(row, 80, 251, 1000, 388)
        withBounds(hint, 80, 2131, 1000, 2242)
        withBounds(list, 0, 251, 1080, 2270)

        assertEquals(
            "тумблер строки «msa» должен находиться несмотря на совпавшие заголовок и подсказку",
            checkbox,
            SwitchFinder.findSwitch(root, listOf("Доступ к личным данным", "Отозвать", "msa"))
        )
    }

    @Test
    fun `primary target text wins over a secondary row that is earlier in the tree`() {
        // Реальный кейс Mi Music (прогон rmuef7nbf): у шага несколько целевых строк, и
        // второстепенная («Сервисы онлайн-контента», включена) шла в дереве ПЕРВОЙ.
        // Поиск «первый совпавший узел дерева» возвращал чужой включённый тумблер: шаг
        // тумблил не ту строку и уходил в timeout вместо already_off по главной цели.
        val secondaryWrapper = node(
            "android.widget.Switch", checked = true, desc = "Сервисы онлайн-контента",
            clickable = true, checkable = true
        )
        val primaryWrapper = node(
            "android.widget.Switch", checked = false, desc = "Показывать рекламу",
            clickable = true, checkable = true
        )
        val list = node(
            "androidx.recyclerview.widget.RecyclerView",
            clickable = false,
            children = arrayOf(secondaryWrapper, primaryWrapper)
        )
        val root = node("android.widget.FrameLayout", clickable = false, children = arrayOf(list))
        link(root, list)
        link(list, secondaryWrapper, primaryWrapper)
        withBounds(secondaryWrapper, 0, 2039, 1080, 2193)
        withBounds(primaryWrapper, 0, 826, 1080, 980)
        withBounds(list, 0, 245, 1080, 2270)

        val found = SwitchFinder.findSwitch(
            root,
            listOf("Показывать рекламу", "Сервисы онлайн-контента")
        )

        assertEquals(
            "главная цель шага (первый текст) приоритетнее вторичной строки",
            primaryWrapper,
            found
        )
        assertFalse("состояние читается у целевой строки", SwitchFinder.isChecked(found!!))
    }
}