package com.xiaohypercleaner.service

/**
 * Исход возврата одной настройки в откате Simple Mode.
 *
 * Три состояния отчёта — [APPLIED], [ALREADY_ORIGINAL], [UNREADABLE] — плюс [FAILED]
 * для «шаг отработал, но исходное состояние не подтвердилось». Сводить их к двум нельзя:
 * отчёт отката строится на этой структуре, и «нечитаемо» (значения в снапшоте нет —
 * вернуть нечем) принципиально отличается от «не получилось вернуть».
 */
enum class ReverseOutcome {
    /** Значение из снапшота применено. */
    APPLIED,

    /** Настройка уже стояла в исходном состоянии — трогать нечего. */
    ALREADY_ORIGINAL,

    /**
     * Возврат невозможен: значения в снапшоте нет либо строка, которую мы тумбили,
     * исчезла с экрана. Инверсия `targetChecked` в этом случае НЕ подставляется —
     * иначе дефект остаётся невидимым для отчёта.
     */
    UNREADABLE,

    /** Шаг отработал, но исходное состояние не подтвердилось (`verify_failed` и пр.). */
    FAILED
}

/** Исход одной дополнительной цели отката (ключ снапшота `<stepId>#extra:<подпись>`). */
data class ReverseExtraResult(
    val text: String,
    val outcome: ReverseOutcome,
    val note: String? = null
)

/**
 * Итог отката одного шага.
 *
 * [ok] — вернулось ли исходное состояние; [outcome] — как именно; [relaunchUsed] —
 * потребовался ли повторный запуск маршрута; [extras] — исходы доп. целей;
 * [reason] — вердикт раннера или причина отказа на уровне отката.
 */
data class ReverseStepResult(
    val stepId: String,
    val ok: Boolean,
    val outcome: ReverseOutcome,
    val relaunchUsed: Boolean,
    val extras: List<ReverseExtraResult> = emptyList(),
    val reason: String? = null
)

/**
 * Контекст отката для [SimpleRunner]: снимок `checked_before` (главные ключи `<stepId>`
 * и ключи доп. целей `<stepId>#extra:<подпись>`) и приёмник исходов доп. целей.
 *
 * Не-null контекст означает «шаг выполняется как откат»: целевые состояния берутся из
 * снимка, а запись снимка запрещена (иначе откат перезаписывает то, что возвращает).
 */
class ReverseContext(
    val snapshot: Map<String, Boolean>,
    val extras: MutableList<ReverseExtraResult> = mutableListOf()
) {
    /** Откат этого шага пользовался повторным запуском маршрута. */
    var relaunchUsed: Boolean = false
}

/**
 * Решение по одной строке-цели в откате. Причина ([reason]) живёт ВНУТРИ решения:
 * production берёт её в лог и в [ReverseExtraResult.note], второго `when` по тем же
 * случаям нигде нет — правило не копируется, а возвращается вместе с исходом.
 */
sealed interface ExtraRestoreDecision {
    /** Токен причины для лога и отчёта (поле `reason=…`). */
    val reason: String

    /** Тумблить не нужно: строка уже в исходном состоянии (или мы её не тумбили). */
    data class AlreadyOriginal(override val reason: String) : ExtraRestoreDecision

    /** Строку надо привести к [target]. */
    data class Toggle(val target: Boolean, override val reason: String) : ExtraRestoreDecision

    /** Возврат невозможен: строка исчезла или её состояние не читается. */
    data class Unreadable(override val reason: String) : ExtraRestoreDecision
}

/** Действие канала отката перед бурением drill-шага (см. [ReverseExtras.entryAction]). */
enum class ReverseEntryAction {
    /** Шаг на своём экране (или это форвард) — бурим как обычно. */
    CONTINUE_DRILL,

    /** Шаг не на своём экране: повторный вход в приложение шага (образец `retryEnterStep`). */
    RETRY_ENTRY,

