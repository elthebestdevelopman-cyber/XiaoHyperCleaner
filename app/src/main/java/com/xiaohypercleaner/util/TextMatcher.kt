package com.xiaohypercleaner.util

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Нормализация текста для сопоставления подписей в дереве Accessibility.
 *
 * MIUI вставляет в подписи настроек мягкие переносы (U+00AD) и другие невидимые
 * символы, из-за чего точное сравнение `findAccessibilityNodeInfosByText` и
 * `String.contains` не находит нужные элементы. Все сравнения в SimpleRunner
 * должны идти через этот объект.
 */
object TextMatcher {

    /** Невидимые/форматирующие символы, вставляемые MIUI в подписи. */
    private val INVISIBLE = charArrayOf(
        '\u00AD', // soft hyphen (мягкий перенос)
        '\u200B', // zero width space
        '\u200C', // zero width non-joiner
        '\u200D', // zero width joiner
        '\u200E', // left-to-right mark
        '\u200F', // right-to-left mark
        '\u2060', // word joiner
        '\uFEFF'  // zero width no-break space (BOM)
    )

    /** Каноническая форма строки: NFC, нижний регистр, без невидимых символов, схлопнутые пробелы. */
    fun normalize(input: String?): String {
        if (input == null) return ""
        var s = Normalizer.normalize(input, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        for (c in INVISIBLE) {
            s = s.replace(c.toString(), "")
        }
        s = s.replace('\u00A0', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    fun normalizedEquals(a: String?, b: String?): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotEmpty() && na == nb
    }

    fun normalizedContains(haystack: String?, needle: String?): Boolean {
        val n = normalize(needle)
        if (n.isEmpty()) return false
        return normalize(haystack).contains(n)
    }

    /**
     * Нечёткое сравнение по коэффициенту похожести (расстояние Левенштейна).
     * Используется как последний рубеж, когда точное совпадение не найдено.
     */
    fun isFuzzyMatch(a: String?, b: String?, threshold: Double = 0.85): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (na.contains(nb) || nb.contains(na)) return true
        return levenshteinRatio(na, nb) >= threshold
    }

    private fun levenshteinRatio(a: String, b: String): Double {
        val m = a.length
        val n = b.length
        if (m == 0) return if (n == 0) 1.0 else 0.0
        if (n == 0) return 0.0
        if (abs(m - n) > maxOf(2, m / 3)) return 0.0
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val curr = IntArray(n + 1)
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = curr
        }
        return 1.0 - prev[n].toDouble() / maxOf(m, n)
    }
}