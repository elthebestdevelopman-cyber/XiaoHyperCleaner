package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {

    @Test
    fun `soft hyphen is removed and lowercased`() {
        assertEquals("получать", TextMatcher.normalize("Полу\u00ADчать"))
    }

    @Test
    fun `invisible chars and nbsp are collapsed`() {
        assertEquals("ab c", TextMatcher.normalize("a\u200Bb\u00A0\u00A0 c"))
    }

    @Test
    fun `normalized equals ignores case`() {
        assertTrue(TextMatcher.normalizedEquals("Реклама", "реклама"))
        assertFalse(TextMatcher.normalizedEquals("", "x"))
        assertFalse(TextMatcher.normalizedEquals("", ""))
    }

    @Test
    fun `normalized contains`() {
        assertTrue(TextMatcher.normalizedContains("Персонализированная реклама", "реклама"))
        assertFalse(TextMatcher.normalizedContains("персонализация", "реклама"))
    }

    @Test
    fun `fuzzy match tolerates single typo`() {
        assertTrue(TextMatcher.isFuzzyMatch("реклама", "рекламв"))
        assertTrue(TextMatcher.isFuzzyMatch("Персонализация рекламы", "Персонализация рекламы"))
        assertFalse(TextMatcher.isFuzzyMatch("abcd", "wxyz"))
    }

    // ── Порог 0.88: используется в SimpleRunner.matchesAny как последний рубеж ──

    @Test
    fun `threshold 0_88 rejects single typo in short word`() {
        // Одна опечатка в слове из 7 букв: ratio = 1 - 1/7, примерно 0.857.
        // Проходит порог 0.85 по умолчанию, но не более строгий 0.88.
        assertTrue(TextMatcher.isFuzzyMatch("реклама", "рекламв"))
        assertFalse(TextMatcher.isFuzzyMatch("реклама", "рекламв", threshold = 0.88))
    }

    @Test
    fun `threshold 0_88 accepts near identical long labels`() {
        // Одна опечатка в длинной подписи: ratio >= 0.9, проходит 0.88.
        assertTrue(TextMatcher.isFuzzyMatch("Очистить данные", "Очистить даные", threshold = 0.88))
        assertTrue(TextMatcher.isFuzzyMatch("Clear data", "Clear dta", threshold = 0.88))
    }

    @Test
    fun `threshold 0_88 rejects unrelated words`() {
        assertFalse(TextMatcher.isFuzzyMatch("Очистить", "Удалить", threshold = 0.88))
        assertFalse(TextMatcher.isFuzzyMatch("Настройки", "Экран", threshold = 0.88))
    }

    @Test
    fun `substring matches regardless of threshold`() {
        // Ветка contains срабатывает до вычисления расстояния Левенштейна.
        assertTrue(TextMatcher.isFuzzyMatch("Очистить все данные", "данные", threshold = 0.88))
        assertTrue(TextMatcher.isFuzzyMatch("Clear all data", "clear", threshold = 0.88))
    }
}