package com.xiaohypercleaner.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0 (прогон rmuu7xcch): потеря разрешения «Поверх других окон» посреди прогона
 * останавливает прогон ОДНИМ итогом, а не превращается в каскад из 17 шагов
 * `overlay_not_attached`. Незапущенные шаги не выполняются, повторные сигналы
 * игнорируются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleModeControllerOverlayTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        SimplePlan.reset()
    }

    @After
    fun tearDown() {
        SimplePlan.reset()
    }

    @Test
    fun `overlay permission loss stops the run with a single result`() {
        val steps = SimpleSteps.ALL.take(5).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)

        val states = mutableListOf<SimpleModeController.SimpleModeState>()
        val controller = SimpleModeController(app, PermissionFlowManager(app)) { states.add(it) }

        controller.start()
        assertTrue("прогон активен", controller.isActive)

        controller.onOverlayPermissionLost()

        val last = states.last()
        assertEquals("прогон закрыт, а не переведён к следующему шагу", SimpleModePhase.DONE, last.phase)
        assertTrue("итог помечен частичным", last.partialRun)
        assertNotNull("итог опубликован", last.done)
        assertEquals("тумблеры шагов не исполнялись", 0, last.completedCount)

        // Повторный сигнал (гейт следующего шага) ничего не меняет: остановка идемпотентна.
        val before = states.size
        controller.onOverlayPermissionLost()
        assertEquals("повторный сигнал игнорируется", before, states.size)
    }

    @Test
    fun `overlay loss on an inactive controller changes nothing`() {
        val states = mutableListOf<SimpleModeController.SimpleModeState>()
        val controller = SimpleModeController(app, PermissionFlowManager(app)) { states.add(it) }

        controller.onOverlayPermissionLost()

        assertTrue("нет активного прогона — нет состояния", states.isEmpty())
    }
}