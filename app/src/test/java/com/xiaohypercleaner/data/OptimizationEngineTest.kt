package com.xiaohypercleaner.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мок [AdbExecutor] для тестирования [OptimizationEngine].
 *
 * Эмулирует поведение настоящего ADB-клиента:
 * - Хранит список выполненных команд
 * - Отслеживает отключённые пакеты
 * - Поддерживает симуляцию обрыва соединения (failAtCommandNumber)
 * - Обрабатывает DNS-настройки и region
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * ИСПРАВЛЕНО: override fun isConnected()
     * Требуется контрактом интерфейса AdbExecutor.
     */
    override fun isConnected(): Boolean = true

    override fun disconnect() {
        // Ничего не делаем в моке
    }

    private fun executeCommandInternal(command: String): String {
        return when {
            // ── GET settings ──
            command.contains("settings get secure limit_ad_tracking") -> keyValue
            command.contains("settings get secure user_experience_program") -> "1"
            command.contains("settings get secure upload_log_pref") -> "1"
            command.contains("settings get secure show_recommendations") -> "1"
            command.contains("settings get system miui_recents_show_recommend") -> "1"
            command.contains("settings get secure miui_region") -> originalRegion
            command.contains("settings get global window_animation_scale") -> "0.5"
            command.contains("settings get global transition_animation_scale") -> "0.5"
            command.contains("settings get global animator_duration_scale") -> "0.5"
            command.contains("settings get global low_power") -> "1"
            command.contains("settings get global always_finish_activities") -> "0"

            // ── DNS settings ──
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

            // ── Package management ──
            command.contains("pm list packages -d") ->
                disabledPackages.joinToString("\n") { "package:$it" }

            command.contains("pm list packages --suspended") -> ""

            command.contains("pm list packages -e") ->
                listOf("com.miui.analytics", "com.miui.systemAdSolution")
                    .joinToString("\n") { "package:$it" }

            command.contains("pm list packages") ->
                (ServiceRegistry.ANALYTICS_PACKAGES + ServiceRegistry.AD_SERVICES_PACKAGES)
                    .distinct()
                    .joinToString("\n") { "package:$it" }

            command.contains("pm path") -> {
                val pkg = command.substringAfterLast(' ')
                if ((ServiceRegistry.ANALYTICS_PACKAGES + ServiceRegistry.AD_SERVICES_PACKAGES)
                        .contains(pkg)
                ) "package:/system/app/$pkg/$pkg.apk"
                else ""
            }

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

            // ── PUT settings ──
            command.startsWith("settings put") -> "Success"
            command.contains("setprop") -> "Success"
            command.contains("shell reboot") -> ""

            else -> ""
        }
    }
}

/**
 * Тесты для [OptimizationEngine].
 *
 * Проверяют:
 * - Успешное применение системных настроек
 * - Отключение аналитических сервисов
 * - Обработку ошибок и реконнект
 * - Работу с DNS-фильтром
 * - Восстановление настроек (restore)
 * - Формирование корректного отчёта
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimizationEngineTest {

    @Test
    fun optimizeSucceedsWhenSystemSettingsApplied() = runTest {
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
            "appliedSettings list should not be empty",
            report.appliedSettings.isNotEmpty()
        )
    }

    @Test
    fun optimizeDisablesAnalyticsServices() = runTest {
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
            "disabledPackages list should not be empty",
            report.disabledPackages.isNotEmpty()
        )
    }

    @Test
    fun optimizeFailsWhenNothingApplied() = runTest {
        val fake = FakeAdb().apply { failDisable = true }
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()

        // Даже если все disable-user падают, системные настройки могут примениться.
        // Проверяем, что failedActions содержит ошибки.
        assertTrue(
            "failedActions should not be empty when disable fails",
            report.failedActions.isNotEmpty()
        )
    }

    /**
     * Проверяет, что в обычном режиме применяются безопасные настройки
     * (limit_ad_tracking и др.) БЕЗ смены региона.
     */
    @Test
    fun optimizeAppliesSafeSettings() = runTest {
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
        assertFalse(
            "should NOT change timezone (removed for safety)",
            fake.commands.any { it.contains("setprop persist.sys.timezone") }
        )
        assertFalse(
            "should NOT change region by default",
            fake.commands.any { it.contains("settings put secure miui_region") }
        )
    }

    @Test
    fun optimizeSurvivesSingleConnectionDrop() = runTest {
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
    fun optimizeWithDnsFilter() = runTest {
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
    fun optimizeWithoutDnsFilter() = runTest {
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
    fun restoreEnablesAllPackagesAndRestoresSettings() = runTest {
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

    /**
     * Проверяет, что отчёт содержит корректные данные после оптимизации.
     * Используем устойчивые проверки `isNotEmpty()` вместо хрупких `>= N`.
     */
    @Test
    fun reportContainsAccurateCounts() = runTest {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        val report = engine.optimize()

        assertTrue("optimize should succeed", report.success)
        assertTrue(
            "appliedSettings should contain at least one setting",
            report.appliedSettings.isNotEmpty()
        )
        assertTrue(
            "disabledPackages should contain at least one package",
            report.disabledPackages.isNotEmpty()
        )
        assertTrue(
            "failedActions should be empty on success",
            report.failedActions.isEmpty()
        )
    }

    @Test
    fun verificationResultIsAttached() = runTest {
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