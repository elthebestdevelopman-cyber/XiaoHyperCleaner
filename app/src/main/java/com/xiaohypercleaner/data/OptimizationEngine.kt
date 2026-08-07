package com.xiaohypercleaner.data

import android.util.Log
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.delay

data class OptimizationEngineConfig(
    val connectAttempts: Int = AppConstants.ADB_CONNECT_ATTEMPTS,
    val commandDelayMs: Long = AppConstants.COMMAND_DELAY_MS,
    val initialRetryDelayMs: Long = AppConstants.RETRY_DELAY_MS,
    val maxRetryDelayMs: Long = AppConstants.RETRY_DELAY_MS * 8,
    val keyCheckCommand: String = "settings get secure miui_ad_filtering_enabled",
    val keyExpectedValue: String = "0"
)

class OptimizationEngine(
    private val adb: AdbExecutor,
    private val config: OptimizationEngineConfig = OptimizationEngineConfig()
) {
    companion object {
        private const val TAG = "OptimizationEngine"
    }

    data class Callbacks(
        val onStage: (String) -> Unit = {},
        val onProgress: (Float) -> Unit = {},
        val onError: (String) -> Unit = {}
    )

    suspend fun optimize(cb: Callbacks = Callbacks()): Boolean {
        return try {
            cb.onProgress(0.05f); cb.onStage("connecting")
            if (!tryConnect()) {
                cb.onError("connect"); return false
            }
            cb.onProgress(0.15f); cb.onStage("method1")
            applyHiddenKeys()
            if (verifyAll()) return finish(cb, true)
            cb.onProgress(0.45f); cb.onStage("method2")
            disablePackages()
            if (verifyAll()) return finish(cb, true)
            cb.onProgress(0.7f); cb.onStage("method3")
            applyHiddenKeys(); disablePackages()
            if (verifyAll()) return finish(cb, true)
            cb.onStage("verifying")
            disablePackagesFallback()
            finish(cb, verifyAll())
        } catch (e: AdbException) {
            Log.w(TAG, "optimize failed [${e.code}]: ${LogMasker.mask(e.message.orEmpty())}")
            cb.onError(e.message ?: "adb error"); false
        } finally {
            adb.disconnect()
        }
    }

    suspend fun restore(cb: Callbacks = Callbacks()): Boolean {
        return try {
            cb.onStage("connecting")
            if (!tryConnect()) {
                cb.onError("connect"); return false
            }
            cb.onStage("restoring_keys"); cb.onProgress(0.3f)
            for (cmd in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
                adb.executeCommand(cmd); delay(config.commandDelayMs)
            }
            cb.onStage("restoring_packages"); cb.onProgress(0.6f)
            for (pkg in ServiceRegistry.PACKAGES) {
                adb.executeCommand("pm enable $pkg"); delay(config.commandDelayMs)
            }
            finish(cb, true)
        } catch (e: AdbException) {
            Log.w(TAG, "restore failed [${e.code}]"); cb.onError(e.message ?: "adb error"); false
        } finally {
            adb.disconnect()
        }
    }

    suspend fun reboot(): Boolean {
        return try {
            if (!tryConnect()) return false
            adb.executeCommand("reboot"); true
        } catch (e: AdbException) {
            Log.w(TAG, "reboot failed [${e.code}]"); false
        } finally {
            adb.disconnect()
        }
    }

    private fun finish(cb: Callbacks, ok: Boolean): Boolean {
        cb.onProgress(if (ok) 1f else 0.9f); return ok
    }

    private suspend fun tryConnect(): Boolean {
        var delayMs = config.initialRetryDelayMs
        repeat(config.connectAttempts) { attempt ->
            if (adb.connect()) return true
            if (attempt < config.connectAttempts - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(config.maxRetryDelayMs)
            }
        }
        return false
    }

    private suspend fun applyHiddenKeys() {
        repeat(2) { attempt ->
            for (cmd in ServiceRegistry.HIDDEN_KEYS_DISABLE) {
                adb.executeCommand(cmd); delay(config.commandDelayMs)
            }
            val v = adb.executeCommand(config.keyCheckCommand).trim()
            if (v == config.keyExpectedValue) return
            if (attempt < 1) delay(config.initialRetryDelayMs)
        }
    }

    private suspend fun disablePackages() {
        for (pkg in ServiceRegistry.PACKAGES) {
            if (isPackageDisabled(pkg)) continue
            val result = runCatching { adb.executeCommand("pm disable-user --user 0 $pkg") }
                .getOrDefault("")
            if (!looksSuccess(result)) {
                delay(300)
                runCatching { adb.executeCommand("pm disable --user 0 $pkg") }
            }
            delay(config.commandDelayMs)
        }
    }

    private suspend fun disablePackagesFallback() {
        for (pkg in ServiceRegistry.PACKAGES) {
            if (isPackageDisabled(pkg)) continue
            runCatching { adb.executeCommand("pm clear $pkg") }
            delay(config.commandDelayMs)
            runCatching { adb.executeCommand("pm disable-user --user 0 $pkg") }
            delay(config.commandDelayMs)
        }
    }

    private suspend fun isPackageDisabled(pkg: String): Boolean =
        adb.executeCommand("pm list packages -d").contains(pkg)

    private fun looksSuccess(result: String): Boolean {
        val lower = result.lowercase()
        return lower.contains("success") || lower.contains("disabled") || lower.contains("new state")
    }

    private suspend fun verifyAll(): Boolean {
        val disabled = adb.executeCommand("pm list packages -d")
        val missing = ServiceRegistry.PACKAGES.filter { !disabled.contains(it) }
        val keyOff = adb.executeCommand(config.keyCheckCommand).trim() == config.keyExpectedValue
        if (missing.isNotEmpty()) Log.w(TAG, "still enabled: $missing")
        return missing.isEmpty() || keyOff
    }
}