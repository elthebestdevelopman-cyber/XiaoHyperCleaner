package com.xiaohypercleaner.ui

import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.SimpleModePhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `confirmLevelStart begins optimization`() = runTest {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.SIMPLE)

        vm.confirmLevelStart(OptimizationMode.SIMPLE)

        val state = vm.state.value
        assertFalse("showLevelConfirm should be closed", state.showLevelConfirm)
        assertTrue(
            "simpleModeActive should be true after SIMPLE start",
            state.simpleModeActive
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
}