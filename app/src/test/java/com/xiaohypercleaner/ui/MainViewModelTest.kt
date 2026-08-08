package com.xiaohypercleaner.ui

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.AdbExecutor
import com.xiaohypercleaner.data.AppDependencies
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Unit-тесты MainViewModel на Robolectric: реальный Android Context,
 * но без устройства. Подменяем AdbExecutor фейком, PreferencesManager
 * работает на реальном DataStore в tmp-директории Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var app: XiaoHyperApp
    private lateinit var fakeAdb: FakeAdbExecutor

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        fakeAdb = FakeAdbExecutor()
        // Подменяем движок через рефлексию в AppDependencies:
        // в реальном коде используется newEngine() → делаем override для теста
        app.deps = TestDependencies(app, fakeAdb)
    }

    @Test
    fun initialState_isNotOptimizedAndNotWorking() = runTest {
        val vm = MainViewModel(app)
        val state = vm.state.first()
        assertFalse(state.isOptimized)
        assertFalse(state.isWorking)
        assertFalse(state.showAccessibilityDialog)
        assertFalse(state.showOverlayDialog)
    }

    @Test
    fun dialogAgreed_clearsBothDialogFlags() = runTest {
        val vm = MainViewModel(app)
        // искусственно поднимаем флаги
        vm.showAccessibilityDialog()
        vm.showOverlayDialog()
        vm.dialogAgreed()
        advanceUntilIdle()
        val state = vm.state.first()
        assertFalse(state.showAccessibilityDialog)
        assertFalse(state.showOverlayDialog)
    }

    @Test
    fun dialogCancelled_resetsFlowActiveAndDialogs() = runTest {
        val vm = MainViewModel(app)
        vm.showAccessibilityDialog()
        vm.dialogCancelled()
        advanceUntilIdle()
        val state = vm.state.first()
        assertFalse(state.showAccessibilityDialog)
        assertFalse(state.showOverlayDialog)
    }

    @Test
    fun restoreOptimization_setsIsOptimizedFalseOnSuccess() = runTest {
        val vm = MainViewModel(app)
        // сначала «оптимизировали»
        app.preferencesManager.setHiddenSettingsApplied(true)
        advanceUntilIdle()
        assertTrue(vm.state.first().isOptimized)

        fakeAdb.connectResult = true
        vm.restoreOptimization()
        advanceUntilIdle()

        val state = vm.state.first()
        assertFalse(state.isOptimized)
        assertFalse(state.isWorking)
        assertFalse(app.preferencesManager.isHiddenSettingsApplied.first())
    }

    @Test
    fun restoreOptimization_setsRestoreFailedWhenAdbFails() = runTest {
        val vm = MainViewModel(app)
        app.preferencesManager.setHiddenSettingsApplied(true)
        advanceUntilIdle()

        fakeAdb.connectResult = false
        vm.restoreOptimization()
        advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.restoreFailed)
        assertFalse(state.isWorking)
    }

    @Test
    fun dismissRestoreFailed_clearsFlag() = runTest {
        val vm = MainViewModel(app)
        fakeAdb.connectResult = false
        vm.restoreOptimization()
        advanceUntilIdle()
        assertTrue(vm.state.first().restoreFailed)

        vm.dismissRestoreFailed()
        assertFalse(vm.state.first().restoreFailed)
    }

    @Test
    fun requestReboot_showsDialogThenCallsReboot() = runTest {
        val vm = MainViewModel(app)
        vm.requestReboot()
        assertTrue(vm.state.first().showRebootDialog)

        fakeAdb.connectResult = true
        vm.confirmReboot()
        advanceUntilIdle()

        val state = vm.state.first()
        assertFalse(state.showRebootDialog)
        assertFalse(state.isWorking)
        assertTrue(fakeAdb.commands.contains("reboot"))
    }

    @Test
    fun confirmReboot_whenAdbFails_setsRebootFailed() = runTest {
        val vm = MainViewModel(app)
        vm.requestReboot()
        fakeAdb.connectResult = false
        vm.confirmReboot()
        advanceUntilIdle()

        val state = vm.state.first()
        assertTrue(state.rebootFailed)
        assertFalse(state.isWorking)
    }

    @Test
    fun refreshStatuses_readsOverlayPermissionFromSettings() = runTest {
        val vm = MainViewModel(app)
        vm.refreshStatuses()
        advanceUntilIdle()
        // в тестовом окружении canDrawOverlays всегда false (нет M+ runtime-grant),
        // проверяем что поле обновилось и не упало
        val state = vm.state.first()
        assertFalse(state.isOverlayGranted)
    }
}

/**
 * Фейк AdbExecutor — возвращает то, что задали в тестах.
 * Все команды складываются в commands для ассертов.
 */
private class FakeAdbExecutor : AdbExecutor {
    var connectResult: Boolean = true
    val commands = mutableListOf<String>()
    var executeResult: String = "Success"

    override suspend fun connect(): Boolean = connectResult
    override suspend fun executeCommand(command: String): String {
        commands.add(command)
        return executeResult
    }

    override fun disconnect() {}
}

/**
 * Тестовый AppDependencies, возвращающий OptimizationEngine с FakeAdbExecutor.
 * PreferencesManager остаётся реальным (Robolectric предоставляет Context).
 */
private class TestDependencies(
    context: Context,
    private val fake: FakeAdbExecutor
) : AppDependencies(context) {
    override suspend fun newEngine(): OptimizationEngine = OptimizationEngine(fake)
}