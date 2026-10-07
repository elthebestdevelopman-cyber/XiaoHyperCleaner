package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
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
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Отсчётный диалог msa: нажимается КНОПКА, а не сообщение диалога.
 *
 * Прогон rmua0pt7i: `findEnabledClickableByText` брал первый узел с текстом,
 * содержащим «Отозвать», — им оказывалось сообщение «…Отозвать разрешение?»,
 * тап уходил по его координатам, диалог оставался («Отозвать (6 с)») и шаг падал
 * `revoke_not_confirmed`. Плюс кнопка неактивна, пока идёт отсчёт.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerMsaRevokeTest {

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
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun node(
        text: String? = null,
        id: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        checked: Boolean = false,
        checkable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (id != null) Mockito.`when`(n.viewIdResourceName).thenReturn(id)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isEnabled).thenReturn(enabled)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.isCheckable).thenReturn(checkable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    @Test
    fun `dialog message is never chosen instead of the button`() {
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            className = "android.widget.TextView"
        )
        val revoke = node(
            text = "Отозвать",
            id = "android:id/button1",
            className = "android.widget.Button",
            clickable = true
        )
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(message, revoke))

        val found = runner.findDialogConfirmButton(root, listOf("Отозвать"))

        assertEquals("должна нажиматься кнопка, а не сообщение", revoke, found)
    }

    @Test
    fun `countdown label is recognised before tapping`() {
        assertTrue(runner.isCountdownLabel("Отозвать (9 с)"))
        assertTrue(runner.isCountdownLabel("Revoke (9s)"))
        assertFalse(runner.isCountdownLabel("Отозвать"))
        assertTrue(runner.isCountdownConfirmLabel("Отозвать (9 с)", listOf("Отозвать")))
        assertFalse(
            "сообщение диалога не считается кнопкой",
            runner.isCountdownConfirmLabel(
                "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
                listOf("Отозвать")
            )
        )
    }

    @Test
    fun `no button means no plain-text tap`() {
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            clickable = true
        )
        val root = node(className = "android.widget.FrameLayout", children = arrayOf(message))

        assertNull(runner.findDialogConfirmButton(root, listOf("Отозвать")))
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
    fun `delayed confirm step asks for revoke before verifying`() = runTest {
        // Регрессия rmupuud3s: диалог отсчёта msa перекрывает экран, тумблер исчез из
        // дерева; правило честности verify («узла нет» при видимом confirm-тексте =
        // провал) роняло шаг `verify_failed` ДО попытки отозвать. Порядок обязан быть
        // «сначала подтверждение отзыва», а провал — `revoke_not_confirmed`.
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            className = "android.widget.TextView"
        )
        val countdown = node(
            text = "Отозвать (5 с)",
            id = "android:id/button1",
            className = "android.widget.TextView"
        )
        val root = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(message, countdown)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val result = runner.postToggleConfirm(msaStep(), listOf("msa"))

        assertEquals(
            "msa обязан провалиться как revoke_not_confirmed, а не verify_failed",
            "revoke_not_confirmed",
            result?.reason
        )
    }

    @Test
    fun `plain toggle step is not routed into the delayed revoke path`() = runTest {
        // Не-DELAYED шаг не должен попадать в revoke-путь msa: его пост-тап путь
        // остаётся прежним (подтверждение → verify) и завершается успешно.
        val root = node(className = "android.widget.FrameLayout")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)
        val plain = SimpleSteps.Step(
            id = "__plain_probe__",
            titleRu = "plain",
            titleEn = "plain",
            descRu = "plain",
            descEn = "plain",
            intents = emptyList(),
            searchTexts = listOf("plain"),
            manualHintRu = "",
            manualHintEn = "",
            confirmTexts = emptyList(),
            confirmWaitMs = 0L
        )

        val result = runner.postToggleConfirm(plain, listOf("plain"))

        assertNull("не-DELAYED шаг не уходит в revoke_not_confirmed", result)
    }

    @Test
    fun `revoke is confirmed right after the dialog closes without the full settle wait`() = runTest {
        // Settle обязан выходить сразу по закрытию диалога: прежний цикл сжигал до
        // 12 с, хотя состояние тумблера уже читалось целевым (прогон rmuvijbl1: msa 30 с).
        val revoke = node(
            text = "Отозвать",
            id = "android:id/button1",
            className = "android.widget.Button",
            clickable = true,
            enabled = true
        )
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(revoke)
        )
        val msaSwitch = node(
            text = "msa",
            checked = false,
            checkable = true,
            className = "android.widget.Switch"
        )
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(dialogRoot, listRoot, listRoot, listRoot)

        val confirmed = runner.confirmDelayedRevoke(msaStep(), listOf("Отозвать"), listOf("msa"))

        assertTrue("отзыв подтверждён фактическим состоянием тумблера", confirmed)
    }

    @Test
    fun `already revoked switch is accepted without waiting the countdown`() = runTest {
        // Диалога нет вовсе (MIUI 13 отозвала доступ прямо по чекбоксу): состояние
        // тумблера целевое — шаг подтверждается коротким окном появления диалога,
        // а не полным отсчётом ~10 с.
        val msaSwitch = node(
            text = "msa",
            checked = false,
            checkable = true,
            className = "android.widget.Switch"
        )
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(msaSwitch))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(listRoot)

        val confirmed = runner.confirmDelayedRevoke(msaStep(), listOf("Отозвать"), listOf("msa"))

        assertTrue("отзыв уже применён — подтверждаем по состоянию", confirmed)
    }

    @Test
    fun `revoke is not confirmed while the switch stayed on even after the dialog closed`() = runTest {
        // Прогон rmuvlyyor: диалог ушёл сам, а чекбокс msa остался ВКЛючённым. Прежний
        // вердикт `dialogGone && onTargetScreen` (маркеры пусты = «целевой экран»)
        // отдавал ложный успех — теперь успех только по фактическому состоянию.
        val revoke = node(
            text = "Отозвать",
            id = "android:id/button1",
            className = "android.widget.Button",
            clickable = true,
            enabled = true
        )
        Mockito.`when`(revoke.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val dialogRoot = node(className = "android.widget.FrameLayout", children = arrayOf(revoke))
        val stayedOn = node(
            text = "msa",
            checked = true,
            checkable = true,
            className = "android.widget.Switch"
        )
        val listRoot = node(className = "android.widget.FrameLayout", children = arrayOf(stayedOn))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(dialogRoot, listRoot, listRoot, listRoot)

        val confirmed = runner.confirmDelayedRevoke(msaStep(), listOf("Отозвать"), listOf("msa"))

        assertFalse("живой чекбокс — отзыв не подтверждён", confirmed)
    }

    @Test
    fun `dialog title matching a confirm text is not chosen instead of the button`() {
        // Прогон rmusp726z: в confirmTexts msa есть ЗАГОЛОВОК «Отзыв разрешения»,
        // и прежний отбор брал именно его; кликабельный предок заголовка — контейнер
        // диалога (parentPanel), тап уходил по пустому месту и отзыв не подтверждался.
        val title = node(
            text = "Отзыв разрешения",
            id = "android:id/alertTitle",
            className = "android.widget.TextView"
        )
        val message = node(
            text = "После отзыва разрешения приложение прекратит сбор данных. Отозвать разрешение?",
            id = "android:id/message",
            className = "android.widget.TextView"
        )
        val revoke = node(
            text = "Отозвать",
            id = "android:id/button1",
            className = "android.widget.Button",
            clickable = true
        )
        val root = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(title, message, revoke)
        )

        val found = runner.findDialogConfirmButton(
            root, listOf("Отозвать", "ОК", "Отзыв разрешения")
        )

        assertTrue(
            "должна выбираться кнопка «Отозвать», а не заголовок диалога",
            found === revoke
        )
    }
}
