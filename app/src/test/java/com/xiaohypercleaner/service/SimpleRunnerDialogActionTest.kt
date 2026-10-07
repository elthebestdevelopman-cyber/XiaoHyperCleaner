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
import org.junit.Assert.assertEquals
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
 * Свой диалог подтверждения ВМЕСТО тумблера — это действие шага.
 *
 * Приёмочный прогон rmuojptft: строка «О ленте виджетов» открывает диалог
 * «Отключить службы?» → «Отключить». В прогоне rmuslthv7 шаг падал
 * `low_confidence`/`not_applicable`, потому что тумблера на экране нет вовсе.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerDialogActionTest {

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
        // Прокрутка строки-цели берёт размеры экрана: у мока они должны быть настоящими.
        Mockito.`when`(service.resources)
            .thenReturn(RuntimeEnvironment.getApplication().resources)
        runner = SimpleRunner(service)
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
        className: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(enabled)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `own confirm dialog on the target screen is the step action`() = runTest {
        val title = node(text = "Персонализированные услуги")
        val body = node(text = "Отключить службы?")
        val button = node(
            text = "Отключить",
            className = "android.widget.Button",
            clickable = true
        )
        Mockito.`when`(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(title, body, button)
        )
        val afterRoot = node(text = "Персонализированные услуги О ленте виджетов")
        // Состояние по факту тапа: до тапа — диалог, после — экран без кнопки.
        var tapped = false
        Mockito.`when`(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            tapped = true
            true
        }
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { if (tapped) afterRoot else dialogRoot }

        val step = SimpleSteps.ALL.first { it.id == "appvault_about" }
        val result = runner.findAndToggleSwitch(step)

        assertEquals(
            "свой диалог подтверждён — шаг выполнен действием",
            "toggled",
            result.reason
        )
        assertTrue(result.success)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `own confirm dialog with the wrong resulting state fails honestly`() = runTest {
        // Третий исход после via_dialog: диалог закрылся, строка читается и состояние НЕ
        // целевое — честный провал, а не «диалог закрылся, значит готово» (шаг 2.6).
        val title = node(text = "Персонализированные услуги")
        val body = node(text = "Отключить службы?")
        val button = node(
            text = "Отключить",
            className = "android.widget.Button",
            clickable = true
        )
        val dialogRoot = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(title, body, button)
        )
        // Цель appvault_about — выключить; после диалога строка осталась включённой.
        val row = node(text = "Персонализированные услуги", className = "android.widget.CheckBox")
        Mockito.`when`(row.isChecked).thenReturn(true)
        val afterRoot = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "О ленте виджетов"), row)
        )
        var tapped = false
        Mockito.`when`(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenAnswer {
            tapped = true
            true
        }
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { if (tapped) afterRoot else dialogRoot }

        val step = SimpleSteps.ALL.first { it.id == "appvault_about" }
        val result = runner.findAndToggleSwitch(step)

        assertFalse(
            "диалог не привёл тумблер к цели — шаг не может считаться успешным",
            result.success
        )
        assertEquals("причина — честный verify_failed", "verify_failed", result.reason)
    }
}