package com.xiaohypercleaner.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Полнота отката (R1).
 *
 * Прогон rmuyl4opt: снапшот хранил только главный тумблер, поэтому откат карусели
 * возвращал одну строку из пяти, а зависимые подстроки оставались выключенными.
 * Тест фиксирует контракт ключа доп. цели: разбор на стороне отката идёт по маркеру
 * `#extra:`, а вторая часть ключа — ПОДПИСЬ строки (по ней откат её и находит).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ReverseRollbackTest {

    private val runner = SimpleRunner(Mockito.mock(AdbEnablerService::class.java))

    @Test
    fun `extra target key keeps step id and row label`() {
        val key = runner.extraToggleKey("carousel", "Реклама на Экране блокировки")

        assertEquals("carousel#extra:Реклама на Экране блокировки", key)
        assertEquals("откат разбирает ключ по маркеру", "carousel", key.substringBefore(SimpleRunner.EXTRA_KEY_MARKER))
        assertEquals(
            "вторая часть ключа — подпись строки",
            "Реклама на Экране блокировки",
            key.substringAfter(SimpleRunner.EXTRA_KEY_MARKER)
        )
    }
}