    /**
     * Окно нейтральное для шага (Настройки, лаунчер, карточки приложения-цели): возврат на
     * КОРЕНЬ маршрута одним входом и бурение оттуда. С текущего экрана drill уходил на
     * соседний под-экран (carousel: «Блокировка экрана» вместо «Карусели обоев»), а ожидание
     * цели без relaunch жгло три полных окна (appvault_services: confirm_miss ×3).
     */
    DRILL_FROM_NEUTRAL,

    /** Повторный вход не привёл шаг на его экран: честный провал вместо `low_confidence`. */
    FAIL_SCREEN_NOT_REACHED
}

/**
 * Что делать с шагом после группы доп. целей перед главным тумблером (только откат).
 * Доп. цели с `back` уводят из приложения в системные Настройки — главный тумблер тогда искали
 * не там (прогон 23–24.10: carousel в откате, `markers_mismatch` на «Блокировке экрана»).
 */
enum class ExtraReanchorAction {
    /** Экран шага на месте — ничего не делаем. */
    NONE,

    /** Приложение шага в фокусе, но экран не подтверждён: один возврат к корню маршрута. */
    REANCHOR,

    /** В фокусе чужое окно: слепых действий нет — честный `screen_not_reached`. */
    SCREEN_NOT_REACHED
}

/**
 * Решение одного оборота цикла входа drill-шага в откате — см.
 * [ReverseExtras.entryLoopDecision].
 *
 * [neutralWindow] — окно нейтральное (не своё и не чужое): вызывающий пишет
 * `reverse_drill_from_neutral` один раз на шаг. [attempt] — счётчик после решения (нужен логу
 * повтора); для действий без повтора остаётся прежним. [stopReason] — причина, по которой
 * повторы прекращены ([ReverseExtras.REASON_ATTEMPTS_EXHAUSTED] /
 * [ReverseExtras.REASON_BUDGET_BELOW_RESERVE]); `null`, если повторы не прекращались.
 */
data class ReverseEntryDecision(
    val action: ReverseEntryAction,
    val neutralWindow: Boolean,
    val attempt: Int = 0,
    val stopReason: String? = null
)

/**
 * Правила возврата строк-целей в откате, вынесенные из [SimpleRunner.toggleExtraRow]
 * как чистые решения: тест обязан падать на подмене значения, а не на формате ключа.
 */
object ReverseExtras {

    // Причины исходов: единственный источник токенов `reason=` в логе и
    // ReverseExtraResult.note — дублировать эти строки в раннере нельзя.
    const val REASON_NOT_TOGGLED_BY_US = "not_toggled_by_us"
    const val REASON_ALREADY_ORIGINAL = "already_original"
    const val REASON_ROW_ABSENT = "row_absent"

    // Причины останова повторов входа (цикл drill-шага в откате): отдельная ось от причин
    // исходов строк-целей выше — токены идут в лог `reverse_drill_fallback` и в
    // `reverse entry: … screen not reached`.
    /** Потолок попыток исчерпан: повтор не выдаётся, шаг бурит текущий экран. */
    const val REASON_ATTEMPTS_EXHAUSTED = "attempts_exhausted"

    /** Остаток бюджета ниже резерва на вход и тумблер: повтор съел бы время самого действия. */
    const val REASON_BUDGET_BELOW_RESERVE = "budget_below_reserve"

    /**
     * Вход нерезолвящийся: ни один кандидат шага не открывается на устройстве. Повтор бессмыслен
     * (запускать нечего), поэтому нейтральное окно сразу уходит в бурение с корня, а чужое —
     * в честный провал шага (прогон rmvgvhbzu: три попытки за 20 мс, каждая падала в
     * `No Activity found` ещё до ожидания экрана).
     */
    const val REASON_ENTRY_UNRESOLVED = "entry_unresolved"

