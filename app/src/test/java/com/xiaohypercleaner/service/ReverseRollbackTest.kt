package com.xiaohypercleaner.service

import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Откат Simple Mode: честный третий исход и возврат значений из снапшота.
 *
 * Прогон rmv2kgdf3: откат `ux_program#extra:…` не нашёл значения в снапшоте, подставил
 * инверсию `targetChecked` и отчитался `success` — дефект был невидим. Тест падает именно
 * на подмене значения: фейковый раннер НЕ должен получить `!original.targetChecked`, а
 * шаг без значения снапшота не должен запускаться вовсе.
 *
 * Тест идёт через потребляющий слой — цикл, которым пользуется
 * [AdbEnablerService.reverseSimpleToggles], а не через разбор ключа.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReverseRollbackTest {

    private fun step(id: String, targetChecked: Boolean) = SimpleSteps.Step(
        id = id,
        titleRu = "Шаг $id",
        titleEn = "Step $id",
        descRu = "",
        descEn = "",
        intents = emptyList(),
        searchTexts = emptyList(),
        targetChecked = targetChecked,
        manualHintRu = "",
        manualHintEn = ""
    )

    /** Прогон отката с записью того, что ушло в раннер и что вернулось потребителю. */
    private class Harness(
        private val steps: List<SimpleSteps.Step>,
        private val report: (SimpleSteps.Step) -> StepRunReport
    ) {
        val runCalls = mutableListOf<SimpleSteps.Step>()
        val titles = mutableListOf<String>()
        val results = mutableListOf<ReverseStepResult>()
        val done = mutableListOf<Pair<String, Boolean>>()
        val progress = mutableListOf<Float>()

        private val runner = ReverseToggleRunner(
            findStep = { id -> steps.firstOrNull { it.id == id } },
            runStep = { s, overlay ->
                if (!overlay) throw AssertionError("откат обязан идти в окне прогресса")
                runCalls += s
                report(s)
            },
            log = {}
        )

        fun run(snapshot: Map<String, Boolean>): Boolean = runBlocking {
            runner.run(
                stepIds = LinkedHashSet(steps.map { it.id }),
                checkedBeforeStates = snapshot,
                requireOverlay = true,
                shouldStop = { false },
                onStep = { _, _, title -> titles += title },
                onStepDone = { id, ok -> done += id to ok },
                onStepResult = { results += it },
                onProgress = { progress += it }
            )
        }
    }

    @Test
    fun `step without checked_before is unreadable and inversion is never substituted`() {
        val h = Harness(listOf(step("ux_program", targetChecked = false))) {
            StepRunReport(SimpleRunner.Result(true, "toggled"))
        }

        val ok = h.run(emptyMap())

        assertFalse("шаг без значения снапшота не считается восстановленным", ok)
        assertTrue("раннер не должен получить подставленную инверсию", h.runCalls.isEmpty())
        assertTrue("заголовок невыполняемого шага в UI не отправляется", h.titles.isEmpty())
        assertEquals(listOf("ux_program" to false), h.done)
        assertEquals(listOf(ReverseOutcome.UNREADABLE), h.results.map { it.outcome })
        assertEquals("no_checked_before", h.results[0].reason)
        assertEquals("прогресс обязан двигаться и на этом исходе", listOf(1f), h.progress)
    }

    @Test
    fun `unreadable step does not stop the rollback`() {
        val h = Harness(listOf(step("a", targetChecked = false), step("b", targetChecked = false))) {
            StepRunReport(SimpleRunner.Result(true, "toggled"))
        }

        val ok = h.run(mapOf("b" to true))

        assertTrue(ok)
        assertEquals(listOf("b"), h.runCalls.map { it.id })
        assertEquals(
            listOf(ReverseOutcome.UNREADABLE, ReverseOutcome.APPLIED),
            h.results.map { it.outcome }
        )
        assertEquals(listOf(0.5f, 1f), h.progress)
    }

    @Test
    fun `checked_before goes to the runner as the target state`() {
        val h = Harness(listOf(step("carousel", targetChecked = false))) {
            StepRunReport(SimpleRunner.Result(true, "toggled"))
        }

        h.run(mapOf("carousel" to true))

        assertEquals(1, h.runCalls.size)
        assertTrue("возврат идёт к значению снапшота, а не к инверсии target", h.runCalls[0].targetChecked)
        assertEquals(listOf("Шаг carousel"), h.titles)
        assertEquals(ReverseOutcome.APPLIED, h.results[0].outcome)
        assertTrue(h.done[0].second)
    }

    @Test
    fun `step already in original state is already_original`() {
        val h = Harness(listOf(step("themes", targetChecked = false))) {
            StepRunReport(SimpleRunner.Result(true, "already_off"))
        }

        val ok = h.run(mapOf("themes" to false))

        assertTrue("уже исходное состояние — восстановлено", ok)
        assertEquals(ReverseOutcome.ALREADY_ORIGINAL, h.results[0].outcome)
        assertTrue(h.results[0].ok)
    }

    @Test
    fun `failed step is not reported as restored`() {
        val h = Harness(listOf(step("shareme", targetChecked = false))) {
            StepRunReport(SimpleRunner.Result(false, "verify_failed"))
        }

        val ok = h.run(mapOf("shareme" to true))

        assertFalse(ok)
        assertEquals(ReverseOutcome.FAILED, h.results[0].outcome)
        assertFalse(h.results[0].ok)
    }

    @Test
    fun `relaunch flag and extra outcomes reach the report`() {
        val h = Harness(listOf(step("browser_sys", targetChecked = false))) {
            StepRunReport(
                result = SimpleRunner.Result(true, "toggled"),
                relaunchUsed = true,
                extras = listOf(
                    ReverseExtraResult("Персонализация услуг", ReverseOutcome.APPLIED),
                    ReverseExtraResult(
                        "Персонализация контент-услуг",
                        ReverseOutcome.UNREADABLE,
                        "row_absent"
                    )
                )
            )
        }

        h.run(mapOf("browser_sys" to true))

        assertTrue(h.results[0].relaunchUsed)
        assertEquals(2, h.results[0].extras.size)
        assertEquals(ReverseOutcome.UNREADABLE, h.results[0].extras[1].outcome)
    }

    @Test
    fun `extra row is returned to the snapshot value`() {
        assertEquals(
            ExtraRestoreDecision.Toggle(false, ReverseExtras.REASON_TOGGLED),
            ReverseExtras.decide(rowFound = true, checkedNow = true, snapshotValue = false)
        )
    }

    @Test
    fun `extra row already in snapshot state needs no tap`() {
        assertEquals(
            ExtraRestoreDecision.AlreadyOriginal(ReverseExtras.REASON_ALREADY_ORIGINAL),
            ReverseExtras.decide(rowFound = true, checkedNow = true, snapshotValue = true)
        )
    }

    @Test
    fun `row missing from snapshot is left alone`() {
        assertEquals(
            "строку мы не тумбили — возвращать нечего",
            ExtraRestoreDecision.AlreadyOriginal(ReverseExtras.REASON_NOT_TOGGLED_BY_US),
            ReverseExtras.decide(rowFound = true, checkedNow = false, snapshotValue = null)
        )
    }

    @Test
    fun `absent row with snapshot entry is unreadable even with main switch off`() {
        assertEquals(
            "цель с beforeMain при выключенном главном тумблере — не «выполнена»",
            ExtraRestoreDecision.Unreadable(ReverseExtras.REASON_ROW_ABSENT),
            ReverseExtras.decide(rowFound = false, checkedNow = null, snapshotValue = true)
        )
    }

    @Test
    fun `reverse order flips the group but keeps the phases`() {
        val targets = listOf("row-a", "row-b", "row-c")

        assertEquals(
            "форвард: порядок каталога",
            targets,
            ReverseExtras.orderFor(targets, reverse = false)
        )
        assertEquals(
            "откат: обратный порядок применения внутри группы",
            listOf("row-c", "row-b", "row-a"),
            ReverseExtras.orderFor(targets, reverse = true)
        )
    }

    @Test
    fun `unreadable switch state is not a success either`() {
        // Задел, а не живая ветка: isChecked возвращает Boolean, поэтому checkedNow не бывает
        // null (см. KDoc ReverseExtras.REASON_STATE_UNREADABLE) — тест фиксирует контракт правила.
        assertEquals(
            ExtraRestoreDecision.Unreadable(ReverseExtras.REASON_STATE_UNREADABLE),
            ReverseExtras.decide(rowFound = true, checkedNow = null, snapshotValue = true)
        )
    }

    @Test
    fun `reverse drill step must reach its own screen before drilling`() {
        // Форвард не меняется: решение — бурить с текущего экрана, предикаты не вызываются.
        var forwardReads = 0
        assertEquals(
            ReverseEntryAction.CONTINUE_DRILL,
            ReverseExtras.entryAction(
                reverse = false,
                onStepScreen = { forwardReads++; false },
                budgetAboveReserveMs = 30_000L,
                attemptsLeft = 3,
                foreignWindow = { forwardReads++; false }
            )
        )
        assertEquals("форвард не платит за чтение экрана", 0, forwardReads)

        assertEquals(
            "откат: шаг на своём экране — бурим",
            ReverseEntryAction.CONTINUE_DRILL,
            ReverseExtras.entryAction(true, { true }, 30_000L, 3, { false })
        )
        assertEquals(
            "откат: нейтральное окно (Настройки, лаунчер, карточки цели) — бурение от корня сразу",
            ReverseEntryAction.DRILL_FROM_NEUTRAL,
            ReverseExtras.entryAction(true, { false }, 30_000L, 3, { false })
        )
        assertEquals(
            "откат: нейтральное окно бурим и без бюджета с попытками",
            ReverseEntryAction.DRILL_FROM_NEUTRAL,
            ReverseExtras.entryAction(true, { false }, -1L, 0, { false })
        )
        assertEquals(
            "откат: пустые интенты (attemptsLeft=0) на чужом окне — честный провал без цикла повторов",
            ReverseEntryAction.FAIL_SCREEN_NOT_REACHED,
            ReverseExtras.entryAction(true, { false }, 30_000L, 0, { true })
        )
        assertEquals(
            "откат: бюджет ниже резерва на вход и тумблер — повтор не выдаётся",
            ReverseEntryAction.FAIL_SCREEN_NOT_REACHED,
            ReverseExtras.entryAction(true, { false }, -1L, 3, { true })
        )
        assertEquals(
            "откат: чужое окно при живом бюджете и попытках — повтор входа, а не провал",
            ReverseEntryAction.RETRY_ENTRY,
            ReverseExtras.entryAction(true, { false }, 30_000L, 3, { true })
        )
        assertEquals(
            "откат: чужое окно — провал и без бюджета с попытками",
            ReverseEntryAction.FAIL_SCREEN_NOT_REACHED,
            ReverseExtras.entryAction(true, { false }, -1L, 0, { true })
        )
    }

    @Test
    fun `cancellation during rollback is not a failure and does not touch the snapshot`() {
        // Отмена обратного канала симметрична прямому: остановленный откат не рапортует
        // FAILED и не запускает остаток (запись в снапшот идёт из самого раннера и до
        // отменённого шага не доходит). Прогон rmvcp6zzq: причина cancelled уезжала в отчёт
        // провалов, а вместе с ней «не восстановленными» числились и непройденные шаги.
        val steps = listOf(step("a", targetChecked = false), step("b", targetChecked = false))
        val runCalls = mutableListOf<SimpleSteps.Step>()
        val done = mutableListOf<Pair<String, Boolean>>()
        val results = mutableListOf<ReverseStepResult>()
        val progress = mutableListOf<Float>()
        val runner = ReverseToggleRunner(
            findStep = { id -> steps.firstOrNull { it.id == id } },
            runStep = { s, _ ->
                runCalls += s
                throw kotlinx.coroutines.CancellationException("cancelled during rollback")
            },
            log = {}
        )

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                runner.run(
                    stepIds = LinkedHashSet(steps.map { it.id }),
                    checkedBeforeStates = mapOf("a" to true, "b" to true),
                    requireOverlay = true,
                    shouldStop = { false },
                    onStep = { _, _, _ -> },
                    onStepDone = { id, ok -> done += id to ok },
                    onStepResult = { results += it },
                    onProgress = { progress += it }
                )
            }
        }

        assertEquals("отменённый шаг не превращается в FAILED", 0, results.size)
        assertEquals("итог отменённого шага в UI не отправляется", 0, done.size)
        assertEquals("прогресс после отмены не двигается", 0, progress.size)
        assertEquals("остаток отката после отмены не запускается", listOf("a"), runCalls.map { it.id })
    }
}
