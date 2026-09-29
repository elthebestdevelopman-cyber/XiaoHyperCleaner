package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomFamily
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.RomRegion
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import android.accessibilityservice.GestureDescription
import android.graphics.RectF
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
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
 * Бурение маршрута: проверка результата каждого уровня.
 *
 * Регрессия прогона rmu8qhjhi:
 * - проверка ждала `firstOrNull()` уровня, а им у уровней-меню («️/Настройки»)
 *   оказывался иконочный глиф, которого на экране не бывает — drill падал без
 *   единой строки в логе (browser_sys, mivideo, security_sys, cleaner, downloads);
 * - уровень-меню возвращал true сразу после тапа, даже если меню не открылось
 *   (shareme, downloads);
 * - не кликабельный заголовок экрана («Приложения») считался непройденным уровнем
 *   (sys_recommendations).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerDrillTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        SemanticCatalog.selectVariant(
            RomProfile(
                region = RomRegion.GLOBAL,
                miuiVersion = "V130",
                hyperOsHint = false,
                isTablet = false,
                family = RomFamily.MIUI,
                uiVersion = "13"
            )
        )
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
        // performOverflowGesture/swipeUp читают displayMetrics сервиса.
        Mockito.`when`(service.resources).thenReturn(RuntimeEnvironment.getApplication().resources)
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun node(
        text: String? = null,
        description: String? = null,
        clickable: Boolean = false,
        className: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (description != null) Mockito.`when`(n.contentDescription).thenReturn(description)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(true)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    private fun step(id: String) = SimpleSteps.Step(
        id = id,
        titleRu = id,
        titleEn = id,
        descRu = id,
        descEn = id,
        intents = emptyList(),
        searchTexts = emptyList(),
        manualHintRu = id,
        manualHintEn = id
    )

    @Test
    fun `header menu wins over list item menu`() = runTest {
        // Музыка: `item_menu desc='Больше меню'` у каждой строки списка перехватывал тап
        // раньше кнопки шапки `header_menu desc='Показать меню'` — меню не открывалось,
        // шаг падал `drill_failed` на уровне 1 (прогон rmua2sd7x; дамп music_menu).
        val itemMenu = node(description = "Больше меню", clickable = true, className = "android.widget.ImageView")
        Mockito.`when`(itemMenu.viewIdResourceName).thenReturn("com.miui.player:id/item_menu")
        val header = node(description = "Показать меню", clickable = true, className = "android.widget.ImageView")
        Mockito.`when`(header.viewIdResourceName).thenReturn("com.miui.player:id/header_menu")
        val main = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(itemMenu, header)
        )
        val menuScreen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки"), node(text = "Спящий режим"))
        )
        var current: AccessibilityNodeInfo = main
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        // Меню трека экран не меняет, меню шапки — открывает экран с «Настройки».
        Mockito.`when`(itemMenu.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        Mockito.`when`(header.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            current = menuScreen
            true
        }

        val path = listOf(
            listOf("☰", "", "Ещё", "More", "Больше"),
            listOf("Настройки", "Settings"),
            listOf("Расширенные настройки", "Advanced settings")
        )

        assertTrue(
            "тап должен уйти в кнопку меню шапки, а не в «Больше меню» трека",
            runner.drillIntoLevel(step("music_sys"), 0, path[0], path)
        )
    }

    @Test
    fun `gear level is verified by its label not by the icon glyph`() = runTest {
        val profileTab = node(text = "Профиль", clickable = true)
        val screen1 = node(className = "android.widget.FrameLayout", children = arrayOf(profileTab))
        val gearLabel = node(text = "Настройки", className = "android.widget.TextView")
        val screen2 = node(className = "android.widget.FrameLayout", children = arrayOf(gearLabel))

        var current: AccessibilityNodeInfo = screen1
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        Mockito.`when`(profileTab.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            .thenAnswer {
                current = screen2
                true
            }

        val path = listOf(
            listOf("Профиль"),
            listOf("\u2699\uFE0F", "Настройки", "Settings")
        )

        assertTrue(
            "проверка уровня-меню идёт по подписи, а не по иконке",
            runner.drillIntoLevel(step("browser_sys"), 0, path[0], path)
        )
    }

    @Test
    fun `menu level is not passed when the menu did not open`() = runTest {
        val overflow = node(description = "Ещё", clickable = true)
        val screen = node(className = "android.widget.FrameLayout", children = arrayOf(overflow))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(screen)
        Mockito.`when`(overflow.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)

        val path = listOf(
            listOf("", "Ещё", "More"),
            listOf("О приложении")
        )

        assertFalse(
            "меню не открылось — уровень не пройден",
            runner.drillIntoLevel(step("shareme"), 0, path[0], path)
        )
    }

    @Test
    fun `level already passed when its label and the next level are on screen`() = runTest {
        val title = node(text = "Приложения", className = "android.widget.TextView")
        val row = node(text = "Все приложения", clickable = true)
        val screen = node(className = "android.widget.FrameLayout", children = arrayOf(title, row))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(screen)

        val path = listOf(listOf("Приложения"), listOf("Все приложения"))

        assertTrue(
            "заголовок экрана не ломает маршрут, если следующий уровень уже виден",
            runner.drillIntoLevel(step("sys_recommendations"), 0, path[0], path)
        )
    }

    @Test
    fun `last drill level is not passed when the screen did not change`() = runTest {
        // Загрузки: пункт «Настройки» в меню ⋮ не открыл экран, но уровень считался
        // пройденным (прежний `return true` без проверки) — тумблер искался в меню.
        val row = node(text = "Настройки", clickable = true)
        val screen = node(className = "android.widget.FrameLayout", children = arrayOf(row))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(screen)
        Mockito.`when`(row.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        Mockito.`when`(service.resources).thenReturn(RuntimeEnvironment.getApplication().resources)

        val path = listOf(
            listOf("⋮", "Ещё"),
            listOf("Настройки", "Settings")
        )

        assertFalse(
            "тап без смены экрана — уровень не пройден",
            runner.drillIntoLevel(step("downloads"), 1, path[1], path)
        )
    }

    @Test
    fun `last drill level is passed when the screen changed`() = runTest {
        val row = node(text = "Настройки", clickable = true)
        val first =
            node(className = "android.widget.FrameLayout", children = arrayOf(node(text = "Меню"), row))
        val second = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки"), node(text = "Получать рекомендации"))
        )
        var current: AccessibilityNodeInfo = first
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        Mockito.`when`(row.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            current = second
            true
        }

        val path = listOf(listOf("⋮"), listOf("Настройки", "Settings"))

        assertTrue(
            "смена экрана подтверждает последний уровень",
            runner.drillIntoLevel(step("downloads"), 1, path[1], path)
        )
    }

    @Test
    fun `missing level text marks the step as not applicable`() = runTest {
        // GetApps 20.4.5: раздела «Конфиденциальность» в настройках магазина нет —
        // уровень отсутствует, шаг должен стать неприменимым, а не FAIL.
        val screen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки"), node(text = "Уведомления"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(screen)

        assertFalse(
            runner.drillIntoLevel(step("getapps"), 2, listOf("Конфиденциальность", "Privacy"))
        )
        assertTrue("отсутствие уровня — признак неприменимости", runner.lastDrillLevelNotFound)
    }

    @Test
    fun `second level node is tried when the first opens a wrong screen`() = runTest {
        // GetApps: в профиле несколько подписей «Настройки» — первая открывает нативные
        // настройки магазина (без «Конфиденциальности»), вторая («гайка») — нужный экран.
        val wrongScreen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки"), node(text = "Уведомления"))
        )
        val rightScreen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(
                node(text = "Настройки"),
                node(text = "Конфиденциальность"),
                node(text = "Персональные рекомендации")
            )
        )
        val first = node(text = "Настройки", clickable = true)
        val second = node(text = "Настройки", clickable = true)
        val base = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Профиль"), first, second)
        )
        var current: AccessibilityNodeInfo = base
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        Mockito.`when`(first.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            current = wrongScreen
            true
        }
        Mockito.`when`(second.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            current = rightScreen
            true
        }
        Mockito.`when`(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            .thenAnswer {
                current = base
                true
            }

        val path = listOf(listOf("Профиль"), listOf("Настройки"), listOf("Конфиденциальность"))

        assertTrue(
            "альтернативный узел уровня должен быть опробован",
            runner.drillIntoLevel(step("getapps"), 1, path[1], path)
        )
    }

    /** Границы узла: `getBoundsInScreen` заполняет переданный Rect. */
    private fun bounds(n: AccessibilityNodeInfo, l: Int, t: Int, r: Int, b: Int) {
        Mockito.`when`(n.getBoundsInScreen(Mockito.any(android.graphics.Rect::class.java)))
            .thenAnswer { inv ->
                (inv.arguments[0] as android.graphics.Rect).set(l, t, r, b)
                null
            }
    }

    /** Координаты центров инжектированных жестов (для проверки «тот же узел»). */
    private val gestureCenters = mutableListOf<Pair<Float, Float>>()

    private fun captureGestures() {
        gestureCenters.clear()
        Mockito.`when`(service.dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenAnswer { inv ->
                val gesture = inv.arguments[0] as GestureDescription
                val path = gesture.getStroke(0).path
                val rect = RectF()
                path.computeBounds(rect, true)
                gestureCenters.add(rect.centerX() to rect.centerY())
                (inv.arguments[1] as AccessibilityService.GestureResultCallback).onCompleted(gesture)
                true
            }
    }

    @Test
    fun `coordinate retry is injected inside the passthrough window`() = runTest {
        // Прогон rmumuqr53: `tap: Button id=action_my … via=node` отчитался успехом, а
        // координатный повтор ушёл в перехватывающее окно (`touch intercepted x=972 y=2182`),
        // passthrough открылся на 60 мс позже — тап пропал. Повтор обязан идти ПОСЛЕ
        // подтверждения окна, то есть между запросом passthrough и инъекцией жеста.
        val row = node(
            text = "Дополнительные настройки",
            clickable = true,
            className = "android.widget.TextView"
        )
        Mockito.`when`(row.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(false)
        bounds(row, 100, 200, 300, 400)
        val root = node(children = arrayOf(row))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)
        captureGestures()

        runner.drillIntoLevel(
            step("browser_sys"),
            0,
            listOf("Дополнительные настройки"),
            listOf(listOf("Дополнительные настройки"))
        )

        val order = Mockito.inOrder(service)
        order.verify(service).startService(Mockito.any())
        order.verify(service).dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
        assertTrue("повтор ушёл в центр того же узла", gestureCenters.contains(200f to 300f))
    }

    @Test
    fun `drill retries the same node before switching to another candidate`() = runTest {
        // Прогон rmumuqr53: ретрай уровня ударил по СОСЕДНЕМУ узлу (action_tabs вместо
        // action_my) и открыл диалог «Закрытие всех вкладок». Повтор обязан держать
        // тот же узел (те же bounds/id), а новый кандидат — только после повторов.
        val my = node(text = "Настройки", clickable = true, className = "android.widget.Button")
        Mockito.`when`(my.viewIdResourceName).thenReturn("com.mi.globalbrowser:id/action_my")
        Mockito.`when`(my.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(false)
        bounds(my, 100, 200, 300, 400)
        val tabs = node(text = "Настройки", clickable = true, className = "android.widget.Button")
        Mockito.`when`(tabs.viewIdResourceName).thenReturn("com.mi.globalbrowser:id/action_tabs")
        Mockito.`when`(tabs.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(false)
        bounds(tabs, 600, 200, 800, 400)
        val root = node(children = arrayOf(my, tabs))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)
        captureGestures()

        runner.drillIntoLevel(
            step("browser_sys"),
            0,
            listOf("Настройки"),
            listOf(listOf("Настройки"))
        )

        val firstCandidateHits = gestureCenters.takeWhile { it == 200f to 300f }.size
        assertTrue(
            "повторы держат тот же узел до исчерпания: центры=$gestureCenters",
            firstCandidateHits >= 3
        )
        assertTrue(
            "соседний узел трогается только после повторов: центры=$gestureCenters",
            gestureCenters.drop(firstCandidateHits).contains(700f to 300f)
        )
    }

    @Test
    fun `icon only level texts are not used for screen verification`() {
        val path = listOf(listOf("Профиль"), listOf("\u2699\uFE0F", "Настройки"))

        assertFalse(
            "иконочный текст уровня не годится как маркер экрана",
            runner.isVerifiableText("\u2699\uFE0F")
        )
        assertFalse(runner.isVerifiableText("⋮"))
        assertTrue(runner.isMenuLevel(path[1]))
        assertTrue(
            "проверка идёт по подписи уровня",
            runner.nextLevelVerificationTexts(path, 1) == listOf("Настройки")
        )
    }
}