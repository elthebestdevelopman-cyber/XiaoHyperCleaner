package com.xiaohypercleaner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private open class FakeAdb : AdbExecutor {
    val commands = mutableListOf<String>()
    val disabledPackages = mutableSetOf<String>()
    var keyValue = "1"
    var failDisable = false
    var failAtCommandNumber = -1
    var failedOnce = false
    var connectionsCount = 0

    var dnsMode: String = "opportunistic"
    var dnsSpecifier: String = ""
    var originalRegion: String = "RU"

    override suspend fun connect(): Boolean {
        connectionsCount++
        return true
    }

    override suspend fun executeCommand(command: String): Result<String> {
        commands.add(command)
        if (commands.size == failAtCommandNumber && !failedOnce) {
            failedOnce = true
            if (connect()) {
                return Result.success(executeCommandInternal(command))
            }
            return Result.failure(AdbException("timeout"))
        }
        return Result.success(executeCommandInternal(command))
    }

    private fun executeCommandInternal(command: String): String {
        return when {
            command.contains("settings get secure limit_ad_tracking") -> keyValue
            command.contains("settings get secure user_experience_program") -> "1"
            command.contains("settings get secure upload_log_pref") -> "1"
            command.contains("settings get secure show_recommendations") -> "1"
            command.contains("settings get secure miui_region") -> originalRegion
            command.contains("settings get global window_animation_scale") -> "0.5"
            command.contains("settings get global transition_animation_scale") -> "0.5"
            command.contains("settings get global animator_duration_scale") -> "0.5"
            command.contains("settings get global low_power") -> "1"
            command.contains("settings get global always_finish_activities") -> "0"

            command.contains("settings get global private_dns_mode") -> dnsMode
            command.contains("settings get global private_dns_specifier") -> dnsSpecifier

            command.contains("settings put global private_dns_mode") -> {
                dnsMode = command.substringAfterLast(' ')
                "Success"
            }

            command.contains("settings put global private_dns_specifier") -> {
                dnsSpecifier = command.substringAfterLast(' ')
                "Success"
            }

            command.contains("pm list packages -d") ->
                disabledPackages.joinToString("\n") { "package:$it" }

            command.contains("pm list packages -e") ->
                listOf("com.miui.analytics", "com.miui.systemAdSolution")
                    .joinToString("\n") { "package:$it" }

            command.contains("pm disable-user") -> {
                if (failDisable) "Failure" else {
                    disabledPackages.add(command.substringAfterLast(' '))
                    "Success"
                }
            }

            command.contains("pm suspend") -> {
                if (failDisable) "Failure" else {
                    disabledPackages.add(command.substringAfterLast(' '))
                    "Success"
                }
            }

            command.contains("pm enable") -> {
                disabledPackages.remove(command.substringAfterLast(' '))
                "Success"
            }

            command.startsWith("settings put") -> "Success"
            command.contains("setprop") -> "Success"
            command.contains("shell reboot") -> ""

            else -> ""
        }
    }

    override fun disconnect() {}
}

