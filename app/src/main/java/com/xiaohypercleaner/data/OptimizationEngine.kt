package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.delay
import java.io.IOException

data class OptimizationOptions(
    val dnsFilter: Boolean = false,
    val aggressiveMode: Boolean = false
)

data class OptimizationReport(
    val success: Boolean,
    val disabledPackages: List<String>,
    val appliedSettings: List<String>,
    val failedActions: List<String>,
    val verificationResult: OptimizationEngine.VerificationResult,
    val rollbackReport: OptimizationEngine.RollbackReport? = null
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

    data class RollbackReport(
        val restoredSettings: Int,
        val restoredPackages: Int,
        val restoredDns: Boolean,
        val failedSettings: List<String>,
        val failedPackages: List<Pair<String, String>>,
        val failedDns: String?
    ) {
        val totalFailed: Int
            get() = failedSettings.size + failedPackages.size + (if (failedDns != null) 1 else 0)

        fun summary(): String = buildString {
            append("🔄 Откат: ")
            append("настройки=${restoredSettings}, ")
            append("пакеты=${restoredPackages}")
            if (restoredDns) append(", DNS=да")
            if (totalFailed > 0) {
                append("\n⚠️ Не удалось откатить: $totalFailed")
                if (failedSettings.isNotEmpty()) append("\n  • настройки: ${failedSettings.joinToString()}")
                if (failedPackages.isNotEmpty()) append("\n  • пакеты: ${failedPackages.joinToString { "${it.first} (${it.second})" }}")
                if (failedDns != null) append("\n  • DNS: $failedDns")
            }
        }
    }

    private class Transaction {
        val appliedSettings = mutableMapOf<String, String>()
        val disabledPackages = mutableListOf<String>()
        var enabledDns: Boolean = false
        var previousDnsMode: String? = null
        var previousDnsHost: String? = null
        var originalRegion: String? = null
    }

    private suspend fun connect(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: connecting to ADB")
        return try {
            adb.connect()
            AppLog.i(TAG, "OptimizationEngine: connected successfully")
            true
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "OptimizationEngine: connection failed: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    suspend fun optimize(
        options: OptimizationOptions = OptimizationOptions(),
        callbacks: Callbacks = Callbacks()
    ): OptimizationReport {
        AppLog.i(
            TAG,
            "OptimizationEngine: starting optimization, dnsFilter=${options.dnsFilter}, aggressive=${options.aggressiveMode}"
        )
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
            callbacks.onStage("method1")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD1)
            val settings1 = applySystemSettings(transaction)
            appliedSettings.addAll(settings1)
            delay(AppConstants.COMMAND_DELAY_MS)

            callbacks.onStage("method2")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD2)
            val packages2 = disableAnalyticsServices(transaction)
            disabledPackages.addAll(packages2)
            delay(AppConstants.COMMAND_DELAY_MS)

            callbacks.onStage("method3")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD3)
            val settings3 = applyHiddenKeys(transaction)
            appliedSettings.addAll(settings3)

            if (options.aggressiveMode) {
                val regional = applyRegionalKeys(transaction)
                appliedSettings.addAll(regional)
            }
            delay(AppConstants.COMMAND_DELAY_MS)

            callbacks.onStage("method4")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD4)
            val packages4 = disableAdServices(transaction)
            disabledPackages.addAll(packages4)
            delay(AppConstants.COMMAND_DELAY_MS)

            if (options.dnsFilter) {
                callbacks.onStage("method5")
                callbacks.onProgress(AppConstants.PROGRESS_METHOD5_DNS)
                val ok = applyDnsFilter(transaction)
                if (ok) {
                    appliedSettings.add("private_dns=adguard")
                } else {
                    failedActions.add("dns_filter")
                }
                delay(AppConstants.COMMAND_DELAY_MS)
            }

            callbacks.onStage("verifying")
            callbacks.onProgress(AppConstants.PROGRESS_VERIFYING)
            val verification = verifyAll(options.dnsFilter)

            if (!verification.success) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: verification failed, rolling back: ${verification.failedItems}"
                )
                callbacks.onError("final_verification_failed")
                val rollbackReport = rollback(transaction)

                if (rollbackReport.totalFailed > 0) {
                    failedActions.add("rollback_failed: ${rollbackReport.totalFailed}")
                }

                callbacks.onProgress(AppConstants.PROGRESS_FAIL)
                return OptimizationReport(
                    success = false,
                    disabledPackages = emptyList(),
                    appliedSettings = emptyList(),
                    failedActions = verification.failedItems + failedActions,
                    verificationResult = verification,
                    rollbackReport = rollbackReport
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
                verificationResult = verification,
                rollbackReport = null
            )
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "OptimizationEngine: unexpected error, rolling back: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            callbacks.onError("unexpected_error")
            val rollbackReport = rollback(transaction)

            val exceptionFailedActions = mutableListOf("exception: ${e.message}")
            if (rollbackReport.totalFailed > 0) {
                exceptionFailedActions.add("rollback_failed: ${rollbackReport.totalFailed}")
            }

            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return OptimizationReport(
                success = false,
                disabledPackages = emptyList(),
                appliedSettings = emptyList(),
                failedActions = exceptionFailedActions,
                verificationResult = VerificationResult(
                    false,
                    listOf("exception"),
                    e.message ?: "Unknown"
                ),
                rollbackReport = rollbackReport
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

        val failedActions = mutableListOf<String>()

        val settingsFailed = restoreSystemSettingsWithReport()
        if (settingsFailed.isNotEmpty()) {
            failedActions.add("settings_restore: ${settingsFailed.joinToString()}")
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)

        val servicesFailed = restoreServicesWithReport()
        if (servicesFailed.isNotEmpty()) {
            failedActions.add("services_restore: ${servicesFailed.joinToString()}")
        }

        val hiddenFailed = restoreHiddenKeysWithReport()
        if (hiddenFailed.isNotEmpty()) {
            failedActions.add("hidden_keys_restore: ${hiddenFailed.joinToString()}")
        }

        val dnsFailed = restoreDnsWithReport()
        if (dnsFailed != null) {
            failedActions.add("dns_restore: $dnsFailed")
        }

        if (failedActions.isNotEmpty()) {
            AppLog.w(TAG, "OptimizationEngine: restore had failures: $failedActions")
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
            val result = adb.executeCommand("shell reboot")
            if (result.isSuccess) {
                AppLog.i(TAG, "OptimizationEngine: reboot command sent")
                true
            } else {
                AppLog.e(
                    TAG,
                    "OptimizationEngine: reboot failed: ${result.exceptionOrNull()?.message}"
                )
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "OptimizationEngine: reboot failed: ${LogMasker.mask(e.message ?: "")}")
            false
        }
    }

    private suspend fun applySystemSettings(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: applying system settings")
        val applied = mutableListOf<String>()

        for ((key, value) in ServiceRegistry.SYSTEM_SETTINGS) {
            try {
                val getCmd = "shell settings get $key"
                val putCmd = "shell settings put $key $value"
                val original = adb.executeCommand(getCmd).getOrNull()?.trim() ?: ""

                val putResult = adb.executeCommand(putCmd)
                if (putResult.isFailure) {
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: put command failed: $key - ${putResult.exceptionOrNull()?.message}"
                    )
                    continue
                }

                transaction.appliedSettings[putCmd] = original
                applied.add(key.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: command failed: $key - ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }
        return applied
    }

    private suspend fun disableAnalyticsServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: disabling analytics services")
        val disabled = mutableListOf<String>()
        for (pkg in ServiceRegistry.ANALYTICS_PACKAGES) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }
        return disabled
    }

    private suspend fun applyHiddenKeys(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: applying hidden keys")
        val applied = mutableListOf<String>()

        for ((key, value) in ServiceRegistry.HIDDEN_KEYS_DISABLE) {
            try {
                val getCmd = "shell settings get $key"
                val putCmd = "shell settings put $key $value"
                val original = adb.executeCommand(getCmd).getOrNull()?.trim() ?: ""

                val putResult = adb.executeCommand(putCmd)
                if (putResult.isFailure) {
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: hidden key put failed: $key - ${putResult.exceptionOrNull()?.message}"
                    )
                    continue
                }

                transaction.appliedSettings[putCmd] = original
                applied.add(key.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: hidden key failed: $key - ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }
        return applied
    }

    private suspend fun applyRegionalKeys(transaction: Transaction): List<String> {
        // УДАЛЕНО: Изменение региона удалено из-за риска нарушения работы системных сервисов
        // Эта функция теперь возвращает пустой список для обратной совместимости
        return emptyList()
    }

    private suspend fun disableAdServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "OptimizationEngine: disabling ad services")
        val disabled = mutableListOf<String>()
        for (pkg in ServiceRegistry.AD_SERVICES_PACKAGES) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }
        return disabled
    }

    private suspend fun applyDnsFilter(transaction: Transaction): Boolean {
        AppLog.i(TAG, "OptimizationEngine: applying DNS filter (AdGuard)")
        return try {
            val prevMode =
                adb.executeCommand("settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull()
                    ?.trim() ?: ""
            val prevHost =
                adb.executeCommand("settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}").getOrNull()
                    ?.trim() ?: ""
            transaction.previousDnsMode = prevMode
            transaction.previousDnsHost = prevHost
            transaction.enabledDns = true

            val modeResult =
                adb.executeCommand("settings put ${ServiceRegistry.Dns.MODE_KEY} ${ServiceRegistry.Dns.MODE_VALUE}")
            if (modeResult.isFailure) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: DNS mode put failed: ${modeResult.exceptionOrNull()?.message}"
                )
                return false
            }

            delay(AppConstants.COMMAND_DELAY_MS)

            val hostResult =
                adb.executeCommand("settings put ${ServiceRegistry.Dns.SPECIFIER_KEY} ${ServiceRegistry.Dns.SPECIFIER_VALUE}")
            if (hostResult.isFailure) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: DNS host put failed: ${hostResult.exceptionOrNull()?.message}"
                )
                return false
            }

            delay(AppConstants.COMMAND_DELAY_MS)

            AppLog.i(TAG, "OptimizationEngine: DNS filter applied")
            true
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: DNS filter failed: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    private suspend fun disablePackage(pkg: String): Boolean {
        AppLog.i(TAG, "OptimizationEngine: trying to disable $pkg")

        try {
            val result = adb.executeCommand("shell pm disable-user --user 0 $pkg").getOrNull() ?: ""
            if (result.contains("Success")) {
                delay(AppConstants.COMMAND_DELAY_MS)
                AppLog.i(TAG, "OptimizationEngine: disabled $pkg via disable-user")
                return true
            }
            AppLog.w(
                TAG,
                "OptimizationEngine: disable-user returned no Success for $pkg: ${
                    LogMasker.mask(result.take(200))
                }"
            )
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: disable-user failed for $pkg: ${LogMasker.mask(e.message ?: "")}"
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val result = adb.executeCommand("shell pm suspend $pkg").getOrNull() ?: ""
                if (result.contains("Success")) {
                    delay(AppConstants.COMMAND_DELAY_MS)
                    AppLog.i(TAG, "OptimizationEngine: suspended $pkg")
                    return true
                }
                AppLog.w(
                    TAG,
                    "OptimizationEngine: suspend returned no Success for $pkg: ${
                        LogMasker.mask(result.take(200))
                    }"
                )
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: suspend failed for $pkg: ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }

        AppLog.w(TAG, "OptimizationEngine: all disable methods failed for $pkg")
        return false
    }

    private suspend fun rollback(transaction: Transaction): RollbackReport {
        AppLog.i(TAG, "OptimizationEngine: starting rollback")

        val failedSettings = mutableListOf<String>()
        val failedPackages = mutableListOf<Pair<String, String>>()
        var restoredSettings = 0
        var restoredPackages = 0
        var restoredDns = false
        var failedDns: String? = null

        if (transaction.enabledDns) {
            try {
                val mode = transaction.previousDnsMode ?: ServiceRegistry.Dns.RESTORE_MODE
                val modeCmd = if (mode == "off" || mode == "null" || mode.isEmpty()) {
                    "settings put ${ServiceRegistry.Dns.MODE_KEY} ${ServiceRegistry.Dns.RESTORE_MODE}"
                } else {
                    "settings put ${ServiceRegistry.Dns.MODE_KEY} $mode"
                }

                val modeResult = adb.executeCommand(modeCmd)
                if (modeResult.isFailure) {
                    failedDns = modeResult.exceptionOrNull()?.message ?: "Unknown"
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: DNS mode rollback failed: ${LogMasker.mask(failedDns)}"
                    )
                } else {
                    if (!transaction.previousDnsHost.isNullOrEmpty() && transaction.previousDnsHost != "null") {
                        val hostResult =
                            adb.executeCommand("settings put ${ServiceRegistry.Dns.SPECIFIER_KEY} ${transaction.previousDnsHost}")
                        if (hostResult.isFailure) {
                            failedDns = hostResult.exceptionOrNull()?.message ?: "Unknown"
                            AppLog.w(
                                TAG,
                                "OptimizationEngine: DNS host rollback failed: ${
                                    LogMasker.mask(failedDns)
                                }"
                            )
                        } else {
                            restoredDns = true
                        }
                    } else {
                        restoredDns = true
                    }
                }
            } catch (e: Exception) {
                failedDns = e.message ?: "Unknown"
                AppLog.w(
                    TAG,
                    "OptimizationEngine: DNS rollback failed: ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }

        for (entry in transaction.appliedSettings.entries.toList().reversed()) {
            try {
                val cmd = entry.key
                val original = entry.value
                val key = cmd.substringAfter("settings put ").substringBeforeLast(" ")

                val restoreCmd = if (original.isNotEmpty() && original != "null") {
                    "settings put $key $original"
                } else {
                    "settings put $key \"\"" // Пустое значение
                }

                val result = adb.executeCommand(restoreCmd)
                if (result.isSuccess) {
                    restoredSettings++
                } else {
                    val keyName = cmd.substringAfterLast(" ")
                    failedSettings.add(keyName)
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: settings rollback failed for $key: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                val keyName = entry.key.substringAfterLast(" ")
                failedSettings.add(keyName)
                AppLog.w(
                    TAG,
                    "OptimizationEngine: settings rollback failed: ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }

        for (pkg in transaction.disabledPackages) {
            try {
                val result = adb.executeCommand("shell pm enable $pkg")
                if (result.isSuccess) {
                    restoredPackages++
                } else {
                    failedPackages.add(pkg to (result.exceptionOrNull()?.message ?: "Unknown"))
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: package rollback failed for $pkg: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                failedPackages.add(pkg to (e.message ?: "Unknown"))
                AppLog.w(
                    TAG,
                    "OptimizationEngine: package rollback failed for $pkg: ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }

        val report = RollbackReport(
            restoredSettings = restoredSettings,
            restoredPackages = restoredPackages,
            restoredDns = restoredDns,
            failedSettings = failedSettings,
            failedPackages = failedPackages,
            failedDns = failedDns
        )

        AppLog.i(TAG, "OptimizationEngine: rollback completed. ${report.summary()}")
        return report
    }

    suspend fun verifyAll(checkDns: Boolean = false): VerificationResult {
        AppLog.i(TAG, "OptimizationEngine: running final verification")
        val failedItems = mutableListOf<String>()

        if (!verifyAnalyticsDisabled()) failedItems.add("analytics_services")
        if (!verifyAdServicesDisabled()) failedItems.add("ad_services")
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
            val result = adb.executeCommand("shell pm list packages -d").getOrNull() ?: ""
            ServiceRegistry.ANALYTICS_PACKAGES.take(2).any { pkg -> result.contains(pkg) }
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: analytics verification failed: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    private suspend fun verifyAdServicesDisabled(): Boolean {
        return try {
            val result = adb.executeCommand("shell pm list packages -d").getOrNull() ?: ""
            ServiceRegistry.AD_SERVICES_PACKAGES.take(2).any { pkg -> result.contains(pkg) }
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: ad services verification failed: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    private suspend fun verifyDnsFilter(): Boolean {
        return try {
            val mode =
                adb.executeCommand("settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull() ?: ""
            val host =
                adb.executeCommand("settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}").getOrNull()
                    ?: ""
            mode.contains(ServiceRegistry.Dns.MODE_VALUE) && host.contains("adguard")
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: DNS verification failed: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    private suspend fun restoreSystemSettingsWithReport(): List<String> {
        AppLog.i(TAG, "OptimizationEngine: restoring system settings")
        val failed = mutableListOf<String>()

        for (key in ServiceRegistry.SYSTEM_SETTINGS.keys) {
            try {
                val restoreValue = when {
                    key.contains("low_power") -> "0"
                    key.contains("always_finish") -> "0"
                    key.contains("animation_scale") -> "1.0"
                    else -> "1"
                }
                val result = adb.executeCommand("shell settings put $key $restoreValue")
                if (result.isFailure) {
                    failed.add(key.substringAfterLast(" "))
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: restore command failed: $key - ${result.exceptionOrNull()?.message}"
                    )
                }
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                failed.add(key.substringAfterLast(" "))
                AppLog.w(TAG, "OptimizationEngine: restore command failed: $key")
            }
        }
        return failed
    }

    private suspend fun restoreServicesWithReport(): List<String> {
        val failed = mutableListOf<String>()
        for (pkg in ServiceRegistry.ANALYTICS_PACKAGES + ServiceRegistry.AD_SERVICES_PACKAGES) {
            try {
                val result = adb.executeCommand("shell pm enable $pkg")
                if (result.isFailure) {
                    failed.add(pkg)
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: failed to enable $pkg: ${result.exceptionOrNull()?.message}"
                    )
                }
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                failed.add(pkg)
                AppLog.w(
                    TAG,
                    "OptimizationEngine: failed to enable $pkg: ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }
        return failed
    }

    private suspend fun restoreHiddenKeysWithReport(): List<String> {
        AppLog.i(TAG, "OptimizationEngine: restoring hidden keys")
        val failed = mutableListOf<String>()

        for ((key, value) in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
            try {
                val result = adb.executeCommand("shell settings put $key $value")
                if (result.isFailure) {
                    failed.add(key.substringAfterLast(" "))
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: hidden key restore failed: $key - ${result.exceptionOrNull()?.message}"
                    )
                }
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                failed.add(key.substringAfterLast(" "))
                AppLog.w(
                    TAG,
                    "OptimizationEngine: hidden key restore failed: $key - ${LogMasker.mask(e.message ?: "")}"
                )
            }
        }
        return failed
    }

    private suspend fun restoreDnsWithReport(): String? {
        return try {
            val result =
                adb.executeCommand("settings put ${ServiceRegistry.Dns.MODE_KEY} ${ServiceRegistry.Dns.RESTORE_MODE}")
            if (result.isFailure) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: DNS restore failed: ${result.exceptionOrNull()?.message}"
                )
                return result.exceptionOrNull()?.message ?: "Unknown"
            }
            delay(AppConstants.COMMAND_DELAY_MS)
            null
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "OptimizationEngine: DNS restore failed: ${LogMasker.mask(e.message ?: "")}"
            )
            e.message ?: "Unknown"
        }
    }
}