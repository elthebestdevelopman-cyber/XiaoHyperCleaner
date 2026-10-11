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

/** Решение по одной строке-цели в откате. */
sealed interface ExtraRestoreDecision {
    /** Тумблить не нужно: строка уже в исходном состоянии (или мы её не тумбили). */
    data object AlreadyOriginal : ExtraRestoreDecision

    /** Строку надо привести к [target]. */
    data class Toggle(val target: Boolean) : ExtraRestoreDecision

    /** Возврат невозможен: строка исчезла или её состояние не читается. */
    data object Unreadable : ExtraRestoreDecision
}

/**
 * Правила возврата строк-целей в откате, вынесенные из [SimpleRunner.toggleExtraRow]
 * как чистые решения: тест обязан падать на подмене значения, а не на формате ключа.
 */
object ReverseExtras {

    /**
     * [rowFound] — строка-цель найдена на экране; [checkedNow] — её фактическое состояние;
     * [snapshotValue] — значение из снапшота (`null` = ключа нет).
     *
     * Ключа нет — строку мы не тумбили, возвращать нечего ([AlreadyOriginal]).
     * Ключ есть, а строки нет (в том числе потому, что главный тумблер уже выключен) —
     * это исчезнувшая цель, а не «цель выполнена»: [Unreadable].
     */
    fun decide(rowFound: Boolean, checkedNow: Boolean?, snapshotValue: Boolean?): ExtraRestoreDecision = when {
        snapshotValue == null -> ExtraRestoreDecision.AlreadyOriginal
        !rowFound || checkedNow == null -> ExtraRestoreDecision.Unreadable
        checkedNow == snapshotValue -> ExtraRestoreDecision.AlreadyOriginal
        else -> ExtraRestoreDecision.Toggle(checkNotNull(snapshotValue))
    }
}