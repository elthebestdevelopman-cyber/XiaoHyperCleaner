package com.xiaohypercleaner.util

import java.util.Locale

/**
 * Строка прогресса фазы: проценты и таймер («19% · 00:42»).
 *
 * Вынесено из `OverlayService`, чтобы формулы были проверяемы без окна: в фазе отката
 * шкала та же, что и в основном прогоне (запрос владельца — проценты и таймер).
 */
internal object ProgressFormat {

    /** Процент выполнения 0..100; шаг сверх плана и нулевой total не ломают расчёт. */
    fun percent(step: Int, total: Int): Int {
        if (total <= 0) return 0
        return (step * 100 / total).coerceIn(0, 100)
    }

    /** «м:сс» от старта фазы («0:42», «12:05»); отрицательное время читается как ноль. */
    fun elapsed(elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0L) / 1000
        // Цифры одинаковы во всех локалях, поэтому формат явный (иначе lint прав
        // про DefaultLocale).
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}