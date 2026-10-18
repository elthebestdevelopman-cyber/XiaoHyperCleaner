package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Метрика шага: агрегация ожиданий и тапов.
 *
 * Спец-кейс из ТЗ круга 18.10: `hit=true 300 мс + hit=false 700 мс` → `wait_ms=1000`,
 * `wait_miss_ms=700` (в промах идёт ТОЛЬКО неудачное ожидание). Второй тест — граница
 * «метрики не текут между шагами»: числа второго шага не включают первый.
 */
class StepMetricsTest {

    @Test
    fun `miss time counts only the waits that never hit`() {
        val metrics = StepMetrics()

        metrics.recordWait(elapsedMs = 300, hit = true, reads = 2)
        metrics.recordWait(elapsedMs = 700, hit = false, reads = 5)

        val snapshot = metrics.snapshot()
        assertEquals("оба ожидания в сумме", 1000L, snapshot.waitMs)
        assertEquals("в промах идёт только hit=false", 700L, snapshot.waitMissMs)
        assertEquals(2, snapshot.waits)
        assertEquals(7, snapshot.waitReads)
        val fields = snapshot.formatFields()
            .split(' ')
            .filter { it.startsWith("wait_ms=") || it.startsWith("wait_miss_ms=") || it.startsWith("wait_reads=") }
            .joinToString(" ")
        assertEquals("wait_ms=1000 wait_miss_ms=700 wait_reads=7", fields)
    }

    @Test
    fun `metrics do not leak from one step into the next`() {
        val metrics = StepMetrics()

        // Шаг 1: промах на 400 мс и один тап.
        metrics.recordWait(elapsedMs = 400, hit = false, reads = 3)
        metrics.recordTap()
        assertEquals(400L, metrics.snapshot().waitMissMs)

        // Шаг 2 начинается с нуля: счётчики первого не наследуются.
        metrics.reset()
        metrics.recordWait(elapsedMs = 250, hit = true, reads = 1)
        metrics.recordTap()

        val second = metrics.snapshot()
        assertEquals("промах первого шага не наследуется", 0L, second.waitMissMs)
        assertEquals("время ожиданий только второго шага", 250L, second.waitMs)
        assertEquals("ожиданий тоже одно", 1, second.waits)
        assertEquals("тапов тоже один", 1, second.taps)
        assertEquals(1, second.waitReads)
    }

    @Test
    fun `reset clears every counter`() {
        val metrics = StepMetrics()
        metrics.recordWait(elapsedMs = 900, hit = false, reads = 9)
        metrics.recordTap()

        metrics.reset()

        assertEquals(StepMetrics.Snapshot(0, 0, 0L, 0L, 0), metrics.snapshot())
    }

    @Test
    fun `hit waits never add to miss time but always to total`() {
        val metrics = StepMetrics()

        metrics.recordWait(elapsedMs = 1_200, hit = true, reads = 4)

        val snapshot = metrics.snapshot()
        assertEquals(1_200L, snapshot.waitMs)
        assertEquals(0L, snapshot.waitMissMs)
        assertEquals(1, snapshot.waits)
    }
}