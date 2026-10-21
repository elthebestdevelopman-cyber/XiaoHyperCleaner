package com.xiaohypercleaner.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно «привыкания» отмены: в первые [OverlayService.CANCEL_SETTLE_MS] касание по
 * «Отменить» игнорируется. Прогон rmvimewq4: оба повторных прогона умерли на первом шаге
 * через 1–2 с после старта — событие съедала сама кнопка оверлея.
 */
class OverlayCancelSettleTest {

    @Test
    fun `cancel is ignored only inside the settle window`() {
        assertTrue("0 мс — окно активно", cancelSettleActive(nowMs = 1_000, startedMs = 1_000))
        assertTrue("2999 мс — окно активно", cancelSettleActive(nowMs = 3_999, startedMs = 1_000))
        assertFalse(
            "ровно на границе окно закрыто",
            cancelSettleActive(nowMs = 4_000, startedMs = 1_000)
        )
        assertFalse("позже — отмена доступна", cancelSettleActive(nowMs = 9_000, startedMs = 1_000))
    }
}