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
    /** Профиль GLOBAL/MIUI13: тот же, что в setUp для выбора варианта каталога. */
    private val romProfile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V130",
        hyperOsHint = false,
        isTablet = false,
        family = RomFamily.MIUI,
        uiVersion = "13"
    )

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        SemanticCatalog.selectVariant(romProfile)
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
    fun `menu item in a popup window is found for the drill level`() {
        // Прогон 23–24.10 (shareme): меню «Ещё» открывается отдельным popup-окном, а
        // «О приложении» в rootInActiveWindow отсутствует — уровень-меню считался пустым,
        // drill падал drill_failed (recon after/shareme_more.xml).
        val active = node(className = "android.widget.FrameLayout", children = arrayOf(node(text = "ПЕРЕДАЧА")))
        val item = node(text = "О приложении", clickable = true, className = "android.widget.TextView")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(active)
        runner.popupRootsOverride = listOf(item)

        assertTrue(
            "текст popup-окна входит в текст экрана",
            runner.currentScreenText().contains("О приложении")
        )
        assertTrue(
            "пункт меню из popup-окна доступен для тапа",
            runner.findClickableByText(texts = listOf("О приложении")) === item
        )

        runner.popupRootsOverride = null
        assertFalse(
            "без popup-окна пункт меню не находится",
            runner.currentScreenText().contains("О приложении")
        )
        assertTrue(
            "и тапать нечего",
            runner.findClickableByText(texts = listOf("О приложении")) == null
        )
    }
    @Test
    fun `wall polling stops after empty reads`() {
        // Прогон 23–24.10: обработчик стен зовётся на шаге до 20 раз, и каждый пустой
        // опрос стоил ~0,9 с (dismiss-стена cause=network держала browser_sys 9 с).
        repeat(3) { runner.registerWallPollResult(0) }
        assertFalse("после трех пустых чтений опрос шага прекращается", runner.wallPollAllowed())
        runner.registerWallPollResult(1)
        assertTrue("найденная стена сбрасывает счетчик", runner.wallPollAllowed())
    }

    @Test
    fun `wall poll limit is per step execution, not per run`() {
        // SimpleRunner — один экземпляр на сервис: без сброса в начале исполнения шага
        // исчерпанный в форварде лимит переносился в откат, и стены там не опрашивались
        // вовсе (а откат — дорогое место: browser_sys 9 с по walls_recon.md).
        repeat(3) { runner.registerWallPollResult(0) }
        assertFalse("лимит исчерпан в предыдущем исполнении", runner.wallPollAllowed())

        // run() начинается со сброса лимита; поведение шага на моках не важно —
        // проверяется инвариант «лимит на исполнение шага».
        runCatching {
            runner.run(step("browser_sys"), romProfile, requireOverlay = false) { }
        }
        assertTrue("новое исполнение шага начинает счетчик с нуля", runner.wallPollAllowed())
        runner.cancel()
    }
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
    fun `menu level is not passed when the next title is already on the main screen`() = runTest {
        // Прогон 23–24.10 (music_sys в откате): меню шапки не открылось, но «Настройки» уже
        // было на главном экране — проверка «текст виден» объявляла уровень пройденным, drill
        // бил по ложному узлу и шаг падал drill_failed. Меню подтверждается только сменой экрана.
        val header = node(description = "Показать меню", clickable = true, className = "android.widget.ImageView")
        val main = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(
                header,
                node(text = "Поиск треков, плейлистов и артистов"),
                node(text = "Настройки")
            )
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(main)
        Mockito.`when`(header.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)

        val path = listOf(
            listOf("☰", "Показать меню", "Ещё", "More", "Больше"),
            listOf("Настройки", "Settings")
        )

        assertFalse(
            "меню не открылось — уровень не пройден, даже если текст следующего уровня виден",
            runner.drillIntoLevel(step("music_sys"), 0, path[0], path)
        )
    }

    @Test
    fun `menu level opens on the repeated tap when the first click was swallowed`() = runTest {
        // Тот же прогон: MIUI иногда проглатывает первый ACTION_CLICK по кнопке шапки. Второй
        // узловой тап открывает меню — уровень обязан пройти, а не уйти в ложный узел.
        val header = node(description = "Показать меню", clickable = true, className = "android.widget.ImageView")
        val main = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(header, node(text = "Поиск треков, плейлистов и артистов"))
        )
        val drawer = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Аккаунт и настройки"), node(text = "Настройки"))
        )
        var taps = 0
        var current: AccessibilityNodeInfo = main
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        Mockito.`when`(header.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            taps++
            if (taps > 1) current = drawer
            true
        }

        val path = listOf(
            listOf("☰", "Показать меню", "Ещё", "More", "Больше"),
            listOf("Настройки", "Settings")
        )

        assertTrue(
            "второй тап открыл меню — уровень пройден",
            runner.drillIntoLevel(step("music_sys"), 0, path[0], path)
        )
        assertTrue("первый тап был проглочен, понадобился повтор", taps >= 2)
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

    @Test
    fun `drill does not scroll a screen that already shows the step target`() = runTest {
        // Прогон rmuybnsfu (carousel): welcome-стена первого запуска приложения уходит
        // сама, а экран настроек с тумблером «Карусель экрана блокировки» дорисовывается,
        // когда бурение ещё ищет уровень «Блокировка экрана». Прежний код докручивал
        // уже открытый целевой экран до конца и ронял шаг в drill_failed.
        val title = node(text = "Карусель обоев", className = "android.widget.TextView")
        val row = node(text = "Карусель экрана блокировки", className = "android.widget.TextView")
        val settings = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(title, row)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(settings)
        captureGestures()

        val path = listOf(
            listOf("Блокировка экрана", "Lock screen"),
            listOf("Карусель обоев", "Wallpaper Carousel")
        )

        assertFalse(
            "уровня «Блокировка экрана» на целевом экране нет — уровень не пройден",
            runner.drillIntoLevel(step("carousel"), 0, path[0], path)
        )
        assertTrue(
            "целевой экран не должен прокручиваться: жесты=$gestureCenters",
            gestureCenters.isEmpty()
        )
    }

    @Test
    fun `positional overflow tap is blocked while an alert dialog is up`() {
        // Прогон rmvikvzqm (Загрузки): стена PrivacyGrantDialog висит, слепой тап в правый
        // верхний угол попадает по dim-фону ([0,0][1080,2179], clickable) и ЗАКРЫВАЕТ диалог
        // отменой — согласие не нажато, шаг упал по timeout и не восстановился.
        val title = node(text = "Условия использования", className = "android.widget.TextView")
        Mockito.`when`(title.viewIdResourceName).thenReturn("com.android.settings:id/alertTitle")
        val cancel = node(text = "Отмена", clickable = true, className = "android.widget.Button")
        Mockito.`when`(cancel.viewIdResourceName).thenReturn("android:id/button2")
        val agree = node(text = "Согласен", clickable = true, className = "android.widget.Button")
        Mockito.`when`(agree.viewIdResourceName).thenReturn("android:id/button1")
        val dialog = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(title, cancel, agree)
        )

        assertFalse(
            "при открытом диалоге позиционный тап запрещён",
            runner.positionalTapAllowed(dialog)
        )
        assertFalse("без окна тапать некуда", runner.positionalTapAllowed(null))
        assertTrue(
            "обычный экран без диалога позиционный тап разрешает",
            runner.positionalTapAllowed(node(className = "android.widget.FrameLayout"))
        )
    }

    @Test
    fun `degenerate bounds are not tapped`() {
        // MIUI отдаёт кнопку диалога с bounds [559,2524][1000,2400] (top>bottom): центр
        // (779,2462) за пределами дисплея, а прежний код сообщал ok=true о промахе —
        // диалог отзыва согласия оставался, шаг добирал ожидания (прогон rmvikvzqm).
        assertFalse(
            "вырожденные bounds не тапаем",
            runner.hasTappableBounds(android.graphics.Rect(559, 2524, 1000, 2400))
        )
        assertFalse(
            "пустые bounds не тапаем",
            runner.hasTappableBounds(android.graphics.Rect(0, 0, 0, 0))
        )
        assertFalse(
            "центр за пределами дисплея не тапаем",
            runner.hasTappableBounds(android.graphics.Rect(5000, 5000, 5100, 5100))
        )
        assertTrue(
            "обычная кнопка в пределах дисплея тапается",
            runner.hasTappableBounds(android.graphics.Rect(0, 0, 100, 200))
        )
    }

    @Test
    fun `bottom-edge node inside the real display is tappable`() {
        // Прогон 23–24.10 (mivideo): bounds [565,2116,809,2270] при дисплее 1080x2400 —
        // центр (687,2193) внутри экрана, но `resources.displayMetrics` контекста сервиса
        // системные вставки вычитает, и тап отклонялся как вырожденный: шаг уезжал в чужое
        // приложение (FAIL not_applicable, в screen — экран Mi Браузера).
        runner.displayBoundsOverride = android.graphics.Rect(0, 0, 1080, 2400)

        assertTrue(
            "узел у нижней кромки внутри реального дисплея тапается",
            runner.hasTappableBounds(android.graphics.Rect(565, 2116, 809, 2270))
        )
        assertFalse(
            "центр за пределами реального дисплея по-прежнему не тапаем",
            runner.hasTappableBounds(android.graphics.Rect(5000, 5000, 5100, 5100))
        )
    }
    @Test
    fun `exit control in the corner is never tapped by drill`() = runTest {
        // Прогон rmvimewq4 (music_sys): уровень «Показать меню» не подтвердился, угловой
        // fallback тапнул `tv_quit` («Выход») — приложение закрылось, шаг бурил системные
        // Настройки и упал `markers_mismatch`. «Выход» исключён из drill-поиска, слепой
        // жест по такому углу не выполняется.
        val quit = node(text = "Выход", clickable = true, className = "android.widget.TextView")
        Mockito.doAnswer {
            (it.arguments[0] as android.graphics.Rect).set(873, 91, 1025, 186)
            null
        }.`when`(quit).getBoundsInScreen(Mockito.any(android.graphics.Rect::class.java))
        var taps = 0
        Mockito.`when`(quit.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            taps++
            true
        }
        val screen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(quit, node(text = "Музыка"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(screen)
        captureGestures()

        val path = listOf(listOf("☰", "Показать меню"), listOf("Настройки"))
        val advanced = runner.drillIntoLevel(step("music_sys"), 0, path[0], path)

        assertFalse("уровень не подтверждён", advanced)
        assertTrue("тап по «Выход» не отправлялся", taps == 0)
        // Слепой overflow-жест бьёт в правый верхний угол; прокрутка экрана — в центр,
        // поэтому проверяем именно угловые жесты, а не «любые».
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        val cornerGestures = gestureCenters.count {
            it.first >= dm.widthPixels * 0.85f && it.second <= dm.heightPixels * 0.15f
        }
        assertTrue(
            "слепой жест по углу с «Выход» не выполнялся: $gestureCenters",
            cornerGestures == 0
        )
    }

    @Test
    fun `labelled control wins over generic container in drill`() = runTest {
        // Прогон rmvimewq4 (security_sys): drill тапнул безымянную карточку `FrameLayout`
        // вместо кнопки ⚙ с описанием «Настройки» — шаг ушёл на другой экран приложения
        // и упал `markers_mismatch`. Помеченный контрол важнее контейнера.
        val card = node(text = "Настройки", clickable = true, className = "android.widget.FrameLayout")
        val gear = node(description = "Настройки", clickable = true, className = "android.widget.Button")
        var cardTaps = 0
        var gearTaps = 0
        val settingsScreen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Разрешения"))
        )
        var current: AccessibilityNodeInfo = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(card, gear)
        )
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { current }
        Mockito.`when`(card.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            cardTaps++
            true
        }
        Mockito.`when`(gear.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            gearTaps++
            current = settingsScreen
            true
        }

        val path = listOf(listOf("⚙", "Настройки"), listOf("Разрешения"))
        val passed = runner.drillIntoLevel(step("security_sys"), 0, path[0], path)

        assertTrue("уровень пройден кнопкой ⚙", passed)
        assertTrue("кнопка ⚙ тапнута один раз: $gearTaps", gearTaps == 1)
        assertTrue("карточка не тапалась: $cardTaps", cardTaps == 0)
    }
}