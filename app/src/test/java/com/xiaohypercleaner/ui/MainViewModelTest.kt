package com.xiaohypercleaner.ui

import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PlanBuilder
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.data.SimplePlan
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.service.SimpleRunner
import com.xiaohypercleaner.service.SimpleStepBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-тесты для [MainViewModel].
 *
 * Проверяют:
 * - Начальное состояние ViewModel
 * - Логику показа/скрытия диалогов
 * - Переходы между фазами Simple Mode
 * - Обработку пользовательских действий
 *
 * АРХИТЕКТУРА:
 * - Robolectric для эмуляции Android-контекста
 * - runTest из kotlinx-coroutines-test для StateFlow
 * - БЕЗ InstantTaskExecutorRule (нужен только для LiveData)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        vm = MainViewModel(context)
    }

    // ═══════════════════════════════════════════════════════════════
    // Начальное состояние
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `already off steps are collected once as verified state`() {
        // Отчёт должен различать «выключено сейчас» и «было выключено»: пользователь
        // просит видеть фактическое состояние тумблеров (GetApps: рекомендации уже off).
        vm.simpleController.noteAlreadyOff("getapps")
        vm.simpleController.noteAlreadyOff("getapps")
        vm.simpleController.noteAlreadyOff("cleaner")

        assertEquals(
            listOf("getapps", "cleaner"),
            vm.simpleController.snapshot.alreadyOffStepIds
        )
    }

    @Test
    fun `initial state is clean`() {
        val state = vm.state.value

        assertFalse("isWorking should be false", state.isWorking)
        assertFalse("isOptimized should be false", state.isOptimized)
        assertEquals(
            "simpleModePhase should be INACTIVE",
            SimpleModePhase.INACTIVE,
            state.simpleModePhase
        )
        assertFalse("showLevelDialog should be false", state.showLevelDialog)
        assertFalse("showLevelConfirm should be false", state.showLevelConfirm)
    }

    // ═══════════════════════════════════════════════════════════════
    // Start Flow
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `startFlow shows level dialog`() = runTest {
        vm.startFlow()

        assertTrue(
            "showLevelDialog should be true after startFlow",
            vm.state.value.showLevelDialog
        )
    }

    @Test
    fun `startFlow is ignored when already working`() = runTest {
        vm.startFlow()
        val firstState = vm.state.value

        vm.startFlow() // повторный вызов
        val secondState = vm.state.value

        assertEquals(
            "state should not change on second startFlow",
            firstState.showLevelDialog,
            secondState.showLevelDialog
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Level Selection
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `onLevelChosen SIMPLE shows confirm dialog`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.SIMPLE)

        val state = vm.state.value
        assertFalse("showLevelDialog should be closed", state.showLevelDialog)
        assertTrue("showLevelConfirm should be shown", state.showLevelConfirm)
        assertEquals(
            "selectedLevel should be SIMPLE",
            OptimizationMode.SIMPLE,
            state.selectedLevel
        )
    }

    @Test
    fun `onLevelChosen PRO shows confirm dialog`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.PRO)

        val state = vm.state.value
        assertFalse("showLevelDialog should be closed", state.showLevelDialog)
        assertTrue("showLevelConfirm should be shown", state.showLevelConfirm)
        assertEquals(
            "selectedLevel should be PRO",
            OptimizationMode.PRO,
            state.selectedLevel
        )
    }

    @Test
    fun `cancelLevelConfirm resets dialog state`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.SIMPLE)
        vm.cancelLevelConfirm()

        val state = vm.state.value
        assertFalse("showLevelConfirm should be false", state.showLevelConfirm)
    }

    // ═══════════════════════════════════════════════════════════════
    // Confirm and Start
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `installer pre-step is asked after permissions, not before the start`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.SIMPLE)

        vm.confirmLevelStart(OptimizationMode.SIMPLE)

        // Сначала разрешения: до их выдачи уход в настройки гасит окно установщика, и предшаг
        // приходилось запускать повторным кликом (прогон rmve63243).
        assertFalse("showLevelConfirm should be closed", vm.state.value.showLevelConfirm)
        assertTrue("simple mode starts right away", vm.state.value.simpleModeActive)
        assertFalse(
            "installer pre-step must not be shown before permissions",
            vm.state.value.showInstallerPreStep
        )

        // Оба разрешения выданы → предшаг появляется до первого шага.
        vm.applyPermissionStatuses(acc = true, overlay = true)
        assertTrue(
            "installer pre-step should be shown after permissions",
            vm.state.value.showInstallerPreStep
        )

        // «Пропустить» → поток продолжается без перезапуска режима.
        vm.onInstallerPreStepSkip()

        assertFalse("pre-step dialog should be closed", vm.state.value.showInstallerPreStep)
        assertTrue(
            "simple mode should continue after the pre-step is skipped",
            vm.state.value.simpleModeActive
        )
    }

    @Test
    fun `confirmLevelStart with PRO begins pro flow`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.PRO)

        // ИСПРАВЛЕНО: передаём параметр level
        vm.confirmLevelStart(OptimizationMode.PRO)

        val state = vm.state.value
        assertFalse("showLevelConfirm should be closed", state.showLevelConfirm)
    }

    // ═══════════════════════════════════════════════════════════════
    // Ведро «не подтверждено» (D1b, прогон rmvgvhbzu)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `state_unreadable goes to the unconfirmed bucket, not to failures`() = runTest {
        // Мост отдаёт причину строкой (AdbEnablerService:568), поэтому вход — литерал.
        // Ожидание привязано к константе раннера: смена её значения уронит тест осознанно,
        // потому что боевое сравнение в handleSimpleStepResult смотрит на константу.
        val steps = SimpleSteps.ALL.take(3).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)
        try {
            vm.startFlow()
            vm.onLevelChosen(OptimizationMode.SIMPLE)
            vm.confirmLevelStart(OptimizationMode.SIMPLE)
            vm.simpleController.nextStep()
            val stepId = vm.state.value.simpleStep?.step?.id
            assertNotNull("шаг плана должен быть активен", stepId)

            SimpleStepBridge.onResult?.invoke(false, "state_unreadable")

            assertEquals(
                "литерал входа и токен раннера разошлись",
                "state_unreadable",
                SimpleRunner.REASON_STATE_UNREADABLE
            )
            assertEquals(
                "ведро «не подтверждено» заполнено",
                listOf(stepId),
                vm.simpleController.snapshot.unconfirmedStepIds
            )
            assertFalse(
                "неподтверждённый шаг не провал (состояние контроллера)",
                vm.simpleController.snapshot.failedStepIds.contains(stepId)
            )
            assertFalse(
                "неподтверждённый шаг не провал (журнал VM)",
                vm.getFailedStepIds().contains(stepId)
            )
        } finally {
            SimplePlan.reset()
        }
    }

    @Test
    fun `other failure reasons still land in the failed buckets`() = runTest {
        // Контрпример: тот же путь моста, другой токен. `revoke_not_confirmed` — приватная
        // константа раннера (SimpleRunner.REASON_REVOKE_NOT_CONFIRMED -> private), поэтому
        // здесь литерал, как в SimpleRunnerMsaRevokeTest.
        val steps = SimpleSteps.ALL.take(3).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)
        try {
            vm.startFlow()
            vm.onLevelChosen(OptimizationMode.SIMPLE)
            vm.confirmLevelStart(OptimizationMode.SIMPLE)
            vm.simpleController.nextStep()
            val stepId = vm.state.value.simpleStep?.step?.id
            assertNotNull("шаг плана должен быть активен", stepId)

            SimpleStepBridge.onResult?.invoke(false, "revoke_not_confirmed")

            assertTrue(
                "честный провал остаётся в ведре провалов",
                vm.simpleController.snapshot.failedStepIds.contains(stepId)
            )
            assertTrue("журнал VM видит провал", vm.getFailedStepIds().contains(stepId))
            assertFalse(
                "в ведро «не подтверждено» провал не попадает",
                vm.simpleController.snapshot.unconfirmedStepIds.contains(stepId)
            )
        } finally {
            SimplePlan.reset()
        }
    }
}