class OptimizationEngineTest {
    @Test
    fun optimizeSucceedsWhenSystemSettingsApplied() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed", report.success)
        assertTrue(
            "should contain low_power setting",
            fake.commands.any { it.contains("settings put global low_power 1") }
        )
        assertTrue(
            "should contain animation scale setting",
            fake.commands.any { it.contains("settings put global window_animation_scale 0.5") }
        )
        assertTrue(
            "applied settings list should not be empty",
            report.appliedSettings.isNotEmpty()
        )
    }

    @Test
    fun optimizeDisablesAnalyticsServices() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed", report.success)
        assertTrue(
            "should disable analytics",
            fake.commands.any { it.contains("pm disable-user --user 0 com.miui.analytics") }
        )
        assertTrue(
            "should disable systemAdSolution",
            fake.commands.any { it.contains("pm disable-user --user 0 com.miui.systemAdSolution") }
        )
        assertTrue(
            "disabled packages list should not be empty",
            report.disabledPackages.isNotEmpty()
        )
    }

    @Test
    fun optimizeFailsWhenNothingApplied() = runBlocking {
        val fake = FakeAdb().apply { failDisable = true }
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertFalse("optimize should fail when nothing can be disabled", report.success)
        assertTrue(
            "failedActions should not be empty",
            report.failedActions.isNotEmpty()
        )
    }

    /**
     * Проверяет что в обычном режиме применяются безопасные настройки (limit_ad_tracking и др.)
     * БЕЗ смены региона (timezone/setprop удалены как ломающие функциональность)
     */
    @Test
    fun optimizeAppliesSafeSettings() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed", report.success)
        assertTrue(
            "should set limit_ad_tracking",
            fake.commands.any { it.contains("settings put secure limit_ad_tracking 1") }
        )
        assertTrue(
            "should set user_experience_program to 0",
            fake.commands.any { it.contains("settings put secure user_experience_program 0") }
        )
        assertTrue(
            "should NOT change timezone (removed for safety)",
            fake.commands.none { it.contains("setprop persist.sys.timezone") }
        )
        assertTrue(
            "should NOT change region by default",
            fake.commands.none { it.contains("settings put secure miui_region") }
        )
    }

    /**
     * Проверяет что в aggressive mode меняется регион на DE.
     * Этот режим опасен (ломает OTA), поэтому выключен по умолчанию.
     */
    @Test
    fun optimizeAggressiveModeChangesRegion() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val options = OptimizationOptions(aggressiveMode = true)
        val report = engine.optimize(options)
        assertTrue("optimize should succeed in aggressive mode", report.success)
        assertTrue(
            "should read original region before changing",
            fake.commands.any { it.contains("settings get secure miui_region") }
        )
        assertTrue(
            "should change region to DE in aggressive mode",
            fake.commands.any { it.contains("settings put secure miui_region DE") }
        )
        assertTrue(
            "appliedSettings should contain miui_region",
            report.appliedSettings.any { it.contains("miui_region") }
        )
    }

    @Test
    fun optimizeSurvivesSingleConnectionDrop() = runBlocking {
        val fake = FakeAdb().apply {
            failAtCommandNumber = 3
        }
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed after reconnection", report.success)
        assertTrue("Should have reconnected", fake.connectionsCount > 1)
        assertTrue("Should have failed once and recovered", fake.failedOnce)
    }

    @Test
    fun optimizeWithDnsFilter() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val options = OptimizationOptions(dnsFilter = true)
        val report = engine.optimize(options)
        assertTrue("optimize should succeed with DNS", report.success)
        assertTrue(
            "should set DNS mode",
            fake.commands.any { it.contains("settings put global private_dns_mode hostname") }
        )
        assertTrue(
            "should set DNS host",
            fake.commands.any { it.contains("settings put global private_dns_specifier dns.adguard.com") }
        )
    }

    @Test
    fun optimizeWithoutDnsFilter() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val options = OptimizationOptions(dnsFilter = false)
        val report = engine.optimize(options)
        assertTrue("optimize should succeed without DNS", report.success)
        assertFalse(
            "should NOT set DNS mode",
            fake.commands.any { it.contains("settings put global private_dns_mode hostname") }
        )
        assertFalse(
            "should NOT set DNS host",
            fake.commands.any { it.contains("settings put global private_dns_specifier dns.adguard.com") }
        )
    }

    @Test
    fun restoreEnablesAllPackagesAndRestoresSettings() = runBlocking {
        val fake = FakeAdb().apply {
            disabledPackages.addAll(
                listOf(
                    "com.miui.analytics",
                    "com.miui.systemAdSolution",
                    "com.xiaomi.ab",
                    "com.miui.msa.core"
                )
            )
        }
        val engine = OptimizationEngine(fake)
        val ok = engine.restore()
        assertTrue("restore should succeed", ok)
        assertTrue(
            "should enable analytics",
            fake.commands.any { it.contains("pm enable com.miui.analytics") }
        )
        assertTrue(
            "should enable systemAdSolution",
            fake.commands.any { it.contains("pm enable com.miui.systemAdSolution") }
        )
        assertTrue(
            "should restore animation scale",
            fake.commands.any { it.contains("settings put global window_animation_scale 1.0") }
        )
        assertTrue(
            "should restore limit_ad_tracking",
            fake.commands.any { it.contains("settings put secure limit_ad_tracking 0") }
        )
    }

    @Test
    fun reportContainsAccurateCounts() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed", report.success)
        // 5 системных настроек + 4 безопасных = минимум 9
        assertTrue(
            "appliedSettings should have at least 9 items, got ${report.appliedSettings.size}",
            report.appliedSettings.size >= 9
        )
        // Как минимум несколько пакетов отключено
        assertTrue(
            "disabledPackages should have at least 3 items, got ${report.disabledPackages.size}",
            report.disabledPackages.size >= 3
        )
        assertTrue(
            "failedActions should be empty on success",
            report.failedActions.isEmpty()
        )
    }

    @Test
    fun verificationResultIsAttached() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()
        assertTrue("optimize should succeed", report.success)
        assertTrue(
            "verificationResult should be successful",
            report.verificationResult.success
        )
        assertTrue(
            "verificationResult.failedItems should be empty on success",
            report.verificationResult.failedItems.isEmpty()
        )
    }
}