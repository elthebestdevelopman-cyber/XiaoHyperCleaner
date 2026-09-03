package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import com.xiaohypercleaner.util.OptimizationNotifier
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * Движок оптимизации Pro-режима.
 *
 * Отвечает за:
 *  - Выполнение ADB-команд для отключения аналитики
 *  - Транзакционный откат при ошибках
 *  - Верификацию результатов
 *  - Интеграцию с UI через OptimizationNotifier
 *
 * УЛУЧШЕНИЯ:
 *  - Интеграция с OptimizationNotifier — UI получает обновления
 *  - Русские логи для соответствия правилу 1
 *  - TAG "OptimizationEngine" вместо "XHC"
 *  - Константы для магических чисел
 *  - Защита от пустых списков ServiceRegistry
 *  - Улучшенная обработка ошибок
 */
class OptimizationEngine(private val adb: AdbExecutor) {

    companion object {
        private const val TAG = "OptimizationEngine"

        // Безопасные значения по умолчанию для restore()
        private const val DEFAULT_ANIMATION_SCALE = "1.0"
        private const val DEFAULT_DISABLED = "0"
        private const val DEFAULT_ENABLED = "1"
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
        AppLog.i(TAG, "Подключение к ADB")
        return try {
            val success = adb.connect()
            if (success) {
                AppLog.i(TAG, "ADB подключение успешно")
            } else {
                AppLog.w(TAG, "ADB подключение не удалось")
            }
            success
        } catch (e: Exception) {
            AppLog.e(TAG, "Ошибка подключения к ADB: ${LogMasker.mask(e.message ?: "")}")
            false
        }
    }

    suspend fun optimize(
        options: OptimizationOptions = OptimizationOptions(),
        callbacks: Callbacks = Callbacks()
    ): OptimizationReport {
        AppLog.i(
            TAG,
            "Запуск оптимизации: dnsFilter=${options.dnsFilter}, aggressive=${options.aggressiveMode}"
        )

        OptimizationNotifier.setRunning()
        val transaction = Transaction()
        val appliedSettings = mutableListOf<String>()
        val disabledPackages = mutableListOf<String>()
        val failedActions = mutableListOf<String>()

        callbacks.onStage("connecting")
        callbacks.onProgress(AppConstants.PROGRESS_START)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            OptimizationNotifier.setFailure(
                listOf("ADB connection failed"),
                "Не удалось подключиться к ADB"
            )
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
        delay(AppConstants.DELAY_AFTER_CONNECT_MS.milliseconds)

        try {
            callbacks.onStage("method1")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD1)
            val settings1 = applySystemSettings(transaction)
            appliedSettings.addAll(settings1)
            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

            callbacks.onStage("method2")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD2)
            val packages2 = disableAnalyticsServices(transaction)
            disabledPackages.addAll(packages2)
            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

