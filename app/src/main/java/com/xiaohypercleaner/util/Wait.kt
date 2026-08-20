package com.xiaohypercleaner.util

import com.xiaohypercleaner.AppConstants
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

suspend fun waitFor(
    timeoutMs: Long,
    intervalMs: Long = AppConstants.UI_POLL_INTERVAL_MS,
    condition: suspend () -> Boolean
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        delay(intervalMs.milliseconds)
    }
    return condition()
}