package com.xiaohypercleaner.service

import android.content.ActivityNotFoundException
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
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
        intent = "com.miui.android.fashiongallery/com.miui.cw.feature.ui.setting.SettingActivity",
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
}