package com.xiaohypercleaner.data

import android.util.Log
import com.xiaohypercleaner.AppConstants
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
            cb.onProgress(0.05f); cb.onStage("connecting")
            if (!tryConnect()) {
                cb.onError("connect"); return false
            }
            val targets = targetPackages()
            cb.onProgress(0.15f); cb.onStage("method1")
            applyHiddenKeys()
            if (verifyAll(targets)) return finish(cb, true)
            cb.onProgress(0.45f); cb.onStage("method2")
            disablePackages(targets)
            if (verifyAll(targets)) return finish(cb, true)
            cb.onProgress(0.7f); cb.onStage("method3")
            applyHiddenKeys()
            disablePackages(targets)
            if (verifyAll(targets)) return finish(cb, true)
            cb.onStage("verifying")
            disablePackagesFallback(targets)
            finish(cb, verifyAll(targets))
        } catch (e: AdbException) {
            Log.w(TAG, "optimize failed: ${e.message}")
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
                cb.onError("connect"); return false
            }
            cb.onStage("restoring_keys"); cb.onProgress(0.3f)
            for (cmd in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            cb.onStage("restoring_packages"); cb.onProgress(0.6f)
            val installed = installedPackages()
            for (pkg in ServiceRegistry.PACKAGES) {
                if (installed.isNotEmpty() && !installed.contains(pkg)) continue
                adb.executeCommand("pm enable $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            finish(cb, true)
        } catch (e: AdbException) {
            Log.w(TAG, "restore failed: ${e.message}")
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
            Log.w(TAG, "reboot failed: ${e.message}")
            false
        } finally {
            adb.disconnect()
        }
    }

    private fun finish(cb: Callbacks, ok: Boolean): Boolean {
        cb.onProgress(if (ok) 1f else 0.9f)
        return ok
    }

    private suspend fun tryConnect(): Boolean {
        var delayMs = AppConstants.RETRY_DELAY_MS
        repeat(AppConstants.ADB_CONNECT_ATTEMPTS) { attempt ->
            if (adb.connect()) return true
            if (attempt < AppConstants.ADB_CONNECT_ATTEMPTS - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(AppConstants.RETRY_DELAY_MS * 8)
            }
        }
        return false
    }

    private suspend fun targetPackages(): List<String> {
        val installed = installedPackages()
        return if (installed.isEmpty()) ServiceRegistry.PACKAGES
        else ServiceRegistry.PACKAGES.filter { installed.contains(it) }
    }

    private suspend fun installedPackages(): Set<String> {
        val raw = runCatching { adb.executeCommand("pm list packages") }.getOrDefault("")
        return raw.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toSet()
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

    private suspend fun disablePackages(targets: List<String>) {
        val disabled = runCatching { adb.executeCommand("pm list packages -d") }.getOrDefault("")
        for (pkg in targets) {
            if (disabled.contains(pkg)) continue
            val result = runCatching { adb.executeCommand("pm disable-user --user 0 $pkg") }
                .getOrDefault("")
            if (!looksSuccess(result)) {
                delay(300)
                runCatching { adb.executeCommand("pm disable --user 0 $pkg") }
            }
            delay(AppConstants.COMMAND_DELAY_MS)
        }
    }

    private suspend fun disablePackagesFallback(targets: List<String>) {
        val disabled = runCatching { adb.executeCommand("pm list packages -d") }.getOrDefault("")
        for (pkg in targets) {
            if (disabled.contains(pkg)) continue
            runCatching { adb.executeCommand("pm clear $pkg") }
            delay(AppConstants.COMMAND_DELAY_MS)
            runCatching { adb.executeCommand("pm disable-user --user 0 $pkg") }
            delay(AppConstants.COMMAND_DELAY_MS)
        }
    }

    private fun looksSuccess(result: String): Boolean {
        val lower = result.lowercase()
        return lower.contains("success") || lower.contains("disabled") || lower.contains("new state")
    }

    private suspend fun verifyAll(targets: List<String>): Boolean {
        return try {
            val disabled = adb.executeCommand("pm list packages -d")
            val missing = targets.filter { !disabled.contains(it) }
            val keyOff = adb.executeCommand("settings get secure miui_ad_filtering_enabled")
                .trim() == "0"
            if (missing.isNotEmpty()) Log.w(TAG, "still enabled: $missing")
            missing.isEmpty() || keyOff
        } catch (e: AdbException) {
            Log.w(TAG, "verify failed: ${e.message}")
            false
        }
    }
}