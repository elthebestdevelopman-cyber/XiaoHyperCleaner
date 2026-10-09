package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Шкала фазы «проценты · таймер».
 *
 * Владелец просил в фазе отката ту же шкалу, что и в основном прогоне; формулы живут
 * отдельно от окна, поэтому проверяются без сервиса. Регрессия здесь = «шкала стоит»
 * или «таймер врёт» — то, на что смотрит пользователь во время отката.
 */
class ProgressFormatTest {

    @Test
    fun `percent is bounded by step over total`() {
        assertEquals(0, ProgressFormat.percent(0, 16))
        assertEquals("3/16 — целочисленное деление вниз", 18, ProgressFormat.percent(3, 16))
        assertEquals(100, ProgressFormat.percent(16, 16))
        assertEquals("нулевой total не делит на ноль", 0, ProgressFormat.percent(5, 0))
        assertEquals("шаг сверх плана не даёт больше 100", 100, ProgressFormat.percent(20, 16))
    }

    @Test
    fun `elapsed is minutes and seconds`() {
        assertEquals("0:00", ProgressFormat.elapsed(0))
        assertEquals("0:42", ProgressFormat.elapsed(42_000))
        assertEquals("12:05", ProgressFormat.elapsed(725_000))
        assertEquals("отрицательное время не ломает формат", "0:00", ProgressFormat.elapsed(-5_000))
    }
}