    /**
     * Вход резолвится в нейтральное окно (Настройки, лаунчер, системные поверхности): экран
     * шага так не открыть, повтор заново запускал бы тот же нейтральный экран. Бурение идёт
     * с текущего экрана по `drillPath` (прогон rmvjqdfrt: carousel — три повтора за 300 мс).
     */
    const val REASON_ENTRY_NEUTRAL = "entry_neutral"

    /**
     * Решение «нужен ли возврат к экрану шага после доп. целей»: экран подтверждён — не нужен;
     * приложение шага в фокусе — один возврат к корню; чужое окно — честный провал шага.
     * Чистая функция: тест падает на подмене значения, а не на порядке вызовов.
     */
    fun extraReanchorAction(onTargetScreen: Boolean, ownPackage: Boolean): ExtraReanchorAction =
        when {
            onTargetScreen -> ExtraReanchorAction.NONE
            ownPackage -> ExtraReanchorAction.REANCHOR
            else -> ExtraReanchorAction.SCREEN_NOT_REACHED
        }

    /**
     * Пока НЕДОСТИЖИМА в production: [SwitchFinder.isChecked] возвращает `Boolean`, поэтому
     * `checkedNow` не бывает `null` и «строки нет» с «состояние не прочитано» для вызывающего
     * кода неразличимы. Оставлена как задел на трёхзначное чтение состояния
     * (есть / нет / неизвестно) — ветку не считать живой на приёмке.
     */
    const val REASON_STATE_UNREADABLE = "state_unreadable"
    const val REASON_TOGGLED = "toggled"
    const val REASON_TAP_FAILED = "tap_failed"
    const val REASON_CONFIRM_FAILED = "confirm_failed"
    const val REASON_NOT_VERIFIED = "not_verified"

    /**
     * [rowFound] — строка-цель найдена на экране; [checkedNow] — её фактическое состояние;
     * [snapshotValue] — значение из снапшота (`null` = ключа нет).
     *
     * Ключа нет — строку мы не тумбили, возвращать нечего
     * ([ExtraRestoreDecision.AlreadyOriginal]). Ключ есть, а строки нет (в том числе потому,
     * что главный тумблер уже выключен) — это исчезнувшая цель, а не «цель выполнена»:
     * [ExtraRestoreDecision.Unreadable].
     */
    fun decide(
        rowFound: Boolean,
        checkedNow: Boolean?,
        snapshotValue: Boolean?
    ): ExtraRestoreDecision = when {
        snapshotValue == null ->
            ExtraRestoreDecision.AlreadyOriginal(REASON_NOT_TOGGLED_BY_US)
        !rowFound -> ExtraRestoreDecision.Unreadable(REASON_ROW_ABSENT)
        checkedNow == null -> ExtraRestoreDecision.Unreadable(REASON_STATE_UNREADABLE)
        checkedNow == snapshotValue ->
            ExtraRestoreDecision.AlreadyOriginal(REASON_ALREADY_ORIGINAL)
        else -> ExtraRestoreDecision.Toggle(checkNotNull(snapshotValue), REASON_TOGGLED)
    }

