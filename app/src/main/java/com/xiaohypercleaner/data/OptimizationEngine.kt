package com.xiaohypercleaner.data

import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

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

    suspend fun optimize(callbacks: Callbacks = Callbacks()): Boolean {
        AppLog.i(TAG, "OptimizationEngine: starting optimization")
        callbacks.onStage("connecting")
        callbacks.onProgress(AppConstants.PROGRESS_START)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        callbacks.onProgress(AppConstants.PROGRESS_CONNECTED)
        delay(AppConstants.DELAY_AFTER_CONNECT_MS)

        // Метод 1: системные параметры
        callbacks.onStage("method1")
        callbacks.onProgress(AppConstants.PROGRESS_METHOD2)
        if (!applySystemSettings(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        if (!verifySystemSettings()) {
            AppLog.w(TAG, "OptimizationEngine: system settings verification failed")
            callbacks.onError("verify_method1_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        // Метод 2: отключение сервисов аналитики
        callbacks.onStage("method2")
        callbacks.onProgress(AppConstants.PROGRESS_METHOD3)
        if (!disableAnalyticsServices(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        if (!verifyAnalyticsDisabled()) {
            AppLog.w(TAG, "OptimizationEngine: analytics services verification failed")
            callbacks.onError("verify_method2_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        // Метод 3: фейковая смена региона
        callbacks.onStage("method3")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)
        if (!applyFakeRegion(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        if (!verifyFakeRegion()) {
            AppLog.w(TAG, "OptimizationEngine: fake region verification failed")
            callbacks.onError("verify_method3_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        // Метод 4: отключение рекламных служб
        callbacks.onStage("method4")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)
        if (!disableAdServices(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        if (!verifyAdServicesDisabled()) {
            AppLog.w(TAG, "OptimizationEngine: ad services verification failed")
            callbacks.onError("verify_method4_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        // Финальная проверка
        callbacks.onStage("verifying")
        val finalCheck = verifyAll()
        if (!finalCheck.success) {
            AppLog.w(
                TAG,
                "OptimizationEngine: final verification failed: ${finalCheck.failedItems}"
            )
            callbacks.onError("final_verification_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        callbacks.onProgress(AppConstants.PROGRESS_DONE)
        AppLog.i(TAG, "OptimizationEngine: optimization completed successfully")
        return true
    }

    suspend fun restore(callbacks: Callbacks = Callbacks()): Boolean {
        AppLog.i(TAG, "OptimizationEngine: starting restore")
        callbacks.onStage("restoring_keys")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_KEYS)

        if (!connect()) {
            callbacks.onError("connect_failed")
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        if (!restoreSystemSettings(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }
        delay(AppConstants.COMMAND_DELAY_MS)

        callbacks.onStage("restoring_packages")
        callbacks.onProgress(AppConstants.PROGRESS_RESTORE_PACKAGES)

        if (!restoreServices(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        if (!restoreRegion(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        if (!restoreAdServices(callbacks)) {
            callbacks.onProgress(AppConstants.PROGRESS_FAIL)
            return false
        }

        val finalCheck = verifyRestored()
        if (!finalCheck.success) {
            AppLog.w(
                TAG,
                "OptimizationEngine: restore verification failed: ${finalCheck.failedItems}"
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

    private suspend fun applySystemSettings(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: applying system settings")
        val commands = listOf(
            "shell settings put global low_power 1",
            "shell settings put global always_finish_activities 0",
            "shell settings put global background_limit 4",
            "shell settings put global process_limit 4",
            "shell settings put global window_animation_scale 0.5",
            "shell settings put global transition_animation_scale 0.5",
            "shell settings put global animator_duration_scale 0.5"
        )
        for (cmd in commands) {
            try {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: command failed: $cmd - ${e.message}")
            }
        }
        return true
    }

    private suspend fun verifySystemSettings(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: verifying system settings")
        val checks = listOf(
            "shell settings get global low_power" to "1",
            "shell settings get global window_animation_scale" to "0.5",
            "shell settings get global transition_animation_scale" to "0.5",
            "shell settings get global animator_duration_scale" to "0.5"
        )
        for ((cmd, expected) in checks) {
            try {
                val result = adb.executeCommand(cmd).trim()
                if (!result.contains(expected)) {
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: setting verification failed: $cmd = $result (expected $expected)"
                    )
                    return false
                }
            } catch (e: Exception) {
                AppLog.w(
                    TAG,
                    "OptimizationEngine: verification command failed: $cmd - ${e.message}"
                )
                return false
            }
        }
        return true
    }

    // ===== Метод 2: отключение сервисов аналитики =====

    private suspend fun disableAnalyticsServices(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: disabling analytics services")
        val packages = listOf(
            "com.xiaomi.misettings",
            "com.miui.analytics",
            "com.xiaomi.ab",
            "com.miui.msa.core",
            "com.miui.systemAdSolution",
            "com.xiaomi.discover",
            "com.miui.bugreport"
        )
        for (pkg in packages) {
            try {
                adb.executeCommand("shell pm disable-user --user 0 $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
                AppLog.i(TAG, "OptimizationEngine: disabled $pkg")
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to disable $pkg: ${e.message}")
            }
        }
        return true
    }

    private suspend fun verifyAnalyticsDisabled(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: verifying analytics services disabled")
        val packages = listOf(
            "com.miui.analytics",
            "com.miui.systemAdSolution",
            "com.xiaomi.ab"
        )
        try {
            val result = adb.executeCommand("shell pm list packages -d").trim()
            for (pkg in packages) {
                if (!result.contains(pkg)) {
                    AppLog.w(TAG, "OptimizationEngine: package $pkg is not disabled")
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: verification failed: ${e.message}")
            return false
        }
    }

    // ===== Метод 3: фейковая смена региона =====

    private suspend fun applyFakeRegion(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: applying fake region")
        return try {
            adb.executeCommand("shell setprop persist.sys.timezone Asia/Singapore")
            delay(AppConstants.COMMAND_DELAY_MS)
            adb.executeCommand("shell settings put global device_provisioned 1")
            delay(AppConstants.COMMAND_DELAY_MS)
            adb.executeCommand("shell settings put secure limit_ad_tracking 1")
            delay(AppConstants.COMMAND_DELAY_MS)
            AppLog.i(TAG, "OptimizationEngine: fake region applied")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: fake region failed: ${e.message}")
            false
        }
    }

    private suspend fun verifyFakeRegion(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: verifying fake region")
        return try {
            val limitAd = adb.executeCommand("shell settings get secure limit_ad_tracking").trim()
            if (!limitAd.contains("1")) {
                AppLog.w(TAG, "OptimizationEngine: limit_ad_tracking verification failed: $limitAd")
                return false
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: region verification failed: ${e.message}")
            false
        }
    }

    // ===== Метод 4: отключение рекламных служб =====

    private suspend fun disableAdServices(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: disabling ad services")
        val packages = listOf(
            "com.miui.systemAdSolution",
            "com.miui.analytics",
            "com.xiaomi.ad",
            "com.miui.ad",
            "com.miui.personalassistant",
            "com.miui.smartassistant"
        )
        for (pkg in packages) {
            try {
                adb.executeCommand("shell pm disable-user --user 0 $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
                AppLog.i(TAG, "OptimizationEngine: disabled ad service $pkg")
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to disable $pkg: ${e.message}")
            }
        }
        return true
    }

    private suspend fun verifyAdServicesDisabled(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: verifying ad services disabled")
        val packages = listOf(
            "com.miui.systemAdSolution",
            "com.miui.analytics",
            "com.xiaomi.ad"
        )
        try {
            val result = adb.executeCommand("shell pm list packages -d").trim()
            for (pkg in packages) {
                if (!result.contains(pkg)) {
                    AppLog.w(TAG, "OptimizationEngine: ad service $pkg is not disabled")
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: ad service verification failed: ${e.message}")
            return false
        }
    }

    // ===== Финальная проверка =====

    suspend fun verifyAll(): VerificationResult {
        AppLog.i(TAG, "OptimizationEngine: running final verification")
        val failedItems = mutableListOf<String>()

        if (!verifySystemSettings()) failedItems.add("system_settings")
        if (!verifyAnalyticsDisabled()) failedItems.add("analytics_services")
        if (!verifyFakeRegion()) failedItems.add("fake_region")
        if (!verifyAdServicesDisabled()) failedItems.add("ad_services")
        if (!checkAdsDisabledInSystemApps()) failedItems.add("system_app_ads")
        if (!checkRecommendationsDisabled()) failedItems.add("recommendations")

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

    private suspend fun checkAdsDisabledInSystemApps(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: checking ads disabled in system apps")
        return try {
            val adPackages = listOf(
                "com.miui.systemAdSolution",
                "com.miui.analytics",
                "com.xiaomi.ad",
                "com.miui.ad"
            )
            val disabledPackages = adb.executeCommand("shell pm list packages -d").trim()
            for (pkg in adPackages) {
                if (!disabledPackages.contains(pkg)) {
                    AppLog.w(TAG, "OptimizationEngine: ad package $pkg is still enabled")
                    return false
                }
            }
            val limitAd = adb.executeCommand("shell settings get secure limit_ad_tracking").trim()
            if (!limitAd.contains("1")) {
                AppLog.w(TAG, "OptimizationEngine: personalized ads still enabled")
                return false
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: ads check failed: ${e.message}")
            false
        }
    }

    private suspend fun checkRecommendationsDisabled(): Boolean {
        AppLog.i(TAG, "OptimizationEngine: checking recommendations disabled")
        return try {
            val recPackages = listOf(
                "com.miui.personalassistant",
                "com.miui.smartassistant",
                "com.miui.msa.core"
            )
            val disabledPackages = adb.executeCommand("shell pm list packages -d").trim()
            for (pkg in recPackages) {
                if (!disabledPackages.contains(pkg)) {
                    AppLog.w(
                        TAG,
                        "OptimizationEngine: recommendation package $pkg is still enabled"
                    )
                    return false
                }
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: recommendations check failed: ${e.message}")
            false
        }
    }

    // ===== Восстановление =====

    private suspend fun verifyRestored(): VerificationResult {
        AppLog.i(TAG, "OptimizationEngine: running restore verification")
        val failedItems = mutableListOf<String>()

        if (!verifySystemSettingsRestored()) failedItems.add("system_settings_restored")
        if (!verifyServicesEnabled()) failedItems.add("services_enabled")

        val success = failedItems.isEmpty()
        AppLog.i(
            TAG,
            "OptimizationEngine: restore verification ${if (success) "PASSED" else "FAILED: $failedItems"}"
        )

        return VerificationResult(
            success = success,
            failedItems = failedItems,
            details = if (success) "Restore completed" else "Failed: ${failedItems.joinToString(", ")}"
        )
    }

    private suspend fun verifySystemSettingsRestored(): Boolean {
        return try {
            val animScale =
                adb.executeCommand("shell settings get global window_animation_scale").trim()
            if (!animScale.contains("1.0") && !animScale.contains("1")) {
                AppLog.w(TAG, "OptimizationEngine: animation scale not restored: $animScale")
                return false
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: restore verification failed: ${e.message}")
            false
        }
    }

    private suspend fun verifyServicesEnabled(): Boolean {
        return try {
            val enabledPackages = adb.executeCommand("shell pm list packages -e").trim()
            val packages = listOf("com.miui.analytics", "com.miui.systemAdSolution")
            for (pkg in packages) {
                if (!enabledPackages.contains(pkg)) {
                    AppLog.w(TAG, "OptimizationEngine: package $pkg is not enabled")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: services verification failed: ${e.message}")
            false
        }
    }

    private suspend fun restoreSystemSettings(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: restoring system settings")
        val commands = listOf(
            "shell settings put global low_power 0",
            "shell settings put global always_finish_activities 0",
            "shell settings put global background_limit 10",
            "shell settings put global process_limit 10",
            "shell settings put global window_animation_scale 1.0",
            "shell settings put global transition_animation_scale 1.0",
            "shell settings put global animator_duration_scale 1.0"
        )
        for (cmd in commands) {
            try {
                adb.executeCommand(cmd)
                delay(AppConstants.COMMAND_DELAY_MS)
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: restore command failed: $cmd - ${e.message}")
            }
        }
        return true
    }

    private suspend fun restoreServices(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: restoring services")
        val packages = listOf(
            "com.xiaomi.misettings",
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
                AppLog.i(TAG, "OptimizationEngine: enabled $pkg")
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to enable $pkg: ${e.message}")
            }
        }
        return true
    }

    private suspend fun restoreRegion(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: restoring region")
        return try {
            adb.executeCommand("shell setprop persist.sys.timezone Asia/Shanghai")
            delay(AppConstants.COMMAND_DELAY_MS)
            adb.executeCommand("shell settings put secure limit_ad_tracking 0")
            delay(AppConstants.COMMAND_DELAY_MS)
            AppLog.i(TAG, "OptimizationEngine: region restored")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "OptimizationEngine: region restore failed: ${e.message}")
            false
        }
    }

    private suspend fun restoreAdServices(callbacks: Callbacks): Boolean {
        AppLog.i(TAG, "OptimizationEngine: restoring ad services")
        val packages = listOf(
            "com.miui.systemAdSolution",
            "com.miui.analytics",
            "com.xiaomi.ad",
            "com.miui.ad",
            "com.miui.personalassistant",
            "com.miui.smartassistant"
        )
        for (pkg in packages) {
            try {
                adb.executeCommand("shell pm enable $pkg")
                delay(AppConstants.COMMAND_DELAY_MS)
                AppLog.i(TAG, "OptimizationEngine: enabled ad service $pkg")
            } catch (e: Exception) {
                AppLog.w(TAG, "OptimizationEngine: failed to enable $pkg: ${e.message}")
            }
        }
        return true
    }
}