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
        // Целевой экран TOGGLE-шага подтверждается строкой с тумблером (гейт уверенности):
        // подпись без переключателя — заголовок, а не строка настройки.
        Mockito.doAnswer {
            (it.arguments[0] as android.graphics.Rect).set(80, 300, 800, 400)
            null
        }.`when`(text).getBoundsInScreen(Mockito.any(android.graphics.Rect::class.java))
        val toggle = node(className = "android.widget.Switch", clickable = true, checkable = true)
        Mockito.doAnswer {
            (it.arguments[0] as android.graphics.Rect).set(866, 300, 1000, 400)
            null
        }.`when`(toggle).getBoundsInScreen(Mockito.any(android.graphics.Rect::class.java))
        val row = node(className = "android.widget.LinearLayout", children = arrayOf(text, toggle))
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(row))
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
    fun `revoke is confirmed when the progress dialog closes and the row reads off`() = runTest {
        // rmvgvhbzu: после тапа диалог прогресса остался на экране, settle вышел через 355 мс
        // (признак прогресса не видел), verify читал перекрытый экран → ложный
        // `revoke_not_confirmed`, хотя отзыв состоялся. Теперь settle ждёт ЗАКРЫТИЯ диалога
        // и только потом читает строку: диалог → прогресс → список с выключенным тумблером.
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        val msaSwitch =
            node(text = "msa", checked = false, checkable = true, className = "android.widget.Switch")
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(
                dialogRoot, dialogRoot, dialogRoot,
                progressRoot, progressRoot, progressRoot, progressRoot,
                progressRoot, progressRoot, progressRoot, progressRoot,
                listRoot
            )

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertTrue("диалог закрылся, тумблер off — отзыв подтверждён", confirmed)
    }

    @Test
    fun `revoke is not confirmed while the progress dialog is still on screen`() = runTest {
        // rmuslthv7: после тапа «Отозвать» MIUI показывает прогресс «Отзыв разрешения…»;
        // строка msa исчезла из дерева, и шаг объявлял успех через 480 мс, оставив
        // прогресс открытым — следующий шаг (sys_recommendations) ушёл в timeout.
        val revoke = node(
            text = "Отозвать",
            className = "android.widget.Button",
            clickable = true,
            enabled = true
        )
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, progressRoot, progressRoot, progressRoot, progressRoot)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse(
            "прогресс на экране — отзыв не подтверждён, шаг обязан отдать fail",
            confirmed
        )
    }

    @Test
    fun `progress dialog that never closes reports a timeout reason`() = runTest {
        // Тап состоялся, но диалог прогресса не закрылся за окно: это честный таймаут
        // (`progress_dialog_timeout`), а не «состояние не подтвердилось».
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, dialogRoot, dialogRoot, progressRoot)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse("диалог не закрылся — подтверждения нет", confirmed)
        assertEquals("progress_dialog_timeout", runner.lastRevokeReason)
    }

    @Test
    fun `single flickering signal does not end the settle`() = runTest {
        // Одинокая «диалог ушёл» между двумя «диалог жив» — не подтверждение: нужен второй
        // такой же сигнал подряд. Без confirmations=2 ожидание обрывалось бы на первом.
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        val msaSwitch =
            node(text = "msa", checked = false, checkable = true, className = "android.widget.Switch")
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, dialogRoot, dialogRoot, listRoot, progressRoot, listRoot, listRoot)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertTrue("мигнувший сигнал не обрывает ожидание — отзыв подтверждён", confirmed)
    }

    @Test
    fun `retry decision needs all six conditions`() {
        assertTrue(
            "все условия выполнены",
            runner.msaRetryDecision(false, true, false, 2_500L, 3_000L, 4_000L)
        )
        assertFalse(
            "пол 2 с не пройден",
            runner.msaRetryDecision(false, true, false, 1_500L, 3_000L, 4_000L)
        )
        assertFalse(
            "окно фазы кнопки исчерпано",
            runner.msaRetryDecision(false, true, false, 8_000L, 8_000L, 2_000L)
        )
        assertFalse(
            "остаток окна меньше опроса",
            runner.msaRetryDecision(false, true, false, 2_500L, 3_000L, 100L)
        )
        assertFalse(
            "на экране прогресс — повтор запрещён",
            runner.msaRetryDecision(false, true, true, 2_500L, 3_000L, 4_000L)
        )
        assertFalse(
            "уже повторяли",
            runner.msaRetryDecision(true, true, false, 2_500L, 3_000L, 4_000L)
        )
        assertFalse(
            "кнопки нет",
            runner.msaRetryDecision(false, false, false, 2_500L, 3_000L, 4_000L)
        )
    }

    @Test
    fun `progress phase never re-taps the revoke button`() = runTest {
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, dialogRoot, dialogRoot, progressRoot)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse("диалог не закрылся — подтверждения нет", confirmed)
        Mockito.verify(revoke, Mockito.times(1)).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertEquals("progress_dialog_timeout", runner.lastRevokeReason)
    }

    @Test
    fun `empty root alone does not prove the dialog is gone`() = runTest {
        // Два пустых чтения подряд — НЕ подтверждение «диалог ушёл»: это неизвестное состояние.
        // Иначе settle обрывался бы на переходе окон и verify читал бы прогресс-диалог.
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val progressRoot = node(text = "Отзыв разрешения…")
        val msaSwitch =
            node(text = "msa", checked = false, checkable = true, className = "android.widget.Switch")
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(
                dialogRoot, dialogRoot, dialogRoot,
                null, null, progressRoot, progressRoot,
                listRoot
            )

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertTrue("пустые чтения не обрывают ожидание — отзыв подтверждён по строке", confirmed)
    }

    @Test
    fun `persistent unknown root reports no_root_window`() = runTest {
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, dialogRoot, dialogRoot, null)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse("подтверждения нет", confirmed)
        assertEquals("no_root_window", runner.lastRevokeReason)
    }

    @Test
    fun `cancellation during settle is not a timeout`() = runTest {
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        var reads = 0
        Mockito.`when`(service.rootInActiveWindow).thenAnswer {
            reads++
            if (reads > 3) {
                runner.cancel()
                null
            } else {
                dialogRoot
            }
        }

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse("отмена не подтверждает отзыв", confirmed)
        assertEquals("отмена — это не таймаут", "cancelled", runner.lastRevokeReason)
    }

    @Test
    fun `pre-tap checked_before does not prove the post-tap state`() = runTest {
        // Строка исчезла, пост-тапового чтения не было: доказательства нет → «не подтверждено»,
        // а не «провал». Дотаповое знание состояния сюда не подставляется.
        val revoke = node(text = "Отозвать", clickable = true, enabled = true)
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val otherScreen = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Пароли и безопасность"))
        )
        Mockito.`when`(service.rootInActiveWindow)
            .thenReturn(dialogRoot, dialogRoot, dialogRoot, otherScreen)

        val confirmed = runner.confirmDelayedRevoke(
            msaStep(),
            listOf("Отозвать", "ОК", "Отзыв разрешения"),
            listOf("msa")
        )

        assertFalse("строки нет — подтверждения нет", confirmed)
        assertEquals("пост-тапового чтения не было", null, runner.revokeStateReadAfterTap)
        assertEquals("state_unreadable", runner.lastRevokeReason)
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