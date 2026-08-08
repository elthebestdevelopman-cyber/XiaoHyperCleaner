package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

class OptimizationEngine(private val adb: AdbExecutor) {

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
            cb.onProgress(AppConstants.PROGRESS_START)
            cb.onStage("connecting")
            if (!tryConnect()) {
                cb.onError("connect")
                return false
            }
            cb.onProgress(AppConstants.PROGRESS_CONNECTED)
            cb.onStage("method1")
            applyHiddenKeys()
            if (verifyAll()) return finish(cb, true)
            cb.onProgress(AppConstants.PROGRESS_METHOD2)
            cb.onStage("method2")
            disablePackages()
            if (verifyAll()) return finish(cb, true)
            cb.onProgress(AppConstants.PROGRESS_METHOD3)
            cb.onStage("method3")
            applyHiddenKeys()
            disablePackages()
            if (verifyAll()) return finish(cb, true)
            cb.onStage("verifying")
            disablePackagesFallback()
            finish(cb, verifyAll())
        } catch (e: AdbException) {
            AppLog.w(TAG, "optimize failed: ${e.message}")
            cb.onError(e.message ?: "adb error")
            false
        } finally {
            adb.disconnect()
        }
    }

    suspend fun restore(cb: Callbacks = Callbacks()): Boolean {
        return try {
            cb.onStage("connecting")
            if (!tryConnect()) {
                cb.onError("connect")
                return false
            }
            cb.onStage("restoring_keys")
            cb.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)
            for (cmd in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            cb.onStage("restoring_packages")
            cb.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)
            for (pkg in ServiceRegistry.PACKAGES) {
                adb.executeCommand("pm enable $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            finish(cb, true)
        } catch (e: AdbException) {
            AppLog.w(TAG, "restore failed: ${e.message}")
            cb.onError(e.message ?: "adb error")
            false
        } finally {
            adb.disconnect()
        }
    }

    suspend fun reboot(): Boolean {
        return try {
            if (!tryConnect()) return false
            adb.executeCommand("reboot")
            true
        } catch (e: AdbException) {
            AppLog.w(TAG, "reboot failed: ${e.message}")
            false
        } finally {
            adb.disconnect()
        }
    }

    private fun finish(cb: Callbacks, ok: Boolean): Boolean {
        cb.onProgress(if (ok) AppConstants.PROGRESS_DONE else AppConstants.PROGRESS_FAIL)
        return ok
    }

    private suspend fun tryConnect(): Boolean {
        repeat(AppConstants.ADB_CONNECT_ATTEMPTS) { attempt ->
            if (adb.connect()) return true
            delay(AppConstants.RETRY_DELAY_MS + attempt * 400L)
        }
        return false
    }

    private suspend fun applyHiddenKeys() {
        repeat(2) { attempt ->
            for (cmd in ServiceRegistry.HIDDEN_KEYS_DISABLE) {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            val v = adb.executeCommand("settings get secure miui_ad_filtering_enabled").trim()
            if (v == "0") return
            if (attempt < 1) delay(AppConstants.RETRY_DELAY_MS)
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
            delay(AppConstants.COMMAND_DELAY_MS)
        }
    }

    private suspend fun disablePackagesFallback() {
        for (pkg in ServiceRegistry.PACKAGES) {
            if (isPackageDisabled(pkg)) continue
            runCatching { adb.executeCommand("pm clear $pkg") }
            delay(AppConstants.COMMAND_DELAY_MS)
            runCatching { adb.executeCommand("pm disable-user --user 0 $pkg") }
            delay(AppConstants.COMMAND_DELAY_MS)
        }
    }

    private suspend fun isPackageDisabled(pkg: String): Boolean {
        val list = adb.executeCommand("pm list packages -d")
        return list.contains(pkg)
    }

    private fun looksSuccess(result: String): Boolean {
        val lower = result.lowercase()
        return lower.contains("success") || lower.contains("disabled") || lower.contains("new state")
    }

    private suspend fun verifyAll(): Boolean {
        val disabled = adb.executeCommand("pm list packages -d")
        val missing = ServiceRegistry.PACKAGES.filter { !disabled.contains(it) }
        val keyOff = adb.executeCommand("settings get secure miui_ad_filtering_enabled")
            .trim() == "0"
        if (missing.isNotEmpty()) AppLog.w(TAG, "still enabled: $missing")
        return missing.isEmpty() || keyOff
    }
}