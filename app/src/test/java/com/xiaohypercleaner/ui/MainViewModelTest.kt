package com.xiaohypercleaner.ui

import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.SimpleModePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MainViewModelTest {

    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        vm = MainViewModel(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `initial state is clean`() {
        val state = vm.state.value
        assertFalse(state.isWorking)
        assertFalse(state.isOptimized)
        assertEquals(SimpleModePhase.INACTIVE, state.simpleModePhase)
    }

    @Test
    fun `startFlow shows level dialog`() {
        vm.startFlow()
        assertEquals(true, vm.state.value.showLevelDialog)
    }

    @Test
    fun `startFlow is ignored when already working`() {
        vm.startFlow()
        vm.startFlow()
        assertEquals(true, vm.state.value.showLevelDialog)
    }

    @Test
    fun `cancelLevelConfirm resets dialog state`() {
        vm.startFlow()
        vm.onLevelChosen(OptimizationMode.SIMPLE)
        vm.cancelLevelConfirm()
        assertFalse(vm.state.value.showLevelConfirm)
    }
}