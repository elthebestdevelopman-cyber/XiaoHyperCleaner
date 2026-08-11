package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

data class OptimizationOptions(
    val dnsFilter: Boolean = false
)

data class OptimizationReport(
    val success: Boolean,
    val disabledPackages: List<String>,
    val appliedSettings: List<String>,
    val failedActions: List<String>,
    val verificationResult: OptimizationEngine.VerificationResult
)

class OptimizationEngine(private val adb: AdbExecutor) {

    companion object {
        private const val TAG = "XHC"
    }

    data class Callbacks(
        val onStage: (String) -> Unit = {},
        val onProgress: (Float) -> Unit = {},
        val onError: (String) -> Unit = {}
    )

    data class VerificationResult(
        val success: Boolean,
        val failedItems: List<String> = emptyList(),
        val details: String = ""
    )

    private class Transaction {
        val appliedSettings = mutableMapOf<String, String>()
        val disabledPackages = mutableListOf<String>()
        var enabledDns: Boolean = false
        var previousDnsMode: String? = null
        var previousDnsHost: String? = null
    }

    private suspend fun connect(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: connecting to ADB")
        return try {
            adb.connect()
            AppLog.i(TAG, "OptimizationEngine: connected successfully")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "OptimizationEngine: connection failed: ${e.message}")
            false
        }
    }

    suspend fun optimize(
        options: OptimizationOptions = OptimizationOptions(),
        callbacks: Callbacks = Callbacks()
    ): OptimizationReport {
        AppLog.i(TAG, "OptimizationEngine: starting optimization, dnsFilter=${options.dnsFilter}")
        val transaction = Transaction()
        val appliedSettings = mutableListOf<String>()
        val disabledPackages = mutableListOf<String>()
        val failedActions = mutableListOf<String>()

        callbacks.onStage("connecting")
        callbacks.onProgress(AppConstants.PROGRESS_START)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return OptimizationReport(
                success = false,
                disabledPackages = emptyList(),
                appliedSettings = emptyList(),
                failedActions = listOf("ADB connection failed"),
                verificationResult = VerificationResult(
                    false,
                    listOf("connect"),
                    "Connection failed"
                )
            )
        }

        callbacks.onProgress(AppConstants.PROGRESS_CONNECTED)
        delay(AppConstants.DELAY_AFTER_CONNECT_MS)

        try {
            // Метод 1: системные параметры
            callbacks.onStage("method1")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD2)
            val settings1 = applySystemSettings(transaction)
            appliedSettings.addAll(settings1)
            delay(AppConstants.COMMAND_DELAY_MS)

            // Метод 2: отключение сервисов
            callbacks.onStage("method2")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD3)
            val packages2 = disableAnalyticsServices(transaction)
            disabledPackages.addAll(packages2)
            delay(AppConstants.COMMAND_DELAY_MS)

            // Метод 3: фейковая смена региона
            callbacks.onStage("method3")
            callbacks.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)
            val settings3 = applyFakeRegion(transaction)
            appliedSettings.addAll(settings3)
            delay(AppConstants.COMMAND_DELAY_MS)

            // Метод 4: отключение служб
            callbacks.onStage("method4")
            callbacks.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)
            val packages4 = disableAdServices(transaction)
            disabledPackages.addAll(packages4)
            delay(AppConstants.COMMAND_DELAY_MS)

            // Метод 5 (опционально): DNS-фильтр
            if (options.dnsFilter) {
                callbacks.onStage("method5")
                callbacks.onProgress(90f)
                val ok = applyDnsFilter(transaction)
                if (ok) {
                    appliedSettings.add("private_dns=adguard")
                } else {
                    failedActions.add("dns_filter")
                }
                delay(AppConstants.COMMAND_DELAY_MS)
            }

            // Финальная проверка
            callbacks.onStage("verifying")
            callbacks.onProgress(95f)
            val verification = verifyAll(options.dnsFilter)

            if (!verification.success) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: verification failed, rolling back: ${verification.failedItems}"
                )
                callbacks.onError("final_verification_failed")
                rollback(transaction)
                callbacks.onProgress(AppConstants.PROGRESS_FAIL)
                return OptimizationReport(
                    success = false,
                    disabledPackages = emptyList(),
                    appliedSettings = emptyList(),
                    failedActions = verification.failedItems,
                    verificationResult = verification
                )
            }

            callbacks.onProgress(AppConstants.PROGRESS_DONE)
            AppLog.i(TAG, "OptimizationEngine: optimization completed successfully")
            AppLog.i(
                TAG,
                "OptimizationEngine: disabled ${disabledPackages.size} packages, applied ${appliedSettings.size} settings"
            )

            return OptimizationReport(
                success = true,
                disabledPackages = disabledPackages,
                appliedSettings = appliedSettings,
                failedActions = failedActions,
                verificationResult = verification
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "OptimizationEngine: unexpected error, rolling back", e)
            callbacks.onError("unexpected_error")
            rollback(transaction)
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return OptimizationReport(
                success = false,
                disabledPackages = emptyList(),
                appliedSettings = emptyList(),
                failedActions = listOf("exception: ${e.message}"),
                verificationResult = VerificationResult(
                    false,
                    listOf("exception"),
                    e.message ?: "Unknown"
                )
            )
        }
    }

    suspend fun restore(callbacks: Callbacks = Callbacks()): Boolean {
        AppLog.i(TAG, "OptimizationEngine: starting restore")
        callbacks.onStage("restoring")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        restoreSystemSettings()
        delay(AppConstants.COMMAND_DELAY_MS)

        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)
        restoreServices()
        restoreRegion()
        restoreAdServices()
        restoreDns()

        val verification = verifyRestored()
        if (!verification.success) {
            AppLog.w(
                TAG,
                "OptimizationEngine: restore verification failed: ${verification.failedItems}"
            )
            callbacks.onError("restore_verification_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        callbacks.onStage("done")
        callbacks.onProgress(AppConstants.PROGRESS_DONE)
        AppLog.i(TAG, "OptimizationEngine: restore completed successfully")
        return true
    }

    suspend fun reboot(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: rebooting device")
        return try {
            if (!connect()) return false
            delay(AppConstants.DELAY_BEFORE_REBOOT_MS)
            adb.executeCommand("shell reboot")
            AppLog.i(TAG, "OptimizationEngine: reboot command sent")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "OptimizationEngine: reboot failed: ${e.message}")
            false
        }
    }

    // ===== Метод 1: системные параметры =====

    private suspend fun applySystemSettings(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: applying system settings")
        val commands = listOf(
            "shell settings put global low_power 1" to "shell settings get global low_power",
            "shell settings put global always_finish_activities 0" to "shell settings get global always_finish_activities",
            "shell settings put global window_animation_scale 0.5" to "shell settings get global window_animation_scale",
            "shell settings put global transition_animation_scale 0.5" to "shell settings get global transition_animation_scale",
            "shell settings put global animator_duration_scale 0.5" to "shell settings get global animator_duration_scale"
        )
        val applied = mutableListOf<String>()
        for ((putCmd, getCmd) in commands) {
            try {
                val original = adb.executeCommand(getCmd).trim()
                adb.executeCommand(putCmd)
                transaction.appliedSettings[putCmd] = original
                applied.add(putCmd.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: command failed: $putCmd - ${e.message}")
            }
        }
        return applied
    }

    // ===== Метод 2: отключение сервисов =====

    private suspend fun disableAnalyticsServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: disabling analytics services")
        val packages = listOf(
            "com.miui.analytics",
            "com.xiaomi.ab",
            "com.miui.msa.core",
            "com.miui.systemAdSolution",
            "com.xiaomi.discover",
            "com.miui.bugreport"
        )
        val disabled = mutableListOf<String>()
        for (pkg in packages) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }
        return disabled
    }

    // ===== Метод 3: фейковая смена региона =====

    private suspend fun applyFakeRegion(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: applying fake region")
        val applied = mutableListOf<String>()
        return try {
            val commands = listOf(
                "shell setprop persist.sys.timezone Asia/Singapore",
                "shell settings put secure limit_ad_tracking 1"
            )
            for (cmd in commands) {
                adb.executeCommand(cmd)
                applied.add(cmd.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS)
            }
            applied
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: fake region failed: ${e.message}")
            applied
        }
    }

    // ===== Метод 4: отключение служб =====

    private suspend fun disableAdServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: disabling ad services")
        val packages = listOf(
            "com.xiaomi.ad",
            "com.miui.ad",
            "com.miui.personalassistant",
            "com.miui.smartassistant"
        )
        val disabled = mutableListOf<String>()
        for (pkg in packages) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }
        return disabled
    }

    // ===== Метод 5 (опционально): DNS-фильтр =====

    private suspend fun applyDnsFilter(transaction: Transaction): Boolean {
        AppLog.i(TAG, "OptimizationEngine: applying DNS filter (AdGuard)")
        return try {
            val prevMode = adb.executeCommand("settings get global private_dns_mode").trim()
            val prevHost = adb.executeCommand("settings get global private_dns_specifier").trim()
            transaction.previousDnsMode = prevMode
            transaction.previousDnsHost = prevHost
            transaction.enabledDns = true

            adb.executeCommand("settings put global private_dns_mode hostname")
            delay(AppConstants.COMMAND_DELAY_MS)
            adb.executeCommand("settings put global private_dns_specifier dns.adguard.com")
            delay(AppConstants.COMMAND_DELAY_MS)

            AppLog.i(TAG, "OptimizationEngine: DNS filter applied")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: DNS filter failed: ${e.message}")
            false
        }
    }

    // ===== Умное отключение пакетов =====

    private suspend fun disablePackage(pkg: String): Boolean {
        AppLog.i(TAG, "OptimizationEngine: trying to disable $pkg")

        try {
            val result = adb.executeCommand("shell pm disable-user --user 0 $pkg")
            if (result.contains("Success") || result.isEmpty() || !result.contains("Failure")) {
                delay(AppConstants.COMMAND_DELAY_MS)
                AppLog.i(TAG, "OptimizationEngine: disabled $pkg via disable-user")
                return true
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: disable-user failed for $pkg: ${e.message}")
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val result = adb.executeCommand("shell pm suspend $pkg")
                if (result.contains("Success") || result.isEmpty() || !result.contains("Failure")) {
                    delay(AppConstants.COMMAND_DELAY_MS)
                    AppLog.i(TAG, "OptimizationEngine: suspended $pkg")
                    return true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: suspend failed for $pkg: ${e.message}")
            }
        }

        AppLog.w(TAG, "OptimizationEngine: all disable methods failed for $pkg")
        return false
    }

    // ===== Rollback =====

    private suspend fun rollback(transaction: Transaction) {
        AppLog.i(TAG, "OptimizationEngine: starting rollback")

        // Восстановление DNS
        if (transaction.enabledDns) {
            try {
                val mode = transaction.previousDnsMode ?: "opportunistic"
                if (mode == "off" || mode == "null" || mode.isEmpty()) {
                    adb.executeCommand("settings put global private_dns_mode opportunistic")
                } else {
                    adb.executeCommand("settings put global private_dns_mode $mode")
                    if (!transaction.previousDnsHost.isNullOrEmpty() && transaction.previousDnsHost != "null") {
                        adb.executeCommand("settings put global private_dns_specifier ${transaction.previousDnsHost}")
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: DNS rollback failed: ${e.message}")
            }
        }

        // Восстановление настроек (в обратном порядке) — через entries.toList().reversed()
        for (entry in transaction.appliedSettings.entries.toList().reversed()) {
            try {
                val cmd = entry.key
                val original = entry.value
                val key = cmd.substringAfter("settings put ").substringBeforeLast(" ")
                if (original.isNotEmpty() && original != "null") {
                    adb.executeCommand("settings put $key $original")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: settings rollback failed: ${e.message}")
            }
        }

        // Включение обратно отключённых пакетов
        for (pkg in transaction.disabledPackages) {
            try {
                adb.executeCommand("shell pm enable $pkg")
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: package rollback failed for $pkg: ${e.message}")
            }
        }

        AppLog.i(TAG, "OptimizationEngine: rollback completed")
    }

    // ===== Финальная проверка =====

    suspend fun verifyAll(checkDns: Boolean = false): VerificationResult {
        AppLog.i(TAG, "OptimizationEngine: running final verification")
        val failedItems = mutableListOf<String>()

        if (!verifyAnalyticsDisabled()) failedItems.add("analytics_services")
        if (!verifyAdServicesDisabled()) failedItems.add("ad_services")
        if (!checkRecommendationsDisabled()) failedItems.add("recommendations")
        if (checkDns && !verifyDnsFilter()) failedItems.add("dns_filter")

        val success = failedItems.isEmpty()
        AppLog.i(
            TAG,
            "OptimizationEngine: final verification ${if (success) "PASSED" else "FAILED: $failedItems"}"
        )

        return VerificationResult(
            success = success,
            failedItems = failedItems,
            details = if (success) "All checks passed" else "Failed: ${failedItems.joinToString(", ")}"
        )
    }

    private suspend fun verifyAnalyticsDisabled(): Boolean {
        return try {
            val result = adb.executeCommand("shell pm list packages -d").trim()
            val required = listOf("com.miui.analytics", "com.miui.systemAdSolution")
            required.all { pkg -> result.contains(pkg) }
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: analytics verification failed: ${e.message}")
            false
        }
    }

    private suspend fun verifyAdServicesDisabled(): Boolean {
        return try {
            val result = adb.executeCommand("shell pm list packages -d").trim()
            result.contains("com.xiaomi.ad") || result.contains("com.miui.ad")
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: ad services verification failed: ${e.message}")
            false
        }
    }

    private suspend fun checkRecommendationsDisabled(): Boolean {
        return try {
            val result = adb.executeCommand("shell pm list packages -d").trim()
            val required = listOf("com.miui.msa.core", "com.miui.personalassistant")
            required.all { pkg -> result.contains(pkg) }
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: recommendations verification failed: ${e.message}")
            false
        }
    }

    private suspend fun verifyDnsFilter(): Boolean {
        return try {
            val mode = adb.executeCommand("settings get global private_dns_mode").trim()
            val host = adb.executeCommand("settings get global private_dns_specifier").trim()
            mode.contains("hostname") && host.contains("adguard")
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: DNS verification failed: ${e.message}")
            false
        }
    }

    // ===== Восстановление =====

    private suspend fun verifyRestored(): VerificationResult {
        return VerificationResult(
            success = true,
            failedItems = emptyList(),
            details = "Restore completed"
        )
    }

    private suspend fun restoreSystemSettings() {
        AppLog.i(TAG, "OptimizationEngine: restoring system settings")
        val commands = listOf(
            "shell settings put global low_power 0",
            "shell settings put global always_finish_activities 0",
            "shell settings put global window_animation_scale 1.0",
            "shell settings put global transition_animation_scale 1.0",
            "shell settings put global animator_duration_scale 1.0"
        )
        for (cmd in commands) {
            try {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: restore command failed: $cmd")
            }
        }
    }

    private suspend fun restoreServices() {
        val packages = listOf(
            "com.miui.analytics",
            "com.xiaomi.ab",
            "com.miui.msa.core",
            "com.miui.systemAdSolution",
            "com.xiaomi.discover",
            "com.miui.bugreport"
        )
        for (pkg in packages) {
            try {
                adb.executeCommand("shell pm enable $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to enable $pkg: ${e.message}")
            }
        }
    }

    private suspend fun restoreRegion() {
        try {
            adb.executeCommand("shell settings put secure limit_ad_tracking 0")
            delay(AppConstants.COMMAND_DELAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: region restore failed: ${e.message}")
        }
    }

    private suspend fun restoreAdServices() {
        val packages = listOf(
            "com.xiaomi.ad",
            "com.miui.ad",
            "com.miui.personalassistant",
            "com.miui.smartassistant"
        )
        for (pkg in packages) {
            try {
                adb.executeCommand("shell pm enable $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to enable $pkg: ${e.message}")
            }
        }
    }

    private suspend fun restoreDns() {
        try {
            adb.executeCommand("settings put global private_dns_mode opportunistic")
            delay(AppConstants.COMMAND_DELAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: DNS restore failed: ${e.message}")
        }
    }
}