package com.xiaohypercleaner.service

import com.xiaohypercleaner.data.SimpleSteps

/** Отчёт одного шага отката: вердикт раннера + признак relaunch + исходы доп. целей. */
internal data class StepRunReport(
    val result: SimpleRunner.Result,
    val relaunchUsed: Boolean = false,
    val extras: List<ReverseExtraResult> = emptyList()
)

/**
 * Цикл отката Simple Mode: идёт по шагам из снапшота, для каждого запускает его маршрут
 * с целевым состоянием `checked_before` и решает, что делать при отсутствии значения.
 *
 * Значения нет — третий исход [ReverseOutcome.UNREADABLE]: шаг НЕ запускается, инверсия
 * `targetChecked` не подставляется, в UI уходит только `onStepDone` + `onProgress`
 * (заголовок невыполняемого шага показывать нечего), откат продолжается. Раньше здесь
 * подставлялась инверсия и шаг рапортовал успех — именно это делало дефект невидимым.
 *
 * Логика вынесена из [AdbEnablerService.reverseSimpleToggles] для теста на потребляющем
 * слое: тест подсовывает фейковый [runStep] и проверяет, с каким `targetChecked` шаг
 * ушёл в раннер (условие «инверсия не подставлена»), не завися от Accessibility-сервиса.
 */
internal class ReverseToggleRunner(
    private val findStep: (String) -> SimpleSteps.Step?,
    private val runStep: suspend (SimpleSteps.Step, Boolean) -> StepRunReport,
    private val log: (String) -> Unit
) {

    /**
     * @return true, если хотя бы один шаг вернул исходное состояние ([ReverseOutcome.APPLIED]
     * либо [ReverseOutcome.ALREADY_ORIGINAL]); unreadable и failed в положительный итог не входят.
     */
    suspend fun run(
        stepIds: Set<String>,
        checkedBeforeStates: Map<String, Boolean>,
        requireOverlay: Boolean,
        shouldStop: () -> Boolean,
        onStep: (index: Int, total: Int, title: String) -> Unit,
        onStepDone: (stepId: String, ok: Boolean) -> Unit,
        onStepResult: (ReverseStepResult) -> Unit,
        onProgress: (Float) -> Unit
    ): Boolean {
        if (stepIds.isEmpty()) return true
        var okCount = 0
        val list = stepIds.toList()
        list.forEachIndexed { i, id ->
            // Итог шага в UI: вердикт + прогресс. Шаг без значения снапшота до onStep
            // не доходит, поэтому счётчик и проценты считаются и в этом исходе.
            val finish = { r: ReverseStepResult ->
                onStepDone(r.stepId, r.ok)
                onStepResult(r)
                onProgress((i + 1).toFloat() / list.size)
            }
            if (shouldStop()) {
                // Отмена подтверждена: остаток не трогаем, иначе откат «догонит»
                // пользователя и вернёт то, что он остановил.
                log("reverseSimpleToggles: stopped by user at ${i + 1}/${list.size}")
                return@forEachIndexed
            }
            val original = findStep(id)
            if (original == null) {
                // Идентификатор без шага в плане: откатывать нечего — честно в итог.
                log("reverseSimpleToggles: step id not in plan, skipped: $id")
                finish(
                    ReverseStepResult(
                        stepId = id,
                        ok = false,
                        outcome = ReverseOutcome.UNREADABLE,
                        relaunchUsed = false,
                        reason = REASON_STEP_NOT_IN_PLAN
                    )
                )
                return@forEachIndexed
            }
            val checkedBefore = checkedBeforeStates[id]
            if (checkedBefore == null) {
                log("reverseSimpleToggles: $id unreadable (no checked_before) — step not started")
                finish(
                    ReverseStepResult(
                        stepId = id,
                        ok = false,
                        outcome = ReverseOutcome.UNREADABLE,
                        relaunchUsed = false,
                        reason = REASON_NO_CHECKED_BEFORE
                    )
                )
                return@forEachIndexed
            }
            val reverse = original.copy(targetChecked = checkedBefore)
            log(
                "reverseSimpleToggles: ${original.id} -> targetChecked=$checkedBefore " +
                    "source=checked_before overlay=$requireOverlay"
            )
            onStep(i + 1, list.size, original.titleRu)
            val report = runStep(reverse, requireOverlay)
            if (report.result.success) {
                okCount++
            } else {
                log("reverseSimpleToggles: ${original.id} not restored")
            }
            finish(
                ReverseStepResult(
                    stepId = original.id,
                    ok = report.result.success,
                    outcome = outcomeOf(report.result),
                    relaunchUsed = report.relaunchUsed,
                    extras = report.extras,
                    reason = report.result.reason
                )
            )
        }
        log("reverseSimpleToggles done: $okCount/${list.size}")
        return okCount > 0
    }

    private fun outcomeOf(result: SimpleRunner.Result): ReverseOutcome = when {
        result.reason?.startsWith(REASON_UNREADABLE_PREFIX) == true -> ReverseOutcome.UNREADABLE
        result.success && result.reason in ALREADY_REASONS -> ReverseOutcome.ALREADY_ORIGINAL
        result.success -> ReverseOutcome.APPLIED
        else -> ReverseOutcome.FAILED
    }

    internal companion object {
        /** Раннер отчитался, что состояние уже совпадало с целевым. */
        val ALREADY_REASONS = setOf("already_off", "already_done")

        /** Префикс вердикта раннера «возврат невозможен» (см. `SimpleRunner`). */
        const val REASON_UNREADABLE_PREFIX = "unreadable"

        const val REASON_NO_CHECKED_BEFORE = "no_checked_before"
        const val REASON_STEP_NOT_IN_PLAN = "step_not_in_plan"
    }
}