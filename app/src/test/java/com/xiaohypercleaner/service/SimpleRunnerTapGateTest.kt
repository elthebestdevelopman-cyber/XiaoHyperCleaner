package com.xiaohypercleaner.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
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
 * Фаза A (A1+A2): честный гейт тапа и подтверждение эффекта тапа по тумблеру.
 *
 * Прогон rldmy9x8y: заэкранные bounds встречались (6 тапов), но провалы вызваны НЕ геометрией —
 * `ACTION_CLICK` диспатчится фреймворком и от координат не зависит (тап по «Нет, спасибо» с
 * центром y=2656 закрыл диалог за 18 мс). Реальная причина msa — непрошедший клик при ВАЛИДНЫХ
 * bounds: `via=parent depth=2`, состояние не изменилось, диалог не появился, `taps=0`.
 *
 * Отсюда правило: запрета ACTION_CLICK нет. Есть пометка `tap_unverified` для тапа по узлу с
 * непригодными bounds, подтверждение эффекта окном ожидания и ровно один координатный повтор.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerTapGateTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    /** Реальный дисплей приёмки: 1080×2400 (метрики контекста сервиса вычитают вставки). */
    private val display = Rect(0, 0, 1080, 2400)

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
        runner.displayBoundsOverride = display
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun node(
        className: String = "android.widget.FrameLayout",
        text: String? = null,
        bounds: Rect? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean = false,
        checkable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(enabled)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.isCheckable).thenReturn(checkable)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        if (bounds != null) {
            Mockito.doAnswer { invocation ->
                (invocation.arguments[0] as Rect).set(bounds)
                null
            }.`when`(n).getBoundsInScreen(Mockito.any())
        }
        return n
    }

    /** Реальное дерево Accessibility всегда имеет parent-ссылки. */
    private fun link(parent: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        children.forEach { Mockito.`when`(it.parent).thenReturn(parent) }
    }

    /** Строка настройки: подпись + тумблер в одном контейнере. */
    private fun row(
        label: String,
        checked: Boolean,
        enabled: Boolean = true,
        switchBounds: Rect = Rect(866, 300, 1000, 452),
        switchClickable: Boolean = true
    ): AccessibilityNodeInfo {
        val title = node(
            className = "android.widget.TextView",
            text = label,
            bounds = Rect(64, switchBounds.top, 700, switchBounds.bottom)
        )
        val toggle = node(
            className = "android.widget.Switch",
            bounds = switchBounds,
            clickable = switchClickable,
            enabled = enabled,
            checked = checked,
            checkable = true
        )
        val container = node(
            bounds = Rect(0, switchBounds.top, 1080, switchBounds.bottom),
            children = arrayOf(title, toggle)
        )
        link(container, title, toggle)
        return container
    }

    /** Синтетический id: семантика каталога в этих тестах не участвует. */
    private fun step(
        targetChecked: Boolean = false,
        confirmTexts: List<String> = listOf("Отключить")
    ) = SimpleSteps.Step(
        id = "tap_gate_probe",
        titleRu = "Шаг",
        titleEn = "Step",
        descRu = "Проверка гейта тапа.",
        descEn = "Tap gate probe.",
        intents = emptyList(),
        searchTexts = listOf("Лента виджетов"),
        targetChecked = targetChecked,
        manualHintRu = "Настройки → Лента.",
        manualHintEn = "Settings → Feed.",
        confirmTexts = confirmTexts,
        confirmWaitMs = 10_000L
    )

    // ─── A1: гейт bounds ─────────────────────────────────────────────────

    @Test
    fun `valid bounds are tappable`() {
        assertEquals(TapGate.TAP_OK, tapGateDecision(Rect(866, 1534, 1000, 1686), display, 2))
    }

    @Test
    fun `off-screen bounds wait for stability and then give up honestly`() {
        // Прогон rldmy9x8y: «Нет, спасибо» bounds=[80,2656,520,2400] — центр ниже дисплея.
        val offScreen = Rect(80, 2656, 520, 2400)
        assertEquals(TapGate.WAIT_STABILITY, tapGateDecision(offScreen, display, 1))
        assertEquals(TapGate.DEGENERATE, tapGateDecision(offScreen, display, 2))
    }

    @Test
    fun `bottom row stays tappable only against the real display`() {
        // D12/E2: узел у нижней кромки. Реальный дисплей 1080×2400 — тапаем; метрики контекста
        // сервиса (2270) объявили бы его вырожденным, поэтому они и не используются.
        val bottomRow = Rect(600, 2200, 780, 2400)
        assertEquals(TapGate.TAP_OK, tapGateDecision(bottomRow, display, 2))
        assertEquals(TapGate.DEGENERATE, tapGateDecision(bottomRow, Rect(0, 0, 1080, 2270), 2))
    }

    @Test
    fun `tap on an off-screen node is marked unverified and never taps by coordinates`() = runTest {
        val node = node(
            className = "android.widget.Button",
            bounds = Rect(80, 2656, 520, 2400),
            clickable = false
        )

        assertFalse("координатный тап по заэкранному узлу запрещён", runner.tapNode(node))
        assertTrue("тап обязан быть помечен неверифицированным", runner.lastTapUnverified)
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `off-screen node still gets ACTION_CLICK because it ignores coordinates`() = runTest {
        // Доказательство из прогона rldmy9x8y: ACTION_CLICK по заэкранной кнопке сработал.
        val node = node(
            className = "android.widget.Button",
            bounds = Rect(80, 2656, 520, 2400),
            clickable = true
        )
        Mockito.`when`(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)

        assertTrue(runner.tapNode(node))
        assertTrue(runner.lastTapUnverified)
    }

    @Test
    fun `stale node is re-acquired to stable bounds before the tap`() = runTest {
        val stale = node(
            className = "android.widget.LinearLayout",
            bounds = Rect(0, 2609, 1080, 2270),
            clickable = false
        )
        val stabilised = node(
            className = "android.widget.LinearLayout",
            bounds = Rect(0, 1723, 1080, 1882),
            clickable = true
        )
        Mockito.`when`(stabilised.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)

        assertTrue(runner.tapNode(stale) { stabilised })
        assertFalse(
            "bounds стабилизировались — пометки неверифицированного быть не должно",
            runner.lastTapUnverified
        )
    }

    // ─── A2: эффект тапа и один координатный повтор ──────────────────────

    @Test
    fun `dialog wins over state and unreadable state is no effect`() {
        assertEquals(
            ToggleEffect.DIALOG_PRESENT,
            toggleEffectOf(checkedNow = true, target = false, dialogVisible = true)
        )
        assertEquals(
            ToggleEffect.STATE_SETTLED,
            toggleEffectOf(checkedNow = false, target = false, dialogVisible = false)
        )
        assertEquals(
            ToggleEffect.NONE,
            toggleEffectOf(checkedNow = null, target = false, dialogVisible = false)
        )
        assertEquals(
            ToggleEffect.NONE,
            toggleEffectOf(checkedNow = true, target = false, dialogVisible = false)
        )
    }

    @Test
    fun `effect window never exceeds the dialog appearance ceiling`() {
        // Число не новое: потолок — MSA_DIALOG_APPEAR_WAIT_MS (2000 мс).
        assertTrue(runner.toggleEffectWindowMs() in 1L..2000L)
    }

    @Test
    fun `no effect leads to exactly one coordinate retry`() = runTest {
        val root = row("Лента виджетов", checked = true)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val effect = runner.retryToggleTapOnce(step(), listOf("Лента виджетов"))

        assertEquals(ToggleEffect.NONE, effect)
        Mockito.verify(service, Mockito.times(1))
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `retry is skipped while a dialog is on screen`() = runTest {
        // Проверка «диалог уже на экране» идёт ДО повтора: тап сквозь диалог запрещён,
        // появившийся диалог обрабатывает штатный обработчик стен.
        val dialogText = node(className = "android.widget.TextView", text = "Отключить ленту?")
        val root = node(
            children = arrayOf(row("Лента виджетов", checked = true), dialogText)
        )
        link(root, dialogText, root.getChild(0))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertEquals(ToggleEffect.NONE, runner.retryToggleTapOnce(step(), listOf("Лента виджетов")))
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `missing row is never retried`() = runTest {
        val root = node(children = arrayOf(node(className = "android.widget.TextView", text = "Пусто")))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertEquals(ToggleEffect.NONE, runner.retryToggleTapOnce(step(), listOf("Лента виджетов")))
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `degenerate bounds never get a coordinate retry`() = runTest {
        val root = row("Лента виджетов", checked = true, switchBounds = Rect(80, 2656, 520, 2400))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertEquals(ToggleEffect.NONE, runner.retryToggleTapOnce(step(), listOf("Лента виджетов")))
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }

    @Test
    fun `rom blocked row is never retried`() = runTest {
        // Откат и форвард идут одним потоком: заблокированный ROM тумблер (уведомления Ленты)
        // не получает даже координатного повтора — один честный провал, без цикла.
        val root = row(
            "Показывать уведомления",
            checked = true,
            enabled = false,
            switchClickable = false
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertEquals(
            ToggleEffect.NONE,
            runner.retryToggleTapOnce(step(), listOf("Показывать уведомления"))
        )
        Mockito.verify(service, Mockito.never())
            .dispatchGesture(Mockito.any(), Mockito.any(), Mockito.any())
    }
}