    /**
     * Порядок целей в откате: фазы те же, что в форварде (beforeMain -> главный -> afterMain),
     * внутри группы — ОБРАТНЫЙ порядок применения.
     *
     * «Главный первым» для beforeMain-группы запрещён: её строки исчезают вместе с главным
     * тумблером, и порядок main-first дал бы ровно тот `unreadable`, который откат обязан
     * исключать (в каталоге шесть таких строк). Правило одно на все группы — копий `if (rev
     * != null) reversed()` по файлам не заводим.
     */
    fun <T> orderFor(targets: List<T>, reverse: Boolean): List<T> =
        if (reverse) targets.reversed() else targets
    /**
     * Цикл входа drill-шага в откате: предыдущий шаг мог оставить своё окно, и бурение с чужого
     * экрана даёт `low_confidence` вместо честного провала (прогон rmv5fd8wl: `carousel`
     * стартовал с `com.google.android.gms` и бурил Настройки).
     *
     * Один вызов = один оборот цикла: функция возвращает, что делать СЕЙЧАС, и с каким счётчиком
     * продолжать ([ReverseEntryDecision.attempt]); вызывающий применяет решение и спрашивает
     * заново. Именно поэтому порядок «немедленный вход → пересчёт → гейтовые повторы» проверяется
     * тестом, а не чтением раннера.
     *
     * [onStepScreen] и [foreignWindow] — ленивые предикаты: в форварде они НЕ вызываются
     * (правило возвращает [ReverseEntryAction.CONTINUE_DRILL] первым же условием), поэтому
     * стоимость чтений окна платит только канал отката. Форвардное поведение не меняется.
     *
     * Порядок решения (он же — приоритет сигналов):
     * 1. форвард или шаг на своём экране → [ReverseEntryAction.CONTINUE_DRILL];
     * 2. окно НЕ чужое (нейтральное: Настройки, лаунчер, карточки приложения-цели): интентов
     *    нет — входить нечем, бурим текущий экран; иначе один
     *    [ReverseEntryAction.DRILL_FROM_NEUTRAL] (немедленный вход от КОРНЯ маршрута); если вход
     *    нерезолвящийся ([entryResolvable] = false) — сразу бурение текущего экрана с причиной
     *    [REASON_ENTRY_UNRESOLVED]; затем гейтовые повторы [ReverseEntryAction.RETRY_ENTRY],
     *    после — бурение текущего экрана;
     * 3. чужое окно, есть и бюджет, и попытки, и резолвящийся вход → [ReverseEntryAction.RETRY_ENTRY];
     * 4. чужое окно, повторов больше нет → [ReverseEntryAction.FAIL_SCREEN_NOT_REACHED]
     *    (причина: сначала нерезолвящийся вход, затем потолок, затем бюджет).
     *
     * [hasIntents] — статический признак плана (`step.intents` непустой), [entryResolvable] —
     * результат рантайм-резолва кандидатов: источники разные, поэтому параметры живут раздельно.
     * Ветка `!hasIntents` остаётся первой, иначе нейтральное окно потеряло бы токен причины.
     *
     * Гейт повторов — инвариант ДВУХ каналов: каждый повтор входа (и на нейтральном, и на чужом
     * окне) выдаётся только при остатке бюджета НАД резервом ([budgetAboveReserveMs] — вызывающий
     * вычитает резерв на вход и на тумблер) И неисчерпанном потолке ([attempts] от нуля до
     * [maxAttempts]). Одна попытка отдавала `screen_not_reached` при живом бюджете (прогон
     * rmvb6atul: msa и ux_program — провал за 6,1 с при 23 с успеха в прямой фазе). Причина
     * останова возвращается в [ReverseEntryDecision.stopReason]: без неё выход из цикла на первом
     * обороте после входа был невидим в логе (прогон rmve63243).
     *
     * Решение `d0482ac`: НЕМЕДЛЕННЫЙ вход от корня бюджета не требует — это один дешёвый вход,
     * который ставит приложение на ноги. Гейт на нём вернул бы `done 15/22`: в rmve63243 пять
     * app-шагов падали сразу после обрыва цикла. Гейтятся только ПОВТОРЫ после него. Историческая
     * справка: §94 гейтила первый повтор целиком; после `64616d0`/`d0482ac` первым действием стал
     * немедленный вход, и гейт относится к повторам после него — это осознанное решение, а не
     * недосмотр: без входа шаг бурит нейтральный экран и всё равно падает.
     */
    fun entryLoopDecision(
        reverse: Boolean,
        onStepScreen: () -> Boolean,
        foreignWindow: () -> Boolean,
        hasIntents: Boolean,
        attempts: Int,
        maxAttempts: Int,
        immediateEntryDone: Boolean,
        budgetAboveReserveMs: Long,
        entryResolvable: Boolean = true,
        entryLeadsToNeutral: Boolean = false
    ): ReverseEntryDecision = when {
        !reverse || onStepScreen() ->
            ReverseEntryDecision(ReverseEntryAction.CONTINUE_DRILL, neutralWindow = false)
        !foreignWindow() -> when {
            // Без интентов входить нечем: повтор ничего не изменит — бурим текущий экран.
            !hasIntents ->
                ReverseEntryDecision(ReverseEntryAction.CONTINUE_DRILL, neutralWindow = true)
            // Нейтральное окно (Настройки, лаунчер, карточки цели): ОДИН немедленный вход от
            // КОРНЯ маршрута. С текущего экрана drill уходил на соседний под-экран (carousel:
            // «Блокировка экрана» вместо «Карусели обоев») и давал confirm_miss
            // (appvault_services ×3), а ожидание цели без входа жгло три полных окна.
            !immediateEntryDone ->
                ReverseEntryDecision(ReverseEntryAction.DRILL_FROM_NEUTRAL, neutralWindow = true)
            // Вход нерезолвящийся — повторять нечего: бурение текущего экрана, причина в лог.
            !entryResolvable -> ReverseEntryDecision(
                ReverseEntryAction.CONTINUE_DRILL,
                neutralWindow = true,
                stopReason = REASON_ENTRY_UNRESOLVED
            )
            // Вход резолвится в нейтральное окно (Настройки, лаунчер): экран шага так не
            // открыть, повтор лишь заново запускает тот же нейтральный экран. Бурение с
            // текущего экрана — по drillPath, с причиной в логе (прогон rmvjqdfrt: carousel —
            // три повтора за 300 мс и слепые тапы вместо уровня «Карусель обоев»).
            entryLeadsToNeutral -> ReverseEntryDecision(
                ReverseEntryAction.CONTINUE_DRILL,
                neutralWindow = true,
                stopReason = REASON_ENTRY_NEUTRAL
            )
            // Немедленный вход не помог: дальше гейтовые повторы — потолок И остаток над резервом.
            // Причина останова: сначала потолок, затем бюджет (детерминированно для лога).
            attempts >= maxAttempts -> ReverseEntryDecision(
                ReverseEntryAction.CONTINUE_DRILL,
                neutralWindow = true,
                stopReason = REASON_ATTEMPTS_EXHAUSTED
            )
            budgetAboveReserveMs <= 0 -> ReverseEntryDecision(
                ReverseEntryAction.CONTINUE_DRILL,
                neutralWindow = true,
                stopReason = REASON_BUDGET_BELOW_RESERVE
            )
            else -> ReverseEntryDecision(
                ReverseEntryAction.RETRY_ENTRY,
                neutralWindow = true,
                attempt = attempts + 1
            )
        }
        // Чужое окно: повтор осмыслен только при остатке НАД резервом, живых попытках и
        // резолвящемся входе.
        attempts < maxAttempts && budgetAboveReserveMs > 0 && entryResolvable -> ReverseEntryDecision(
            ReverseEntryAction.RETRY_ENTRY,
            neutralWindow = false,
            attempt = attempts + 1
        )
        // Чужое окно и повторов больше нет: честный провал шага вместо бурения чужого экрана.
        // Причина — корневая: нерезолвящийся вход важнее исчерпанного потолка и бюджета.
        else -> ReverseEntryDecision(
            ReverseEntryAction.FAIL_SCREEN_NOT_REACHED,
            neutralWindow = false,
            stopReason = when {
                !entryResolvable -> REASON_ENTRY_UNRESOLVED
                attempts >= maxAttempts -> REASON_ATTEMPTS_EXHAUSTED
                else -> REASON_BUDGET_BELOW_RESERVE
            }
        )
    }
}
