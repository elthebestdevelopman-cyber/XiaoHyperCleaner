package com.xiaohypercleaner.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.AppDependencies
import com.xiaohypercleaner.XiaoHyperApp
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
}