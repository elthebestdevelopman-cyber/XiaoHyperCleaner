package com.xiaohypercleaner.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Цикл входа drill-шага в откате: «немедленный вход → пересчёт правила → гейтовые повторы».
 *
 * Прогон rmve63243: после первого немедленного входа цикл обрывался, шаг продолжал бурение с
 * нейтрального экрана и падал — `done 15/22`, `reverse_drill_from_neutral ok=false` ×5 у
 * music_sys, security_sys, downloads, mivideo, shareme (закрыто `d0482ac`).
 *
 * Тест гоняет [ReverseExtras.entryLoopDecision] в том же порядке, что и раннер, и проверяет
 * наблюдаемый итог: сколько было немедленных входов, сколько гейтовых повторов и чем цикл
 * закончился. Внутренние имена логов и структура раннера не проверяются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReverseEntryLoopTest {

    /** Что видно в окне на очередном обороте цикла. */
    private enum class Window { STEP, NEUTRAL, FOREIGN }

    /** Наблюдаемый итог цикла: входы, повторы, финальное действие и дошло ли до бурения. */
    private data class LoopOutcome(
        val immediateEntries: Int,
        val retries: Int,
        val action: ReverseEntryAction,
        val drilled: Boolean
    )

    /**
     * Цикл входа в том же порядке, что в раннере: решение спрашивается заново после каждого
     * действия, окно берётся из [windows] по номеру оборота (последний элемент держится, пока
     * цикл не сошёлся).
     */
    private fun runEntryLoop(
        windows: List<Window>,
        hasIntents: Boolean = true,
        maxAttempts: Int = 3,
        budgetAboveReserveMs: Long = 20_000L
    ): LoopOutcome {
        var windowIndex = 0
        var attempts = 0
        var immediateEntries = 0
        var immediateEntryDone = false
        var turns = 0
        while (true) {
            // Страховка от бесконечного цикла: оборотов не больше, чем действий правила.
            assertTrue("цикл входа не сошёлся за 20 оборотов", turns++ < 20)
            val window = windows[minOf(windowIndex, windows.size - 1)]
            val decision = ReverseExtras.entryLoopDecision(
                reverse = true,
                onStepScreen = { window == Window.STEP },
                foreignWindow = { window == Window.FOREIGN },
                hasIntents = hasIntents,
                attempts = attempts,
                maxAttempts = maxAttempts,
                immediateEntryDone = immediateEntryDone,
                budgetAboveReserveMs = budgetAboveReserveMs
            )
            when (decision.action) {
                ReverseEntryAction.CONTINUE_DRILL ->
                    return LoopOutcome(immediateEntries, attempts, decision.action, drilled = true)

                ReverseEntryAction.FAIL_SCREEN_NOT_REACHED ->
                    return LoopOutcome(immediateEntries, attempts, decision.action, drilled = false)

                ReverseEntryAction.DRILL_FROM_NEUTRAL -> {
                    immediateEntryDone = true
                    immediateEntries++
                    windowIndex++
                }

                ReverseEntryAction.RETRY_ENTRY -> {
                    attempts = decision.attempt
                    windowIndex++
                }
            }
        }
    }

    @Test
    fun `neutral window does not abort the step after the first entry`() {
        // Приложение ещё поднимается: окно нейтральное на двух оборотах подряд. Вход ровно один,
        // дальше гейтовый повтор — и только потом бурение. Обрыв после входа ронял шаг.
        val outcome = runEntryLoop(listOf(Window.NEUTRAL, Window.NEUTRAL, Window.STEP))
        assertEquals("немедленный вход ровно один", 1, outcome.immediateEntries)
        assertEquals("после входа шаг получил гейтовый повтор", 1, outcome.retries)
        assertEquals(ReverseEntryAction.CONTINUE_DRILL, outcome.action)
        assertTrue("шаг дошёл до бурения", outcome.drilled)
    }

    @Test
    fun `step continues after drilling from neutral`() {
        val outcome = runEntryLoop(listOf(Window.NEUTRAL, Window.STEP))
        assertEquals(1, outcome.immediateEntries)
        assertEquals("повторов не понадобилось", 0, outcome.retries)
        assertEquals(ReverseEntryAction.CONTINUE_DRILL, outcome.action)
        assertTrue("шаг дошёл до бурения", outcome.drilled)
    }

    @Test
    fun `attempt ceiling and budget reserve bound the retries`() {
        // Потолок: нейтральное окно не даёт бесконечного цикла — повторы кончаются потолком.
        val neutral = runEntryLoop(List(10) { Window.NEUTRAL }, maxAttempts = 3)
        assertEquals(1, neutral.immediateEntries)
        assertEquals("повторов ровно по потолку", 3, neutral.retries)
        assertEquals(
            "после потолка шаг бурит текущий экран",
            ReverseEntryAction.CONTINUE_DRILL,
            neutral.action
        )

        // Резерв: чужое окно с остатком ниже резерва не получает ни одного повтора.
        val foreignNoBudget = runEntryLoop(listOf(Window.FOREIGN), budgetAboveReserveMs = -1L)
        assertEquals(0, foreignNoBudget.retries)
        assertEquals(ReverseEntryAction.FAIL_SCREEN_NOT_REACHED, foreignNoBudget.action)

        // Чужое окно с живым бюджетом: повторы до потолка и честный провал.
        val foreignBudget = runEntryLoop(listOf(Window.FOREIGN), maxAttempts = 2)
        assertEquals(2, foreignBudget.retries)
        assertEquals(ReverseEntryAction.FAIL_SCREEN_NOT_REACHED, foreignBudget.action)
        assertFalse("чужое окно не бурится", foreignBudget.drilled)
    }

    @Test
    fun `step without intents gets no entry and no retries`() {
        val outcome = runEntryLoop(List(5) { Window.NEUTRAL }, hasIntents = false)
        assertEquals("входить нечем — входов нет", 0, outcome.immediateEntries)
        assertEquals("повторов нет", 0, outcome.retries)
        assertEquals(
            "шаг сразу бурит текущий экран",
            ReverseEntryAction.CONTINUE_DRILL,
            outcome.action
        )
        assertTrue(outcome.drilled)
    }

    @Test
    fun `foreign window fails honestly instead of drilling`() {
        val outcome = runEntryLoop(listOf(Window.FOREIGN), maxAttempts = 1)
        assertEquals("немедленного входа на чужом окне нет", 0, outcome.immediateEntries)
        assertEquals(1, outcome.retries)
        assertEquals(ReverseEntryAction.FAIL_SCREEN_NOT_REACHED, outcome.action)
        assertFalse("чужое окно не бурится", outcome.drilled)
    }
}