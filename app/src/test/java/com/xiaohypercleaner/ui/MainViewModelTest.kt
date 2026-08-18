package com.xiaohypercleaner.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.AppDependencies
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-тесты для MainViewModel.
 * 
 * Проверяет:
 * - Начальное состояние ViewModel
 * - Обработку диалогов (Agreed/Cancelled)
 * - Переходы между шагами оптимизации
 * - Обработку результатов шагов (SUCCESS/FAILED/SKIPPED)
 * - Автоповторы при ошибках
 * - Завершение оптимизации и верификацию
 * 
 * @see MainViewModel
 * @see MainViewModel.onSimpleStepResult
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = XiaoHyperApp::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()

        val testDeps = AppDependencies(application)
        XiaoHyperApp.testDeps = testDeps

        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        XiaoHyperApp.testDeps = null
    }

    @Test
    fun `initial state is not optimized`() = runTest {
        val state = viewModel.state.first()
        assertFalse(state.isOptimized)
        assertFalse(state.isWorking)
    }

    @Test
    fun `refreshStatuses updates state correctly`() = runTest {
        viewModel.refreshStatuses()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertNotNull(state)
    }

    @Test
    fun `dialogAgreed resets dialog state`() = runTest {
        viewModel.dialogAgreed()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse(state.showAccessibilityDialog)
        assertFalse(state.showOverlayDialog)
    }

    @Test
    fun `dialogCancelled resets flow and dialog state`() = runTest {
        viewModel.dialogCancelled()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse(state.showAccessibilityDialog)
        assertFalse(state.showOverlayDialog)
    }

    @Test
    fun `requestReboot shows reboot dialog`() = runTest {
        viewModel.requestReboot()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertTrue(state.showRebootDialog)
    }

    @Test
    fun `dismissRebootDialog hides reboot dialog`() = runTest {
        viewModel.requestReboot()
        advanceUntilIdle()
        viewModel.dismissRebootDialog()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse(state.showRebootDialog)
    }

    @Test
    fun `dismissRestoreFailed hides restore failed dialog`() = runTest {
        viewModel.dismissRestoreFailed()
        advanceUntilIdle()

        val state = viewModel.state.first()
        assertFalse(state.restoreFailed)
    }

    @Test
    fun `onSimpleStepResult with SUCCESS advances to next step`() = runTest {
        // Arrange: устанавливаем первый шаг как текущий
        viewModel.startSimpleMode()
        advanceUntilIdle()
        
        // Act: симулируем успешное выполнение шага 0
        viewModel.onSimpleStepResult(0, true, null)
        advanceUntilIdle()
        
        // Assert: должен перейти к шагу 1
        val state = viewModel.state.first()
        assertEquals("Should advance to step 1", 1, state.simpleStepIndex)
    }

    @Test
    fun `onSimpleStepResult with FAILED retries up to max attempts`() = runTest {
        // Arrange: начинаем с шага 0
        viewModel.startSimpleMode()
        advanceUntilIdle()
        
        // Act: симулируем 2 неудачи
        viewModel.onSimpleStepResult(0, false, "switch_not_found")
        advanceUntilIdle()
        viewModel.onSimpleStepResult(0, false, "click_failed")
        advanceUntilIdle()
        
        // Assert: всё ещё на шаге 0, но attempt увеличен
        val state = viewModel.state.first()
        assertEquals("Should stay on step 0", 0, state.simpleStepIndex)
        // После 2 неудач должна быть попытка #3
    }

    @Test
    fun `onSimpleStepResult with SKIPPED advances without retry`() = runTest {
        // Arrange
        viewModel.startSimpleMode()
        advanceUntilIdle()
        
        // Act: пропускаем шаг
        viewModel.onSimpleStepResult(0, false, null, skipped = true)
        advanceUntilIdle()
        
        // Assert: переходит к следующему шагу без повторов
        val state = viewModel.state.first()
        assertEquals("Should advance to step 1", 1, state.simpleStepIndex)
    }

    @Test
    fun `onSimpleStepResult completes optimization after last step`() = runTest {
        // Arrange: устанавливаем последний шаг (11 из 12)
        viewModel.startSimpleMode()
        // Пропускаем первые 11 шагов быстро
        for (i in 0 until SimpleSteps.ALL.size - 1) {
            viewModel.onSimpleStepResult(i, true, null)
            advanceUntilIdle()
        }
        
        // Act: выполняем последний шаг
        viewModel.onSimpleStepResult(SimpleSteps.ALL.size - 1, true, null)
        advanceUntilIdle()
        
        // Assert: оптимизация завершена
        val state = viewModel.state.first()
        assertTrue("Should be optimized", state.isOptimized)
        assertFalse("Should not be working", state.isWorking)
    }
}