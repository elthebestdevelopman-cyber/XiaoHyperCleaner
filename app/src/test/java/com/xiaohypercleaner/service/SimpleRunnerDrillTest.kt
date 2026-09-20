package com.xiaohypercleaner.service

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