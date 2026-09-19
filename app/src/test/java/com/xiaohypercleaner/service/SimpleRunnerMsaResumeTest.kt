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
 * Раннер: resume бурения и подтверждение отзыва msa по отсчёту.
 *
 * Ключевое поведение: экран, уже совпавший с уровнем маршрута/целью, продолжает
 * бурение с нужного места, а msa без фактического подтверждения отзыва — fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerMsaResumeTest {

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
        enabled: Boolean = true,
        checked: Boolean = false,
        checkable: Boolean = false,
        className: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (description != null) Mockito.`when`(n.contentDescription).thenReturn(description)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(enabled)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.isCheckable).thenReturn(checkable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    private fun msaStep() = SimpleSteps.Step(
        id = "msa",
        titleRu = "MSA",
        titleEn = "MSA",
        descRu = "Отзыв доступа msa.",
        descEn = "Revoke msa access.",
        intents = emptyList(),
        searchTexts = listOf("msa"),
        manualHintRu = "Настройки → Доступ к личным данным → msa.",
        manualHintEn = "Settings → Access to personal data → msa.",
        confirmTexts = listOf("Отозвать", "ОК"),
        confirmWaitMs = 10_000L
    )

    @Test
    fun `resume starts from the level already visible on screen`() {
        val screen = node(text = "Доступ к личным данным", className = "android.widget.TextView")
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(screen))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val path = listOf(
            listOf("Пароли и безопасность", "Отпечатки, данные лица и защита устройства"),
            listOf("Доступ к личным данным", "Авторизация и отзыв")
        )

        assertEquals(
            "старт с уровня, который уже на экране",
            1,
            runner.resumeDrillIndex(msaStep(), path)
        )
    }

    @Test
    fun `resume skips drilling when the target screen is already open`() {
        val text = node(
            text = "msa Доступ к личным данным",
            className = "android.widget.TextView"
        )
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(text))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val path = listOf(listOf("Пароли и безопасность"), listOf("Доступ к личным данным"))

        assertEquals(
            "цель на экране — бурение не нужно",
            path.size,
            runner.resumeDrillIndex(msaStep(), path)
        )
    }

    @Test
    fun `resume falls back to zero when nothing matches`() {
        val text = node(text = "О телефоне", className = "android.widget.TextView")
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(text))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertEquals(0, runner.resumeDrillIndex(msaStep(), listOf(listOf("Конфиденциальность"))))
    }

    @Test
    fun `msa revoke is confirmed after countdown button and settle pause`() = runTest {
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))

        val msaSwitch =
            node(text = "msa", checked = false, checkable = true, className = "android.widget.Switch")
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(dialogRoot, listRoot, listRoot)

        val confirmed = runner.confirmDelayedRevoke(msaStep(), listOf("Отозвать"), listOf("msa"))

        assertTrue("отзыв должен быть подтверждён фактическим состоянием", confirmed)
    }

    @Test
    fun `msa without enabled revoke button fails honestly`() = runTest {
        val disabled = node(text = "Отозвать", clickable = true, enabled = false)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(disabled))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(dialogRoot)

        val confirmed = runner.confirmDelayedRevoke(msaStep(), listOf("Отозвать"), listOf("msa"))

        assertFalse("без подтверждения шаг не может быть успешным", confirmed)
    }
}