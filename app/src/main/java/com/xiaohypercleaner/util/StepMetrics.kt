package com.xiaohypercleaner.util

/**
 * Метрика одного шага Простого режима: тапы и ожидания.
 *
 * Наблюдательный инструмент: ничего не решает и не меняет поведение шага — только копит счётчики
 * для финальной DIAG-строки ([StepDiagnostics.stepResult]). Накопление идёт ВНУТРИ существующих
 * точек (итог [UiWait] внутри ожидания и точки инъекции тапов), поэтому цена строго нулевая:
 * ни новых ожиданий, ни новых чтений дерева не появляется.
 *
 * Формат полей (`taps=… waits=…`) задаётся здесь и больше нигде не повторяется — имена не
 * разъедутся с логом.
 *
 * Определения (без них счётчики неоднозначны):
 * - `taps` — инъекции по узлу или координатам: успешный `ACTION_CLICK` (узел и его кликабельный
 *   предок), координатный тап с повторным входом и короткий жест по центру узла. Свайпы,
 *   прокрутка и долгий тап сюда НЕ входят;
 * - `wait_ms` — сумма фактического времени ВСЕХ ожиданий шага (и удачных, и промахов);
 * - `wait_miss_ms` — сумма времени ТОЛЬКО промахов (`hit=false`);
 * - `wait_reads` — число чтений дерева ВНУТРИ ожиданий, не все чтения шага.
 *
 * Экземпляр живёт на [com.xiaohypercleaner.service.SimpleRunner] и сбрасывается в начале каждого
 * шага ([reset]): иначе числа второго шага включали бы первый.
 */
class StepMetrics {
    /** Снимок счётчиков шага на момент финальной DIAG-строки. */
    data class Snapshot(
        val taps: Int,
        val waits: Int,
        val waitMs: Long,
        val waitMissMs: Long,
        val waitReads: Int
    ) {
        /** Поля DIAG-строки: единственное место, где живут их имена. */
        fun formatFields(): String =
            "taps=$taps waits=$waits wait_ms=$waitMs wait_miss_ms=$waitMissMs wait_reads=$waitReads"
    }

    private var taps = 0
    private var waits = 0
    private var waitMs = 0L
    private var waitMissMs = 0L
    private var waitReads = 0

    /** Начало шага: счётчики предыдущего шага не наследуются. */
    fun reset() {
        taps = 0
        waits = 0
        waitMs = 0L
        waitMissMs = 0L
        waitReads = 0
    }

    /** Одна инъекция тапа (определения — в KDoc класса). */
    fun recordTap() {
        taps++
    }

    /** Итог одного ожидания: сколько ждали, сработало ли условие и сколько было чтений. */
    fun recordWait(elapsedMs: Long, hit: Boolean, reads: Int) {
        waits++
        waitMs += elapsedMs
        waitReads += reads
        if (!hit) waitMissMs += elapsedMs
    }

    fun snapshot(): Snapshot = Snapshot(taps, waits, waitMs, waitMissMs, waitReads)
}