package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Гейт уверенности: действовать только при keyword-match И найденном
 * переключателе (или tap-fallback) И совпавших screenMarkers.
 *
 * Область повышенного риска: пропуск гейта = ложные нажатия в чужом UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SemanticGateTest {

    private val markers = listOf("Приложения", "Apps")

    @Test
    fun `acts when keyword markers and switch all match`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Получать рекомендации"),
            screenText = "Настройки Приложения Получать рекомендации",
            screenMarkers = markers,
            switchFound = true,
            hasTapFallback = false
        )

        assertTrue(decision.act)
        assertEquals("ok", decision.reason)
    }

    @Test
    fun `skips with low_confidence when keyword missing`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Получать рекомендации"),
            screenText = "Настройки Приложения Что-то другое",
            screenMarkers = markers,
            switchFound = true,
            hasTapFallback = false
        )

        assertFalse(decision.act)
        assertEquals("low_confidence", decision.reason)
        assertEquals("keyword_mismatch", decision.detail)
    }

    @Test
    fun `skips when screen markers do not match`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Получать рекомендации"),
            screenText = "Получать рекомендации",
            screenMarkers = markers,
            switchFound = true,
            hasTapFallback = false
        )

        assertFalse(decision.act)
        assertEquals("markers_mismatch", decision.detail)
    }

    @Test
    fun `skips when switch missing and no tap fallback`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Получать рекомендации"),
            screenText = "Настройки Приложения Получать рекомендации",
            screenMarkers = markers,
            switchFound = false,
            hasTapFallback = false
        )

        assertFalse(decision.act)
        assertEquals("switch_not_found", decision.detail)
    }

    @Test
    fun `acts for action button screen without switch`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Удалить рекламный идентификатор"),
            screenText = "Приложения Реклама Удалить рекламный идентификатор",
            screenMarkers = markers,
            switchFound = false,
            hasTapFallback = true
        )

        assertTrue("tap-fallback заменяет тумблер", decision.act)
    }

    @Test
    fun `empty markers list does not block the gate`() {
        val decision = SemanticGate.decide(
            keywords = listOf("Карусель"),
            screenText = "Настройки Карусель",
            screenMarkers = emptyList(),
            switchFound = true,
            hasTapFallback = false
        )

        assertTrue(decision.act)
    }
}