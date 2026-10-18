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
 * Правила возврата строк-целей в откате, вынесенные из [SimpleRunner.toggleExtraRow]
 * как чистые решения: тест обязан падать на подмене значения, а не на формате ключа.
 */
object ReverseExtras {

    // Причины исходов: единственный источник токенов `reason=` в логе и
    // ReverseExtraResult.note — дублировать эти строки в раннере нельзя.
    const val REASON_NOT_TOGGLED_BY_US = "not_toggled_by_us"
    const val REASON_ALREADY_ORIGINAL = "already_original"
    const val REASON_ROW_ABSENT = "row_absent"

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
     * Вход drill-шага в откате: предыдущий шаг мог оставить своё окно, и бурение с чужого
     * экрана даёт `low_confidence` вместо честного провала (прогон rmv5fd8wl: `carousel`
     * стартовал с `com.google.android.gms` и бурил Настройки).
     *
     * [onStepScreen] и [foreignWindow] — ленивые предикаты: в форварде они НЕ вызываются
     * (правило возвращает [ReverseEntryAction.CONTINUE_DRILL] первым же условием), поэтому
     * стоимость чтений окна платит только канал отката. Форвардное поведение не меняется.
     *
     * Порядок решения (он же — приоритет сигналов):
     * 1. форвард или шаг на своём экране → [ReverseEntryAction.CONTINUE_DRILL];
     * 2. окно НЕ чужое (нейтральное: Настройки, лаунчер, карточки приложения-цели) →
     *    [ReverseEntryAction.DRILL_FROM_NEUTRAL] — бурим сразу, но от корня маршрута;
     * 3. чужое окно, есть и бюджет, и попытки → [ReverseEntryAction.RETRY_ENTRY];
     * 4. чужое окно, повторов больше нет → [ReverseEntryAction.FAIL_SCREEN_NOT_REACHED].
     *
     * Повтор ограничен двумя границами: остатком бюджета НАД резервом
     * ([budgetAboveReserveMs] — вызывающий вычитает резерв на вход и на тумблер) и потолком
     * попыток ([attemptsLeft]). Одна попытка отдавала `screen_not_reached` при живом бюджете
     * (прогон rmvb6atul: msa и ux_program — провал за 6,1 с при 23 с успеха в прямой фазе).
     */
    fun entryAction(
        reverse: Boolean,
        onStepScreen: () -> Boolean,
        budgetAboveReserveMs: Long,
        attemptsLeft: Int,
        foreignWindow: () -> Boolean
    ): ReverseEntryAction = when {
        !reverse -> ReverseEntryAction.CONTINUE_DRILL
        onStepScreen() -> ReverseEntryAction.CONTINUE_DRILL
        // Нейтральное окно (Настройки, лаунчер, карточки приложения-цели): бурим сразу, но с
        // КОРНЯ маршрута — ожидание и возврат в такое окно ничего не дают, а бурение с него
        // уводит на соседний под-экран (carousel) и даёт confirm_miss (appvault_services).
        !foreignWindow() -> ReverseEntryAction.DRILL_FROM_NEUTRAL
        attemptsLeft > 0 && budgetAboveReserveMs > 0 -> ReverseEntryAction.RETRY_ENTRY
        // Чужое окно и повторов больше нет: честный провал шага вместо бурения чужого экрана.
        else -> ReverseEntryAction.FAIL_SCREEN_NOT_REACHED
    }
}
