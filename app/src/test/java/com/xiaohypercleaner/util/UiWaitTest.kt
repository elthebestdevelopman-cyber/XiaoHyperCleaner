package com.xiaohypercleaner.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Единый механизм ожидания: ранний выход по условию, первое чтение до паузы,
 * подтверждение важных состояний двумя чтениями ПОДРЯД и жёсткий лимит.
 *
 * Тест ловит реальные регрессии ускорения Простого режима: фиксированные паузы
 * вместо раннего выхода, отсутствие подтверждающих чтений и ожидание сверх лимита.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiWaitTest {

    @Test
    fun `stops as soon as the condition holds instead of waiting the whole window`() = runTest {
        var reads = 0
        val outcome = UiWait.until(timeoutMs = 5_000L, pollMs = 200L) { ++reads >= 3 }

        assertTrue(outcome.hit)
        assertEquals("условие сработало на третьем чтении — дальше не ждём", 3, outcome.reads)
    }

    @Test
    fun `first read happens before any delay`() = runTest {
        var reads = 0
        val outcome = UiWait.until(timeoutMs = 5_000L, pollMs = 200L) { reads++ == 0 }

        assertTrue(outcome.hit)
        assertEquals("первое чтение идёт сразу", 1, outcome.reads)
    }

    // ═══ untilStable: ожидание СТАБИЛЬНОГО значения ═══

    @Test
    fun `stable value needs two identical readings in a row`() = runTest {
        var reads = 0
        val outcome = UiWait.untilStable(5_000L, 200L, 2) { reads++; "A" }

        assertTrue("два одинаковых значения подряд — стабильность", outcome.hit)
        assertEquals("A", outcome.value)
        assertEquals(2, outcome.reads)
    }

    @Test
    fun `null resets the streak`() = runTest {
        var reads = 0
        val samples = listOf("A", null, "A", "A")
        val outcome = UiWait.untilStable(5_000L, 200L, 2) { samples.getOrElse(reads++) { null } }

        assertTrue("null между подтверждениями сбрасывает серию", outcome.hit)
        assertEquals("A", outcome.value)
        assertEquals("подтверждение только на четвёртом чтении", 4, outcome.reads)
    }

    @Test
    fun `changing value resets the streak`() = runTest {
        var reads = 0
        val samples = listOf("A", "B", "B")
        val outcome = UiWait.untilStable(5_000L, 200L, 2) { samples.getOrElse(reads++) { null } }

        assertTrue("смена значения начинает новую серию", outcome.hit)
        assertEquals("B", outcome.value)
        assertEquals(3, outcome.reads)
    }

    @Test
    fun `timeout returns the last observed signal`() = runTest {
        var reads = 0
        val outcome = UiWait.untilStable(1_000L, 200L, 3) {
            reads++
            if (reads < 3) "A" else null
        }

        assertFalse(outcome.hit)
        assertEquals("последнее наблюдение — неизвестно", null, outcome.lastSignal)
        assertEquals("лимит 1000/200 + 1 чтение", 6, outcome.reads)
        assertEquals("фактически данное окно", 1_000L, outcome.limitMs)
    }

    @Test
    fun `cancellation is not a timeout`() = runTest {
        var reads = 0
        val outcome = UiWait.untilStable(5_000L, 200L, 2, isCancelled = { reads >= 1 }) {
            reads++
            "A"
        }

        assertFalse("отмена не даёт hit", outcome.hit)
        assertEquals(1, outcome.reads)
        assertEquals("A", outcome.lastSignal)
    }

    @Test
    fun `important state requires two confirming reads in a row`() = runTest {
        var reads = 0
        // Условие «мигает»: true, false, true, true — подтверждение только на двух подряд.
        val samples = listOf(true, false, true, true)
        val outcome = UiWait.until(
            timeoutMs = 5_000L,
            pollMs = 200L,
            confirmations = 2
        ) { samples.getOrElse(reads++) { false } }

        assertTrue("два чтения подряд подтверждают состояние", outcome.hit)
        assertEquals(4, outcome.reads)
    }

    @Test
    fun `hard limit stops the wait when the condition never holds`() = runTest {
        var reads = 0
        val outcome = UiWait.until(timeoutMs = 1_000L, pollMs = 200L) { reads++; false }

        assertFalse(outcome.hit)
        assertEquals("лимит 1000/200 + 1 чтение", 6, outcome.reads)
    }

    @Test
    fun `zero window reads once and gives up`() = runTest {
        var reads = 0
        val outcome = UiWait.until(timeoutMs = 0L, pollMs = 200L) { reads++; false }

        assertFalse(outcome.hit)
        assertEquals(1, outcome.reads)
    }

    @Test
    fun `cancellation stops the wait without success`() = runTest {
        var cancelled = false
        val outcome = UiWait.until(
            timeoutMs = 5_000L,
            pollMs = 200L,
            isCancelled = { cancelled }
        ) {
            cancelled = true
            false
        }

        assertFalse("отменённый шаг не «успешен»", outcome.hit)
        assertEquals(1, outcome.reads)
    }
}