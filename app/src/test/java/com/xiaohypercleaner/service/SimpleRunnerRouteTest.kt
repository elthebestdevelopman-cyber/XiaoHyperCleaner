package com.xiaohypercleaner.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomFamily
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.RomRegion
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Маршрут шага — подсказка, а не замена навигации (master-task v2).
 *
 * Регрессии прогонов rmupuzwh1/rmupuud3s: carousel `route intent failed
 * (ActivityNotFound)` и всё равно «подтверждал» экран на чужом окне Настроек;
 * cleaner читал тумблер security_sys на главном экране Безопасности, потому что
 * экран не подтверждался пакетом-владельцем. Контракт: `executeRouteScript`
 * отдаёт `false` — вызывающий уходит в авто-навигацию; экран подтверждается
 * пакетом ИЗ каталога И маркерами.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerRouteTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
        // Оверлея в тестах нет: без этого гейт целостности окна вернёт false, и
        // маршрут «провалится» не по своей причине.
        runner.overlayGateRequired = false
        // Прокрутка (swipeUp) читает displayMetrics сервиса.
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
        pkg: String? = null,
        className: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (pkg != null) Mockito.`when`(n.packageName).thenReturn(pkg)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `top right blank header icon is tapped positionally`() = runTest {
        // Mi Apps: шестерёнка настроек профиля — кликабельный TextView без текста, desc и
        // id (дамп diagnostic_snapshot_getapps_*.json, прогон rmuwvcqiw). Выбор — самая
        // правая пустая иконка заголовка: колокольчик уведомлений левее.
        val title = node(text = "Защита приложений")
        val bell = blankIcon(827, 902, 123, 200)
        val gear = blankIcon(946, 1023, 123, 200)
        var iconTapped = false
        Mockito.`when`(gear.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            iconTapped = true
            true
        }
        // Полоса заголовка WebView-профиля: подпись + две пустые кликабельные иконки.
        val header = node(pkg = "com.xiaomi.mipicks", children = arrayOf(title, bell, gear))
        Mockito.`when`(header.getBoundsInScreen(any())).thenAnswer { inv ->
            (inv.arguments[0] as android.graphics.Rect).set(0, 0, 1080, 203)
            null
        }
        val root = node(pkg = "com.xiaomi.mipicks", children = arrayOf(header))
        Mockito.`when`(root.getBoundsInScreen(any())).thenAnswer { inv ->
            (inv.arguments[0] as android.graphics.Rect).set(0, 0, 1080, 2400)
            null
        }
        // До тапа активное окно — профиль, после ACTION_CLICK по иконке экран сменился
        // (в WebView переход асинхронный): повторный жест по центру не нужен.
        val shifted = node(text = "Настройки Конфиденциальность", pkg = "com.xiaomi.mipicks")
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { if (iconTapped) shifted else root }

        val tapped = runner.tapRouteNode(step("getapps"), topRightBlank = true)

        assertTrue("шестерёнка нажата", tapped)
        Mockito.verify(gear).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Mockito.verify(bell, Mockito.never()).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Кликабельная иконка заголовка без текста/описания с заданной рамкой. */
    private fun blankIcon(left: Int, right: Int, top: Int, bottom: Int): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.isClickable).thenReturn(true)
        Mockito.`when`(n.getBoundsInScreen(any())).thenAnswer { inv ->
            (inv.arguments[0] as android.graphics.Rect).set(left, top, right, bottom)
            null
        }
        return n
    }

    private fun step(id: String) = SimpleSteps.Step(
        id = id,
        titleRu = id,
        titleEn = id,
        descRu = id,
        descEn = id,
        intents = emptyList(),
        searchTexts = listOf("Получать рекомендации"),
        manualHintRu = "",
        manualHintEn = "",
        confirmTexts = emptyList(),
        confirmWaitMs = 0L
    )

    private fun carouselRouteItem() = SemanticCatalog.RouteItem(
        intent = "fashiongallery://home",
        confirmPackage = listOf("com.miui.android.fashiongallery"),
        confirmMarkers = listOf("Карусель обоев")
    )

    private fun cleanerRouteItem() = SemanticCatalog.RouteItem(
        intent = "com.miui.cleaner/com.miui.optimizecenter.settings.SettingsActivity",
        confirmPackage = listOf("com.miui.cleaner"),
        confirmMarkers = listOf("Настройки очистки")
    )

    @Test
    fun `route intent failure returns false so the caller falls back to auto-nav`() = runTest {
        Mockito.doThrow(ActivityNotFoundException())
            .`when`(service).startActivity(any())
        Mockito.`when`(service.rootInActiveWindow).thenReturn(null)

        val reached = runner.executeRouteScript(step("carousel"), listOf(carouselRouteItem()))

        assertFalse(
            "компонента не открылась — маршрут обязан отдать false, а не «подтвердить» чужой экран",
            reached
        )
    }

    @Test
    fun `missing route item on a sub screen is restored with one back`() = runTest {
        // Прогон rmvjqdfrt: Mi Браузер открывается на вложенном экране «Безопасность»,
        // пункта «Дополнительные настройки» там нет, список не скроллится — шаг объявлялся
        // неприменимым и в форварде, и в откате. Один BACK поднимает к корню настроек,
        // после чего пункт ищется заново.
        val subScreen = node(
            pkg = "com.mi.globalbrowser",
            className = "android.widget.FrameLayout",
            children = arrayOf(
                node(text = "Безопасность", pkg = "com.mi.globalbrowser"),
                node(text = "Сохранять пароли", pkg = "com.mi.globalbrowser")
            )
        )
        var backPressed = false
        val advanced = node(
            text = "Дополнительные настройки",
            pkg = "com.mi.globalbrowser",
            className = "android.widget.TextView"
        )
        Mockito.`when`(advanced.isClickable).thenReturn(true)
        var advancedTapped = false
        Mockito.`when`(advanced.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            advancedTapped = true
            true
        }
        val settingsRoot = node(
            pkg = "com.mi.globalbrowser",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Основные настройки"), advanced)
        )
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { if (backPressed) settingsRoot else subScreen }
        Mockito.`when`(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            .thenAnswer { backPressed = true; true }

        val reached = runner.executeRouteScript(
            SimpleSteps.ALL.first { it.id == "browser_sys" },
            listOf(SemanticCatalog.RouteItem(tapText = "Дополнительные настройки", waitMs = 100L))
        )

        assertTrue("подъём к корню выполнен ровно одним BACK", backPressed)
        assertTrue("пункт маршрута найден и нажат после восстановления уровня", advancedTapped)
        assertTrue("маршрут дошёл до целевого экрана", reached)
    }

    @Test
    fun `confirmed route returns true`() = runTest {
        val root = node(
            pkg = "com.miui.cleaner",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки очистки"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val reached = runner.executeRouteScript(step("cleaner"), listOf(cleanerRouteItem()))

        assertTrue("дошли до целевого экрана — тумблер ищет общий хвост шага", reached)
    }

    @Test
    fun `route screen confirmed by package and markers`() = runTest {
        val root = node(
            pkg = "com.miui.cleaner",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки очистки"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertTrue(runner.confirmRouteIntentScreen(step("cleaner"), cleanerRouteItem()))
    }

    @Test
    fun `foreign foreground package fails route screen confirmation`() = runTest {
        // Экран Безопасности содержит те же слова («РЕКОМЕНДАЦИИ»), поэтому одного
        // совпадения маркеров мало: пакет окна обязан быть пакетом-владельцем.
        val root = node(
            pkg = "com.miui.securitycenter",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Настройки очистки"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse(runner.confirmRouteIntentScreen(step("cleaner"), cleanerRouteItem()))
    }

    @Test
    fun `route markers absent on screen fail confirmation`() = runTest {
        val root = node(
            pkg = "com.miui.cleaner",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Что-то другое"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse(runner.confirmRouteIntentScreen(step("cleaner"), cleanerRouteItem()))
    }

    @Test
    fun `entry confirmation does not trust the stale screen of the previous step`() = runTest {
        // Прогон rmuybnsfu: carousel подтвердил вход по слову «Включить» с ОСТАТКА экрана
        // google_diagnostics (GMS) и ушёл бурить чужой экран. Пока на переднем плане чужой
        // пакет, текст экрана вход не подтверждает — ждём окно целевого пакета.
        val stale = node(
            pkg = "com.google.android.gms",
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Включить"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(stale)

        assertFalse(
            "маркер чужого (оставшегося) экрана не подтверждает вход",
            runner.awaitEntryScreen(
                step("carousel"),
                null,
                listOf("Включить"),
                100L,
                "com.miui.android.fashiongallery"
            )
        )
        assertTrue(
            "без гейта пакета подтверждение остаётся текстовым (подэкраны Настроек)",
            runner.awaitEntryScreen(step("carousel"), null, listOf("Включить"), 100L)
        )
    }

    @Test
    fun `entry gate package only applies to a foreign foreground`() {
        // Экран карусели открывается публичной схемой (внутренние активности закрыты),
        // поэтому гейт обязан работать и для интента БЕЗ компоненты, но с пакетом.
        val carouselIntent = Intent(Intent.ACTION_VIEW, Uri.parse("fashiongallery://home"))
            .setPackage("com.miui.android.fashiongallery")
        val foreign = node(pkg = "com.google.android.gms", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreign)
        assertEquals(
            "чужой экран на переднем плане — ждём пакет интента",
            "com.miui.android.fashiongallery",
            runner.entryGatePackage(carouselIntent)
        )

        val same = node(
            pkg = "com.miui.android.fashiongallery",
            className = "android.widget.FrameLayout"
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(same)
        assertNull(
            "пакет интента уже на переднем плане — гейт не нужен",
            runner.entryGatePackage(carouselIntent)
        )
        assertNull(
            "интент без компоненты и без пакета гейта не имеет",
            runner.entryGatePackage(Intent("android.settings.SETTINGS"))
        )
    }

    @Test
    fun `entry gate honours an explicit component`() {
        val componentIntent = Intent().setComponent(
            ComponentName(
                "com.miui.cleaner",
                "com.miui.optimizecenter.settings.SettingsActivity"
            )
        )
        val foreign = node(pkg = "com.google.android.gms", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreign)

        assertEquals(
            "явная компонента задаёт владельца экрана",
            "com.miui.cleaner",
            runner.entryGatePackage(componentIntent)
        )
    }

    @Test
    fun `target grace window always leaves the toggle reserve`() {
        // Карусель: бюджет 20 c, маршрут 4 уровня — окно «цель дорисовалась» не имеет права
        // съесть время тумблера (резерв 4 c), иначе шаг падает budget_exhausted вместо
        // честного drill_failed.
        assertEquals(3_000L, runner.targetGraceBudgetMs(Long.MAX_VALUE))
        assertEquals(
            "резерв тумблера вычитается из окна ожидания",
            2_000L,
            runner.targetGraceBudgetMs(6_000L)
        )
        assertTrue(
            "резерва нет — ожидания нет (skip окна, а не пустая трата бюджета)",
            runner.targetGraceBudgetMs(3_000L) <= 0L
        )
    }

    @Test
    fun `foreign window guard blocks third party apps during drill`() = runTest {
        // Прогон rmuyfjtvk: промо-ссылка свежей Mi Music открыла Google Play, и дрил продолжил
        // тапать и скроллить чужой магазин. Инвариант: робот взаимодействует только с
        // приложениями шага (и системными поверхностями навигации).
        val playerStep = step("music_sys").copy(launchPackage = "com.miui.player")

        val vending = node(pkg = "com.android.vending", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(vending)
        assertTrue(
            "чужое приложение (Play) — не окно шага",
            runner.isForeignWindowFor(playerStep)
        )

        val own = node(pkg = "com.miui.player", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(own)
        assertFalse("пакет шага — своё окно", runner.isForeignWindowFor(playerStep))

        val settings = node(pkg = "com.android.settings", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(settings)
        assertFalse(
            "Настройки — законная поверхность (notif_*/settings-маршруты)",
            runner.isForeignWindowFor(playerStep)
        )

        Mockito.`when`(service.rootInActiveWindow).thenReturn(null)
        assertFalse("окно не прочиталось — шаг не блокируем", runner.isForeignWindowFor(playerStep))
    }

    @Test
    fun `drill bails out on a foreign window even when the level text is present`() = runTest {
        val row = node(text = "Настройки", pkg = "com.android.vending", className = "android.widget.TextView")
        val foreignRoot = node(pkg = "com.android.vending", children = arrayOf(row))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreignRoot)
        val playerStep = step("music_sys").copy(launchPackage = "com.miui.player")

        assertFalse(
            "уровень виден, но окно чужое — уровень не проходит (без тапов и прокруток)",
            runner.drillIntoLevel(playerStep, 1, listOf("Настройки"), listOf(listOf("Настройки")))
        )
    }

    @Test
    fun `route intent with package and no markers confirms by package only`() = runTest {
        // Прогоны rmuybnsfu/rmuyfjtvk: домашний экран магазина не подтверждался маркерами ЦЕЛИ
        // шага («Конфиденциальность» живёт глубже по маршруту), и подсказка getapps умирала
        // на 1/6. Подтверждение идёт пакетом-владельцем из каталога.
        val homeRow = node(text = "РЕКОМЕНДУЕМЫЕ")
        val home = node(
            pkg = "com.xiaomi.mipicks",
            className = "android.widget.FrameLayout",
            children = arrayOf(homeRow)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(home)
        val item = SemanticCatalog.RouteItem(
            intent = "com.xiaomi.mipicks/.DefaultAlias",
            confirmPackage = listOf("com.xiaomi.mipicks")
        )

        assertTrue(
            "пакет владельца подтверждает экран без подстановки маркеров цели",
            runner.awaitRouteScreen(step("getapps"), item, 100L)
        )
    }

    @Test
    fun `tap route item with markers fails when the destination screen is absent`() = runTest {
        // Прогон rmuyfjtvk (filemanager): «Настройки» тапнулись, экран не открылся, и следующий
        // уровень маршрута искали на главном экране. Тап-шаг подсказки обязан подтвердиться.
        val memRow = node(text = "Внутренняя память")
        val explorerMain = node(
            pkg = "com.mi.globalFileexplorer",
            className = "android.widget.FrameLayout",
            children = arrayOf(memRow)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(explorerMain)
        val item = SemanticCatalog.RouteItem(
            tapId = "more_action_btn",
            confirmMarkers = listOf("Информация")
        )

        assertFalse(
            "экран «Информация» не открылся — подсказка отдаёт false в авто-навигацию",
            runner.executeRouteScript(step("filemanager"), listOf(item))
        )
    }

    @Test
    fun `foreign relaunch budget is granted once per step`() {
        // В логе не должно быть более одной строки foreign_window_relaunch на шаг
        // (прогон rmuyfjtvk): приложение не поднялось — честный foreign_window,
        // а не повторный цикл возврата.
        assertTrue("первый возврат разрешён", runner.claimForeignRelaunch("music_sys"))
        assertFalse("второй возврат на том же шаге запрещён", runner.claimForeignRelaunch("music_sys"))
        assertTrue("другой шаг — свой бюджет", runner.claimForeignRelaunch("themes"))
        runner.resetForeignRelaunchBudget()
        assertTrue("новый прогон — бюджет снова доступен", runner.claimForeignRelaunch("music_sys"))
    }

    @Test
    fun `bottom tap margin is never zero and is capped by a fifth of the window`() {
        assertTrue(
            "отступ не может быть нулевым — тап ушёл бы в жест-зону навбара",
            runner.clampBottomTapMargin(0, 2400) > 0
        )
        assertEquals("отступ ограничен пятой частью окна", 200, runner.clampBottomTapMargin(300, 1000))
        assertEquals("высота окна неизвестна — отступ не режется", 300, runner.clampBottomTapMargin(300, 0))
        assertEquals(
            "вырожденно малое окно: режет верхняя граница, а не минимум",
            20,
            runner.clampBottomTapMargin(300, 100)
        )
    }

    @Test
    fun `row at the bottom window edge is not fully visible for a tap`() {
        val root = node(pkg = "com.miui.videoplayer", className = "android.widget.FrameLayout")
        bounds(root, 0, 0, 1080, 2400)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val edgeRow = node(text = "Онлайн-рекомендации", className = "android.widget.TextView")
        bounds(edgeRow, 60, 2300, 900, 2399)
        assertFalse(
            "строка вплотную к нижней кромке — тап попадёт в жест-зону",
            runner.isFullyVisible(edgeRow)
        )

        val topRow = node(text = "Онлайн-рекомендации", className = "android.widget.TextView")
        bounds(topRow, 60, 300, 900, 420)
        assertTrue("строка в рабочей области считается видимой", runner.isFullyVisible(topRow))
    }

    @Test
    fun `route uri intent is a view intent with new task flag`() {
        // Deep-link приложения (`mimarket://settings` — проба OK: экран настроек GetApps):
        // запускается из фонового сервиса, поэтому NEW_TASK обязателен, а пакет приложения
        // шага ограничивает обработчика (и телефон не уводит в чужое приложение).
        val uri = runner.parseRouteIntent("mimarket://settings", "com.xiaomi.mipicks")

        assertEquals(Intent.ACTION_VIEW, uri.action)
        assertEquals("mimarket://settings", uri.data.toString())
        assertEquals("com.xiaomi.mipicks", uri.`package`)
        assertTrue(
            "NEW_TASK обязателен для старта из сервиса",
            uri.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun `route component spec still resolves to an explicit component`() {
        val component = runner.parseRouteIntent(
            "com.mi.android.globalminusscreen/.tab.TabSettingActivity",
            "com.mi.android.globalminusscreen"
        )
        assertEquals(
            ComponentName(
                "com.mi.android.globalminusscreen",
                "com.mi.android.globalminusscreen.tab.TabSettingActivity"
            ),
            component.component
        )
    }

    @Test
    fun `step budget accounts for the route length without raising the ceiling`() {
        val profile = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "V130",
            hyperOsHint = false,
            isTablet = false,
            family = RomFamily.MIUI,
            uiVersion = "13"
        )
        // getapps: базовый путь приложения + 2 пункта маршрута по 2 с каждый. Берём
        // настоящий шаг плана: синтетический (без launchPackage/пути) базовый бюджет ниже.
        val realGetapps = SimpleSteps.ALL.first { it.id == "getapps" }
        assertTrue(
            "длина маршрута учтена в бюджете шага",
            runner.computeTimeout(realGetapps, profile) >= 24_000L
        )
        // Потолок не поднят: ни один шаг каталога не выходит за 45 с.
        val over = SimpleSteps.ALL.filter { runner.computeTimeout(it, profile) > 45_000L }
        assertTrue("потолок бюджета превышен у: ${over.map { it.id }}", over.isEmpty())
    }

    @Test
    fun `blank header icon hunt fails fast without scrolling`() = runTest {
        // Прогон rmuyl4opt (getapps): охота за шестерёнкой крутила 3 попытки с прокрутками
        // ~12 с на чужом экране и валила шаг в timeout без строки в логе. Теперь — одна
        // попытка и честный false, без жестов прокрутки.
        val foreignHome = node(pkg = "com.xiaomi.mipicks", className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreignHome)

        assertFalse(
            "иконки шапки нет — пункт маршрута обязан упасть быстро",
            runner.tapRouteNode(step("getapps"), topRightBlank = true)
        )
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(any(), any(), any())
    }

    @Test
    fun `route id tap does not scroll a content screen that does not move`() = runTest {
        // Экран предпросмотра карусели (прогон rmv8b85e7): прокрутка НЕ меняет текст экрана,
        // значит это контент, а не список — слепые свайпы запрещены, иначе робот листает обои
        // вместо тапа по шестерёнке (`scroll: container down` ×2 → confirm_miss).
        // Каждое чтение — СВЕЖИЙ узел: currentScreenText() рециклит прочитанный узел, и
        // thenReturn с одним моком делал бы «экран не изменился» по построению мока, а не
        // по факту контентного экрана.
        Mockito.`when`(service.rootInActiveWindow).thenAnswer {
            node(pkg = "com.miui.android.fashiongallery", text = "Начать просмотр")
        }
        Mockito.`when`(service.dispatchGesture(any(), any(), any())).thenReturn(false)

        val tapped = runner.tapRouteNode(step("carousel"), id = "btn_setting", routeIndex = 2)

        assertFalse("узла нет и экран не двигается — тапа нет", tapped)
        // Ровно ОДИН жест: второй свайп на контентном экране — это и был баг 3.2, а
        // atMost(1) пропускал бы и полное отключение прокрутки.
        Mockito.verify(service, Mockito.times(1)).dispatchGesture(any(), any(), any())
    }

    @Test
    fun `route id tap scrolls a real list until the node appears`() = runTest {
        // Настоящий список: целевой узел ниже сгиба. Прокрутка двигает экран (текст
        // меняется), поэтому второй проход обязан случиться и нажать найденный узел —
        // гейт «крутить только при смене экрана» не смеет отключать прокрутку на списках.
        val gear = node(text = "Раздел списка")
        Mockito.`when`(gear.viewIdResourceName)
            .thenReturn("com.miui.android.fashiongallery:id/btn_setting")
        Mockito.`when`(gear.isClickable).thenReturn(true)
        var tappedNode = false
        Mockito.`when`(gear.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            tappedNode = true
            true
        }
        // Прокрутка через контейнер (`ACTION_SCROLL_FORWARD`), а не жестом: жест ждёт
        // callback от системы (`suspendCancellableCoroutine`), в моке он не придёт, и
        // тест повис бы до таймаута. Контейнерный путь — тот же `scroll: container down`.
        val list = node(pkg = "com.miui.android.fashiongallery")
        Mockito.`when`(list.isScrollable).thenReturn(true)
        Mockito.`when`(list.getBoundsInScreen(any())).thenAnswer { inv ->
            (inv.arguments[0] as android.graphics.Rect).set(0, 0, 1080, 2000)
            null
        }
        var scrolled = false
        Mockito.`when`(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            .thenAnswer {
                scrolled = true
                true
            }
        Mockito.`when`(service.rootInActiveWindow).thenAnswer {
            if (scrolled) {
                node(pkg = "com.miui.android.fashiongallery", children = arrayOf(gear))
            } else {
                node(
                    pkg = "com.miui.android.fashiongallery",
                    text = "Верх списка",
                    children = arrayOf(list)
                )
            }
        }

        val result = runner.tapRouteNode(step("carousel"), id = "btn_setting", routeIndex = 2)

        assertTrue("прокрутка сдвинула список — пункт маршрута пройден", result)
        assertTrue("тап ушёл в найденный узел", tappedNode)
        // Жестов не было вовсе: список листался контейнером, и это доказывает, что гейт
        // «крутить только при смене экрана» не выключил прокрутку.
        Mockito.verify(service, Mockito.never()).dispatchGesture(any(), any(), any())
    }

    @Test
    fun `warm-up budget is spent once per step and reset by a new run`() {
        // Прогрев Ленты (appvault_services, прогон rmv16fbxe) — самый дорогой повтор шага:
        // открыть ленту, дождаться карточек, закрыть, повторить маршрут. Второй прогрев
        // внутри того же шага жёг бы бюджет шага, а новый прогон обязан получить бюджет заново.
        assertTrue("первый прогрев шага разрешён", runner.claimWarmUp("appvault_services"))
        assertFalse(
            "повторный прогрев на том же шаге не выдаётся",
            runner.claimWarmUp("appvault_services")
        )
        assertTrue("у другого шага свой бюджет", runner.claimWarmUp("appvault_about"))

        runner.resetWarmUpBudget()

        assertTrue("новый прогон выдаёт бюджет заново", runner.claimWarmUp("appvault_services"))
    }

    /**
     * F5a: пакеты-цели шага (launchPackage + requiredPackages) — семя набора «своих» окон.
     *
     * Прежнее семя из одного launchPackage оставляло набор пустым у carousel (launchPackage
     * пуст, пакет задан только requiredPackages): `isForegroundStepPackage` считал окно своим,
     * а `isForeignWindowFor` — чужим, и drill рубился на первом же уровне (прогон rmvb6atul).
     */
    @Test
    fun `required packages alone make the step window its own`() = runTest {
        val carousel = step("carousel")
        val fashion = node(pkg = "com.miui.android.fashiongallery")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(fashion)

        runner.seedEntryPackages(carousel)

        assertFalse(
            "окно пакета-цели чужим не считается",
            runner.isForeignWindowFor(carousel)
        )
        assertTrue(
            "пакет окна подтверждает экран доп. целей",
            runner.extraTargetsPackageOk("com.miui.android.fashiongallery")
        )
    }

    @Test
    fun `a step without package targets keeps the previous behaviour`() = runTest {
        // Шаг без launchPackage и без requiredPackages: семя пусто, чужое окно остаётся чужим,
        // а проверка пакета для доп. целей по-прежнему вакуумная (прежнее поведение).
        val bare = step("browser_sys")
        val foreign = node(pkg = "com.google.android.gms")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreign)

        runner.seedEntryPackages(bare)

        assertTrue("чужое окно остаётся чужим", runner.isForeignWindowFor(bare))
        assertTrue(
            "пустое семя проверку пакета не выполняет",
            runner.extraTargetsPackageOk("com.google.android.gms")
        )
    }

    @Test
    fun `settings window does not confirm extra targets of another app step`() = runTest {
        // Строгая семантика package_ok: Настройки «своими» для чужого шага не становятся —
        // иначе возвращалось ложное подтверждение экрана на странице «Блокировка экрана»
        // (прогон rmv6x9ts1, carousel: markers_ok=true, package_ok=true).
        val carousel = step("carousel")
        runner.seedEntryPackages(carousel)

        assertFalse(
            "Настройки экран доп. целей не подтверждают",
            runner.extraTargetsPackageOk("com.android.settings")
        )
        assertTrue(
            "непрочитанный пакет проверку не выполняет (прежнее поведение)",
            runner.extraTargetsPackageOk(null)
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
}