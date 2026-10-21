package com.xiaohypercleaner.data

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ведра отчёта прогона (D1b, прогон rmvgvhbzu): «не подтверждено» (`state_unreadable`) —
 * отдельное ведро, а не провал. Тест держит два контракта:
 *
 * 1. Шаг попадает ровно в одно ведро, сумма ведёр равна размеру плана, а счётчик прогресса
 *    (`completed`, `applicable`) сходится с блоками экрана результата.
 * 2. «Не подтверждено» не останавливает прогон — следующий шаг стартует как обычно.
 *
 * Шаги прокручиваются виртуальным временем: переход идёт через `AUTO_ADVANCE_DELAY_MS` + 150 мс,
 * реального ожидания в тесте нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleModeControllerReportTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        SimplePlan.reset()
    }

    @After
    fun tearDown() {
        SimplePlan.reset()
        Dispatchers.resetMain()
    }

    @Test
    fun `report buckets partition the plan and an unconfirmed step does not stop the run`() = runTest {
        // setMain — ДО конструктора контроллера: его scope захватывает Dispatchers.Main.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val steps = SimpleSteps.ALL.take(5).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)
        assertEquals("нужны пять разных шагов плана", 5, steps.map { it.id }.distinct().size)

        val controller = SimpleModeController(app, PermissionFlowManager(app), { true }, { true }) { }
        controller.start()
        // start() останавливается на разрешениях: в фазу шагов тест входит явно.
        controller.nextStep()

        // 1. toggled — обычный успех.
        controller.onStepResult(true, attempt = 1)
        advanceUntilIdle()

        // 2. already_off — тоже success=true, но пользовательское ведро отдельное.
        controller.noteAlreadyOff(steps[1].id)
        controller.onStepResult(true, attempt = 1)
        advanceUntilIdle()

        // 3. unconfirmed — порядок как в MainViewModel: сначала ведро, потом вердикт.
        controller.noteUnconfirmed(steps[2].id)
        controller.onStepResult(false, attempt = 1, finalFailure = true)
        advanceUntilIdle()

        assertEquals(
            "после «не подтверждено» прогон продолжается следующим шагом",
            3,
            controller.snapshot.currentStepIndex
        )
        assertTrue("прогон не прерван", controller.isActive)

        // 4. failed.
        controller.onStepResult(false, attempt = 1, finalFailure = true)
        advanceUntilIdle()

        // 5. skipped.
        controller.onStepSkipped(steps[4].id)
        advanceUntilIdle()

        val s = controller.snapshot
        assertEquals("прогон дошёл до DONE", SimpleModePhase.DONE, s.phase)
        assertEquals("успехов два: toggled + already_off", 2, s.completedCount)
        assertEquals(listOf(steps[1].id), s.alreadyOffStepIds)
        assertEquals("не подтверждено — своё ведро", listOf(steps[2].id), s.unconfirmedStepIds)
        assertEquals("провал — только четвёртый шаг", listOf(steps[3].id), s.failedStepIds)
        assertEquals(listOf(steps[4].id), s.skippedStepIds)

        val toggled = s.completedCount - s.alreadyOffStepIds.size
        assertEquals(
            "toggled + already_off + unconfirmed + failed + skipped == размер плана",
            steps.size,
            toggled + s.alreadyOffStepIds.size + s.unconfirmedStepIds.size +
                s.failedStepIds.size + s.skippedStepIds.size
        )
        // Числитель прогресса неподтверждённый шаг не включает (success=false), знаменатель —
        // включает: блоки экрана результата дают ровно знаменатель.
        assertEquals("итог (выполнено, применимо)", Pair(2, 4), s.done)
        assertEquals(
            "completed + failed + unconfirmed == знаменатель",
            4,
            s.completedCount + s.failedStepIds.size + s.unconfirmedStepIds.size
        )
    }

    @Test
    fun `unconfirmed before the final failure never enters the failed bucket`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val steps = SimpleSteps.ALL.take(2).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)
        val controller = SimpleModeController(app, PermissionFlowManager(app), { true }, { true }) { }
        controller.start()
        controller.nextStep()

        // Штатный порядок MainViewModel: ведро заполняется до вердикта.
        controller.noteUnconfirmed(steps[0].id)
        controller.onStepResult(false, attempt = 1, finalFailure = true)

        assertEquals(listOf(steps[0].id), controller.unconfirmedStepIds)
        assertFalse(
            "неподтверждённый шаг не попадает в ведро провалов",
            controller.failedStepIds.contains(steps[0].id)
        )
        advanceUntilIdle()
    }

    @Test
    fun `unconfirmed after the final failure is removed from the failed bucket`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val steps = SimpleSteps.ALL.take(2).map { PlanBuilder.PlanStep(it, null) }
        SimplePlan.set(steps)
        val controller = SimpleModeController(app, PermissionFlowManager(app), { true }, { true }) { }
        controller.start()
        controller.nextStep()

        // Обратный порядок: вердикт-провал приходит раньше «не подтверждено».
        controller.onStepResult(false, attempt = 1, finalFailure = true)
        assertEquals(
            "до noteUnconfirmed шаг числится провалом",
            listOf(steps[0].id),
            controller.failedStepIds
        )

        controller.noteUnconfirmed(steps[0].id)

        assertEquals(listOf(steps[0].id), controller.unconfirmedStepIds)
        assertFalse(
            "инвариант «ровно одно ведро» не зависит от порядка вызовов",
            controller.failedStepIds.contains(steps[0].id)
        )
        advanceUntilIdle()
    }
}