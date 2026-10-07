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