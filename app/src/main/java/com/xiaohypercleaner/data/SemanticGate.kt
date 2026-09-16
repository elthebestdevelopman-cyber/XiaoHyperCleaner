package com.xiaohypercleaner.data

import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.TextMatcher

/**
 * Гейт уверенности семантического шага.
 *
 * Действуем только при совпадении ТРЁХ условий: keyword-match на экране,
 * найденный переключатель (или настроенный tap-fallback) и совпавшие
 * screenMarkers. Иначе — `low_confidence` без угадывания: ложные нажатия
 * в чужих приложениях недопустимы.
 */
object SemanticGate {

    private const val TAG = "semantic"

    data class Decision(
        val act: Boolean,
        /** Детальная причина для лога (при skip — low_confidence). */
        val detail: String
    ) {
        val reason: String get() = if (act) "ok" else "low_confidence"
    }

    /**
     * Чистое решение гейта (тестируемо без Accessibility).
     *
     * @param keywords keywords шага (7 локалей через каталог)
     * @param screenText текст текущего экрана
     * @param screenMarkers ожидаемые маркеры экрана (пусто = проверка не требуется)
     * @param switchFound найден ли переключатель на экране
     * @param hasTapFallback настроены ли тексты tap-fallback (кнопка вместо тумблера)
     */
    fun decide(
        keywords: List<String>,
        screenText: String,
        screenMarkers: List<String>,
        switchFound: Boolean,
        hasTapFallback: Boolean
    ): Decision {
        if (keywords.isEmpty()) return Decision(false, "no_keywords")
        val keywordHit = keywords.any { TextMatcher.normalizedContains(screenText, it) }
        if (!keywordHit) return Decision(false, "keyword_mismatch")

        val markerHits = screenMarkers.count { TextMatcher.normalizedContains(screenText, it) }
        val markersOk = screenMarkers.isEmpty() || markerHits > 0
        if (!markersOk) return Decision(false, "markers_mismatch")

        if (!switchFound && !hasTapFallback) return Decision(false, "switch_not_found")

        return Decision(true, "keyword+markers+target")
    }

    /** Лог решения гейта (единый формат для диагностики прогона). */
    fun log(stepId: String, decision: Decision) {
        AppLog.i(
            TAG,
            "gate step=$stepId act=${decision.act} reason=${decision.reason} detail=${decision.detail}"
        )
    }
}