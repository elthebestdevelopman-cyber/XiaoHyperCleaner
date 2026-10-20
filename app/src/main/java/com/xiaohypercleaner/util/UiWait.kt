package com.xiaohypercleaner.util

import kotlinx.coroutines.delay

/**
 * Единый механизм ожидания изменений UI Простого режима.
 *
 * Одна политика для всех ожиданий: первое чтение ДО первой паузы, опрос
 * 150..250 мс, ранний выход по условию и подтверждение важных состояний
 * несколькими чтениями ПОДРЯД ([confirmations]). Жёсткий лимит задаёт
 * вызывающий (обычно — запрошенное время, усечённое остатком бюджета шага),
 * поэтому механизм не увеличивает бюджеты шагов.
 *
 * Лимит соблюдается двумя независимыми способами: по числу чтений (детерминирован
 * в unit-тестах с виртуальным временем) и по настенным часам (медленное чтение
 * дерева не растягивает ожидание сверх запрошенного).
 */
object UiWait {
    /** Штатный интервал опроса. */
    const val POLL_MS = 200L

    /** Быстрые состояния: смена экрана сразу после тапа. */
    const val POLL_FAST_MS = 150L

    /** Медленные состояния: отсчёт/прогресс прошивки. */
    const val POLL_SLOW_MS = 250L

    /** Итог ожидания: сработало ли условие, сколько ждали и сколько раз читали. */
    data class Outcome(val hit: Boolean, val elapsedMs: Long, val reads: Int)

    /**
     * Итог ожидания СТАБИЛЬНОГО значения: то же, что [Outcome], плюс само значение и последнее
     * наблюдение. Механизм общий — смысл значения определяет вызывающий, [UiWait] его не
     * интерпретирует.
     *
     * @param value стабильное значение, подтверждённое `confirmations` чтениями подряд; null —
     *   окно истекло, ожидание отменено или значение так и не стало стабильным
     * @param lastSignal последнее сырое наблюдение (включая null): вызывающему нужно отличать
     *   «значение не стабилизировалось» от «последнее чтение было неизвестным»
     * @param limitMs фактически данное окно (запрошенное, урезанное вызывающим по бюджету)
     */
    data class StableOutcome<T>(
        val value: T?,
        val hit: Boolean,
        val elapsedMs: Long,
        val reads: Int,
        val lastSignal: T?,
        val limitMs: Long
    )

    /**
     * Ждёт, пока одно и то же НЕНУЛЕВОЕ значение придёт [confirmations] чтений подряд.
     *
     * Серия сбрасывается на `null` (состояние неизвестно) и на смене значения: «мигнувшее»
     * чтение подтверждением не считается. Политика лимита та же, что у [until]: первое чтение до
     * первой паузы, жёсткий потолок и по числу чтений, и по настенным часам.
     */
    suspend fun <T : Any> untilStable(
        timeoutMs: Long,
        pollMs: Long = POLL_MS,
        confirmations: Int = 1,
        isCancelled: () -> Boolean = { false },
        signal: suspend () -> T?
    ): StableOutcome<T> {
        val started = System.currentTimeMillis()
        val limit = timeoutMs.coerceAtLeast(0L)
        val step = pollMs.coerceAtLeast(1L)
        val needed = confirmations.coerceAtLeast(1)
        val maxReads = (limit / step).toInt() + 1
        var stable: T? = null
        var streak = 0
        var reads = 0
        var last: T? = null
        while (true) {
            if (isCancelled()) {
                return StableOutcome(
                    value = null,
                    hit = false,
                    elapsedMs = System.currentTimeMillis() - started,
                    reads = reads,
                    lastSignal = last,
                    limitMs = limit
                )
            }
            reads++
            val value = signal()
            last = value
            when {
                value == null -> {
                    stable = null
                    streak = 0
                }
                value == stable -> streak++
                else -> {
                    stable = value
                    streak = 1
                }
            }
            if (stable != null && streak >= needed) {
                return StableOutcome(
                    value = stable,
                    hit = true,
                    elapsedMs = System.currentTimeMillis() - started,
                    reads = reads,
                    lastSignal = last,
                    limitMs = limit
                )
            }
            val wall = System.currentTimeMillis() - started
            if (reads >= maxReads || wall >= limit) {
                return StableOutcome(
                    value = null,
                    hit = false,
                    elapsedMs = wall,
                    reads = reads,
                    lastSignal = last,
                    limitMs = limit
                )
            }
            delay(step)
        }
    }

    /**
     * Ждёт выполнения [condition] не дольше [timeoutMs].
     *
     * @param confirmations сколько чтений подряд должно подтвердить условие;
     *   счётчик сбрасывается на первом же `false` — «мигнувшее» состояние
     *   (диалог перекрыл экран) подтверждением не считается.
     * @param pollMs интервал между чтениями; первое чтение идёт сразу.
     * @param isCancelled отмена (шаг прерван) — выходим немедленно, без успеха.
     */
    suspend fun until(
        timeoutMs: Long,
        pollMs: Long = POLL_MS,
        confirmations: Int = 1,
        isCancelled: () -> Boolean = { false },
        condition: suspend () -> Boolean
    ): Outcome {
        val started = System.currentTimeMillis()
        val limit = timeoutMs.coerceAtLeast(0L)
        val step = pollMs.coerceAtLeast(1L)
        val needed = confirmations.coerceAtLeast(1)
        val maxReads = (limit / step).toInt() + 1
        var streak = 0
        var reads = 0
        while (true) {
            if (isCancelled()) return Outcome(false, System.currentTimeMillis() - started, reads)
            reads++
            if (condition()) {
                streak++
                if (streak >= needed) {
                    return Outcome(true, System.currentTimeMillis() - started, reads)
                }
            } else {
                streak = 0
            }
            val wall = System.currentTimeMillis() - started
            if (reads >= maxReads || wall >= limit) return Outcome(false, wall, reads)
            delay(step)
        }
    }
}