            callbacks.onStage("method3")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD3)
            val settings3 = applyHiddenKeys(transaction)
            appliedSettings.addAll(settings3)

            if (options.aggressiveMode) {
                val regional = applyRegionalKeys(transaction)
                appliedSettings.addAll(regional)
            }

            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

            callbacks.onStage("method4")
            callbacks.onProgress(AppConstants.PROGRESS_METHOD4)
            val packages4 = disableAdServices(transaction)
            disabledPackages.addAll(packages4)
            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

            if (options.dnsFilter) {
                callbacks.onStage("method5")
                callbacks.onProgress(AppConstants.PROGRESS_METHOD5_DNS)
                val ok = applyDnsFilter(transaction)
                if (ok) {
                    appliedSettings.add("private_dns=adguard")
                } else {
                    failedActions.add("dns_filter")
                }
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            }

            callbacks.onStage("verifying")
            callbacks.onProgress(AppConstants.PROGRESS_VERIFYING)

            val verification = verifyAll(options.dnsFilter)
            if (!verification.success) {
                AppLog.w(
                    TAG,
                    "Верификация не пройдена, выполняем откат: ${verification.failedItems}"
                )
                callbacks.onError("final_verification_failed")
                val rollbackReport = rollback(transaction)

                if (rollbackReport.totalFailed > 0) {
                    failedActions.add("rollback_failed: ${rollbackReport.totalFailed}")
                }

                callbacks.onProgress(AppConstants.PROGRESS_FAIL)
                OptimizationNotifier.setFailure(
                    verification.failedItems + failedActions,
                    "Верификация не пройдена, выполнен откат"
                )

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

            val successMessage =
                "Отключено ${disabledPackages.size} пакетов, применено ${appliedSettings.size} настроек"
            AppLog.i(TAG, "Оптимизация успешно завершена: $successMessage")
            OptimizationNotifier.setSuccess(successMessage)

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
                "Неожиданная ошибка, выполняем откат: ${LogMasker.mask(e.message ?: "")}",
                e
            )
            callbacks.onError("unexpected_error")
            val rollbackReport = rollback(transaction)

            val exceptionFailedActions = mutableListOf("exception: ${e.message}")
            if (rollbackReport.totalFailed > 0) {
                exceptionFailedActions.add("rollback_failed: ${rollbackReport.totalFailed}")
            }

            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            OptimizationNotifier.setFailure(
                exceptionFailedActions,
                "Неожиданная ошибка: ${e.message ?: "Unknown"}"
            )

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
        AppLog.i(TAG, "Запуск восстановления")
        OptimizationNotifier.setRunning()

        callbacks.onStage("restoring")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            OptimizationNotifier.setFailure(
                listOf("connect_failed"),
                "Не удалось подключиться для восстановления"
            )
            return false
        }

        val failedActions = mutableListOf<String>()

        val settingsFailed = restoreSystemSettingsWithReport()
        if (settingsFailed.isNotEmpty()) {
            failedActions.add("settings_restore: ${settingsFailed.joinToString()}")
        }
        delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

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
            AppLog.w(TAG, "Восстановление завершено с ошибками: $failedActions")
            callbacks.onError("restore_verification_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            OptimizationNotifier.setFailure(failedActions, "Восстановление завершено с ошибками")
            return false
        }

        callbacks.onStage("done")
        callbacks.onProgress(AppConstants.PROGRESS_DONE)
        AppLog.i(TAG, "Восстановление успешно завершено")
        OptimizationNotifier.setSuccess("Все настройки восстановлены")

        return true
    }

    suspend fun reboot(): Boolean {
        AppLog.i(TAG, "Перезагрузка устройства")
        return try {
            if (!connect()) return false
            delay(AppConstants.DELAY_BEFORE_REBOOT_MS.milliseconds)

            val result = adb.executeCommand("shell reboot")
            if (result.isSuccess) {
                AppLog.i(TAG, "Команда перезагрузки отправлена")
                true
            } else {
                AppLog.e(TAG, "Перезагрузка не удалась: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Ошибка перезагрузки: ${LogMasker.mask(e.message ?: "")}")
            false
        }
    }

    private suspend fun applySystemSettings(transaction: Transaction): List<String> {
        AppLog.i(TAG, "Применение системных настроек")
        val applied = mutableListOf<String>()

        if (ServiceRegistry.SYSTEM_SETTINGS.isEmpty()) {
            AppLog.w(TAG, "ServiceRegistry.SYSTEM_SETTINGS пуст, пропускаем")
            return applied
        }

        for ((key, value) in ServiceRegistry.SYSTEM_SETTINGS) {
            try {
                val getCmd = "shell settings get $key"
                val putCmd = "shell settings put $key $value"

                val original = adb.executeCommand(getCmd).getOrNull()?.trim() ?: ""
                val putResult = adb.executeCommand(putCmd)

                if (putResult.isFailure) {
                    AppLog.w(
                        TAG,
                        "Команда put не удалась: $key - ${putResult.exceptionOrNull()?.message}"
                    )
                    continue
                }

                transaction.appliedSettings[putCmd] = original
                applied.add(key.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                AppLog.w(TAG, "Команда не удалась: $key - ${LogMasker.mask(e.message ?: "")}")
            }
        }

        return applied
    }

    private suspend fun disableAnalyticsServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "Отключение сервисов аналитики")
        val disabled = mutableListOf<String>()

        if (ServiceRegistry.ANALYTICS_PACKAGES.isEmpty()) {
            AppLog.w(TAG, "ServiceRegistry.ANALYTICS_PACKAGES пуст, пропускаем")
            return disabled
        }

        for (pkg in ServiceRegistry.ANALYTICS_PACKAGES) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }

        return disabled
    }

    private suspend fun applyHiddenKeys(transaction: Transaction): List<String> {
        AppLog.i(TAG, "Применение скрытых ключей")
        val applied = mutableListOf<String>()

        if (ServiceRegistry.HIDDEN_KEYS_DISABLE.isEmpty()) {
            AppLog.w(TAG, "ServiceRegistry.HIDDEN_KEYS_DISABLE пуст, пропускаем")
            return applied
        }

        for ((key, value) in ServiceRegistry.HIDDEN_KEYS_DISABLE) {
            try {
                val getCmd = "shell settings get $key"
                val putCmd = "shell settings put $key $value"

                val original = adb.executeCommand(getCmd).getOrNull()?.trim() ?: ""
                val putResult = adb.executeCommand(putCmd)

                if (putResult.isFailure) {
                    AppLog.w(
                        TAG,
                        "Скрытый ключ put не удался: $key - ${putResult.exceptionOrNull()?.message}"
                    )
                    continue
                }

                transaction.appliedSettings[putCmd] = original
                applied.add(key.substringAfterLast(" "))
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                AppLog.w(TAG, "Скрытый ключ не удался: $key - ${LogMasker.mask(e.message ?: "")}")
            }
        }

        return applied
    }

    private fun applyRegionalKeys(transaction: Transaction): List<String> {
        // УДАЛЕНО: Изменение региона удалено из-за риска нарушения работы системных сервисов
        AppLog.i(TAG, "Региональные ключи пропущены (удалено из-за рисков)")
        return emptyList()
    }

    private suspend fun disableAdServices(transaction: Transaction): List<String> {
        AppLog.i(TAG, "Отключение рекламных сервисов")
        val disabled = mutableListOf<String>()

        if (ServiceRegistry.AD_SERVICES_PACKAGES.isEmpty()) {
            AppLog.w(TAG, "ServiceRegistry.AD_SERVICES_PACKAGES пуст, пропускаем")
            return disabled
        }

        for (pkg in ServiceRegistry.AD_SERVICES_PACKAGES) {
            if (disablePackage(pkg)) {
                transaction.disabledPackages.add(pkg)
                disabled.add(pkg)
            }
        }

        return disabled
    }

    private suspend fun applyDnsFilter(transaction: Transaction): Boolean {
        AppLog.i(TAG, "Применение DNS фильтра (AdGuard)")
        return try {
            val prevMode =
                adb.executeCommand("shell settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull()
                    ?.trim()
                    ?: adb.executeCommand("settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull()
                        ?.trim()
                    ?: ""
            val prevHost =
                adb.executeCommand("shell settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}")
                    .getOrNull()?.trim()
                    ?: adb.executeCommand("settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}")
                        .getOrNull()?.trim()
                    ?: ""

            transaction.previousDnsMode = prevMode
            transaction.previousDnsHost = prevHost
            transaction.enabledDns = true

            val modeResult =
                adb.executeCommand(
                    "shell settings put ${ServiceRegistry.Dns.MODE_KEY} ${ServiceRegistry.Dns.MODE_VALUE}"
                ).let { r ->
                    if (r.isSuccess) r
                    else adb.executeCommand(
                        "settings put ${ServiceRegistry.Dns.MODE_KEY} ${ServiceRegistry.Dns.MODE_VALUE}"
                    )
                }

            if (modeResult.isFailure) {
                AppLog.w(TAG, "DNS mode put не удался: ${modeResult.exceptionOrNull()?.message}")
                return false
            }

            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)

            val hostResult =
                adb.executeCommand(
                    "shell settings put ${ServiceRegistry.Dns.SPECIFIER_KEY} ${ServiceRegistry.Dns.SPECIFIER_VALUE}"
                ).let { r ->
                    if (r.isSuccess) r
                    else adb.executeCommand(
                        "settings put ${ServiceRegistry.Dns.SPECIFIER_KEY} ${ServiceRegistry.Dns.SPECIFIER_VALUE}"
                    )
                }

            if (hostResult.isFailure) {
                AppLog.w(TAG, "DNS host put не удался: ${hostResult.exceptionOrNull()?.message}")
                return false
            }

            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            AppLog.i(TAG, "DNS фильтр успешно применён")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "DNS фильтр не удался: ${LogMasker.mask(e.message ?: "")}")
            false
        }
    }

    private suspend fun disablePackage(pkg: String): Boolean {
        AppLog.i(TAG, "Попытка отключения пакета $pkg")

        // Пропускаем отсутствующие regional-пакеты — иначе verify/rollback ломаются шумом
        try {
            val path = adb.executeCommand("shell pm path $pkg").getOrNull().orEmpty()
            if (path.isBlank() || path.contains("Error") || path.contains("Exception")) {
                AppLog.i(TAG, "Пакет $pkg не установлен — skip")
                return false
            }
        } catch (_: Exception) {
            // продолжаем попытку disable
        }

        try {
            val result = adb.executeCommand("shell pm disable-user --user 0 $pkg").getOrNull() ?: ""
            if (result.contains("Success")) {
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
                AppLog.i(TAG, "Пакет $pkg отключён через disable-user")
                return true
            }
            AppLog.w(
                TAG,
                "disable-user не вернул Success для $pkg: ${LogMasker.mask(result.take(200))}"
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "disable-user не удался для $pkg: ${LogMasker.mask(e.message ?: "")}")
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val result = adb.executeCommand("shell pm suspend $pkg").getOrNull() ?: ""
                if (result.contains("Success")) {
                    delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
                    AppLog.i(TAG, "Пакет $pkg приостановлен")
                    return true
                }
                AppLog.w(
                    TAG,
                    "suspend не вернул Success для $pkg: ${LogMasker.mask(result.take(200))}"
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "suspend не удался для $pkg: ${LogMasker.mask(e.message ?: "")}")
            }
        }

        AppLog.w(TAG, "Все методы отключения не сработали для $pkg")
        return false
    }

    private suspend fun rollback(transaction: Transaction): RollbackReport {
        AppLog.i(TAG, "Запуск отката")

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
                    AppLog.w(TAG, "Откат DNS mode не удался: ${LogMasker.mask(failedDns)}")
                } else {
                    if (!transaction.previousDnsHost.isNullOrEmpty() && transaction.previousDnsHost != "null") {
                        val hostResult =
                            adb.executeCommand("settings put ${ServiceRegistry.Dns.SPECIFIER_KEY} ${transaction.previousDnsHost}")
                        if (hostResult.isFailure) {
                            failedDns = hostResult.exceptionOrNull()?.message ?: "Unknown"
                            AppLog.w(TAG, "Откат DNS host не удался: ${LogMasker.mask(failedDns)}")
                        } else {
                            restoredDns = true
                        }
                    } else {
                        restoredDns = true
                    }
                }
            } catch (e: Exception) {
                failedDns = e.message ?: "Unknown"
                AppLog.w(TAG, "Откат DNS не удался: ${LogMasker.mask(e.message ?: "")}")
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
                        "Откат настройки не удался для $key: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                val keyName = entry.key.substringAfterLast(" ")
                failedSettings.add(keyName)
                AppLog.w(TAG, "Откат настройки не удался: ${LogMasker.mask(e.message ?: "")}")
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
                        "Откат пакета не удался для $pkg: ${result.exceptionOrNull()?.message}"
                    )
                }
            } catch (e: Exception) {
                failedPackages.add(pkg to (e.message ?: "Unknown"))
                AppLog.w(TAG, "Откат пакета не удался для $pkg: ${LogMasker.mask(e.message ?: "")}")
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

        AppLog.i(TAG, "Откат завершён: ${report.summary()}")
        return report
    }

    suspend fun verifyAll(checkDns: Boolean = false): VerificationResult {
        AppLog.i(TAG, "Запуск финальной верификации")
        val failedItems = mutableListOf<String>()

        if (!verifyAnalyticsDisabled()) failedItems.add("analytics_services")
        if (!verifyAdServicesDisabled()) failedItems.add("ad_services")
        if (checkDns && !verifyDnsFilter()) failedItems.add("dns_filter")

        val success = failedItems.isEmpty()
        AppLog.i(
            TAG,
            "Финальная верификация ${if (success) "ПРОЙДЕНА" else "НЕ ПРОЙДЕНА: $failedItems"}"
        )

        return VerificationResult(
            success = success,
            failedItems = failedItems,
            details = if (success) "Все проверки пройдены" else "Не пройдены: ${
                failedItems.joinToString(
                    ", "
                )
            }"
        )
    }

    private suspend fun verifyAnalyticsDisabled(): Boolean {
        return verifyPackagesInactive(ServiceRegistry.ANALYTICS_PACKAGES, "analytics")
    }

    private suspend fun verifyAdServicesDisabled(): Boolean {
        return verifyPackagesInactive(ServiceRegistry.AD_SERVICES_PACKAGES, "ad_services")
    }

    /**
     * Проверяем только пакеты, которые реально установлены на устройстве.
     * Union CN+Global реестра иначе валит verify на любой региональной прошивке.
     */
    private suspend fun verifyPackagesInactive(packages: List<String>, label: String): Boolean {
        return try {
            val installedRaw = adb.executeCommand("shell pm list packages").getOrNull() ?: ""
            val targets = packages.filter { pkg ->
                installedRaw.contains("package:$pkg") || installedRaw.contains(pkg)
            }
            if (targets.isEmpty()) {
                AppLog.i(TAG, "verify $label: no listed packages installed — OK")
                return true
            }

            val disabled = adb.executeCommand("shell pm list packages -d").getOrNull() ?: ""
            val suspended = adb.executeCommand("shell pm list packages --suspended").getOrNull()
                ?: ""

            val failed = targets.filterNot { pkg ->
                disabled.contains(pkg) || suspended.contains(pkg)
            }
            if (failed.isNotEmpty()) {
                AppLog.w(TAG, "verify $label failed for: $failed")
            }
            failed.isEmpty()
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "Верификация $label не удалась: ${LogMasker.mask(e.message ?: "")}"
            )
            false
        }
    }

    private suspend fun verifyDnsFilter(): Boolean {
        return try {
            // shell-префикс для совместимости с разными AdbExecutor
            val mode =
                adb.executeCommand("shell settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull()
                    ?: adb.executeCommand("settings get ${ServiceRegistry.Dns.MODE_KEY}").getOrNull()
                    ?: ""
            val host =
                adb.executeCommand("shell settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}")
                    .getOrNull()
                    ?: adb.executeCommand("settings get ${ServiceRegistry.Dns.SPECIFIER_KEY}")
                        .getOrNull()
                    ?: ""

            mode.contains(ServiceRegistry.Dns.MODE_VALUE) && host.contains("adguard")
        } catch (e: Exception) {
            AppLog.w(TAG, "Верификация DNS не удалась: ${LogMasker.mask(e.message ?: "")}")
            false
        }
    }

    private suspend fun restoreSystemSettingsWithReport(): List<String> {
        AppLog.i(TAG, "Восстановление системных настроек")
        val failed = mutableListOf<String>()

        for (key in ServiceRegistry.SYSTEM_SETTINGS.keys) {
            try {
                // Безопасные дефолты для восстановления
                val restoreValue: String = when {
                    key.contains("low_power") -> DEFAULT_DISABLED
                    key.contains("always_finish") -> DEFAULT_DISABLED
                    key.contains("animation_scale") -> DEFAULT_ANIMATION_SCALE
                    else -> DEFAULT_ENABLED
                }

                val result = adb.executeCommand("shell settings put $key $restoreValue")
                if (result.isFailure) {
                    failed.add(key.substringAfterLast(" "))
                    AppLog.w(
                        TAG,
                        "Команда восстановления не удалась: $key - ${result.exceptionOrNull()?.message}"
                    )
                }
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                failed.add(key.substringAfterLast(" "))
                AppLog.w(TAG, "Команда восстановления не удалась: $key")
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
                    AppLog.w(TAG, "Не удалось включить $pkg: ${result.exceptionOrNull()?.message}")
                }
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                failed.add(pkg)
                AppLog.w(TAG, "Не удалось включить $pkg: ${LogMasker.mask(e.message ?: "")}")
            }
        }

        return failed
    }

    private suspend fun restoreHiddenKeysWithReport(): List<String> {
        AppLog.i(TAG, "Восстановление скрытых ключей")
        val failed = mutableListOf<String>()

        for ((key, value) in ServiceRegistry.HIDDEN_KEYS_RESTORE) {
            try {
                val result = adb.executeCommand("shell settings put $key $value")
                if (result.isFailure) {
                    failed.add(key.substringAfterLast(" "))
                    AppLog.w(
                        TAG,
                        "Восстановление скрытого ключа не удалось: $key - ${result.exceptionOrNull()?.message}"
                    )
                }
                delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            } catch (e: Exception) {
                failed.add(key.substringAfterLast(" "))
                AppLog.w(
                    TAG,
                    "Восстановление скрытого ключа не удалось: $key - ${LogMasker.mask(e.message ?: "")}"
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
                AppLog.w(TAG, "Восстановление DNS не удалось: ${result.exceptionOrNull()?.message}")
                return result.exceptionOrNull()?.message ?: "Unknown"
            }
            delay(AppConstants.COMMAND_DELAY_MS.milliseconds)
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "Восстановление DNS не удалось: ${LogMasker.mask(e.message ?: "")}")
            e.message ?: "Unknown"
        }
    }
}