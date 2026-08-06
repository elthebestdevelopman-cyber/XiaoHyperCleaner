package com.xiaohypercleaner.data

import kotlinx.coroutines.delay

class OptimizationEngine(private val adb: AdbClient) {

    suspend fun optimize(onStage: (String) -> Unit = {}): Boolean {
        onStage("connecting")
        if (!tryConnect()) return false

        onStage("method1")
        applyHiddenKeys()
        if (verifyAll()) return true

        onStage("method2")
        disablePackages()
        if (verifyAll()) return true

        onStage("method3")
        applyHiddenKeys()
        disablePackages()
        if (verifyAll()) return true

        onStage("verifying")
        delay(1000)
        disablePackagesFallback()
        return verifyAll()
    }

    suspend fun restore(onStage: (String) -> Unit = {}): Boolean {
        onStage("connecting")
        if (!tryConnect()) return false

        onStage("restoring_keys")
        for (cmd in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
            adb.executeCommand(cmd)
            delay(50)
        }

        onStage("restoring_packages")
        for (pkg in ServiceRegistry.PACKAGES) {
            adb.executeCommand("pm enable $pkg")
            delay(50)
        }
        return true
    }

    private suspend fun tryConnect(): Boolean {
        repeat(3) { attempt ->
            if (adb.connect()) return true
            delay(800 + attempt * 400L)
        }
        return false
    }

    private suspend fun applyHiddenKeys() {
        repeat(2) { attempt ->
            for (cmd in ServiceRegistry.HIDDEN_KEYS_DISABLE) {
                adb.executeCommand(cmd)
                delay(80)
            }
            val v = adb.executeCommand("settings get secure miui_ad_filtering_enabled").trim()
            if (v == "0") return
            if (attempt < 1) delay(800)
        }
    }

    private suspend fun disablePackages() {
        for (pkg in ServiceRegistry.PACKAGES) {
            if (isPackageDisabled(pkg)) continue
            val result = adb.executeCommand("pm disable-user --user 0 $pkg")
            if (!looksSuccess(result)) {
                delay(300)
                adb.executeCommand("pm disable --user 0 $pkg")
            }
            delay(80)
        }
    }

    private suspend fun disablePackagesFallback() {
        for (pkg in ServiceRegistry.PACKAGES) {
            if (isPackageDisabled(pkg)) continue
            adb.executeCommand("pm clear $pkg")
            delay(50)
            adb.executeCommand("pm disable-user --user 0 $pkg")
            delay(80)
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
        val anyPackageOff = ServiceRegistry.PACKAGES.any { disabled.contains(it) }
        val keyOff =
            adb.executeCommand("settings get secure miui_ad_filtering_enabled").trim() == "0"
        return anyPackageOff || keyOff
    }
}