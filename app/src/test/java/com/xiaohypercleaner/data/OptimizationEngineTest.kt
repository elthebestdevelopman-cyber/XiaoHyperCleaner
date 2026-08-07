package com.xiaohypercleaner.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private open class FakeAdb : AdbExecutor {
    val commands = mutableListOf<String>()
    val disabledPackages = mutableSetOf<String>()
    var keyValue = "1"
    var failDisable = false

    override suspend fun connect(): Boolean = true

    override suspend fun executeCommand(command: String): String {
        commands.add(command)
        return when {
            command.startsWith("settings get secure miui_ad_filtering_enabled") -> keyValue
            command.startsWith("pm list packages -d") ->
                disabledPackages.joinToString("\n") { "package:$it" }

            command.startsWith("pm disable-user") || command.startsWith("pm disable ") -> {
                if (failDisable) "Failure"
                else {
                    disabledPackages.add(command.substringAfterLast(' '))
                    "Success"
                }
            }

            command.startsWith("pm enable") -> {
                disabledPackages.remove(command.substringAfterLast(' '))
                "Success"
            }

            command.startsWith("pm clear") -> "Success"
            command.startsWith("settings put") -> ""
            else -> ""
        }
    }

    override fun disconnect() {}
}

class OptimizationEngineTest {

    @Test
    fun optimizeSucceedsWhenKeysApplied() = runBlocking {
        val fake = FakeAdb().apply { keyValue = "0" }
        val engine = OptimizationEngine(fake)

        val ok = engine.optimize()

        assertTrue("Оптимизация должна была завершиться успехом", ok)
        assertTrue(
            "Должны были примениться системные ключи",
            fake.commands.any { it.contains("miui_ad_filtering_enabled 0") })
    }

    @Test
    fun optimizeFallsBackToPackagesWhenKeysFail() = runBlocking {
        val fake = FakeAdb().apply { keyValue = "1" }
        val engine = OptimizationEngine(fake)

        val ok = engine.optimize()

        assertTrue("Fallback на пакеты должен был сработать", ok)
        assertTrue(
            "Должны были быть вызовы pm disable-user",
            fake.commands.any { it.startsWith("pm disable-user") })
    }

    @Test
    fun optimizeFailsWhenNothingApplied() = runBlocking {
        val fake = FakeAdb().apply {
            keyValue = "1"
            failDisable = true
        }
        val engine = OptimizationEngine(fake)

        val ok = engine.optimize()

        assertFalse("При полном сбое оптимизация должна вернуть false", ok)
    }

    @Test
    fun partialApplicationContinuesToNextMethod() = runBlocking {
        val fake = FakeAdb().apply { keyValue = "1" }
        val engine = OptimizationEngine(fake)

        val ok = engine.optimize()

        assertTrue(ok)
        val disableCalls = fake.commands.count { it.startsWith("pm disable") }
        assertTrue("Должно было быть несколько вызовов pm disable", disableCalls > 0)
    }

    @Test
    fun optimizeReportsAdbExceptionAsFailure() = runBlocking {
        val fake = object : FakeAdb() {
            override suspend fun executeCommand(command: String): String {
                throw AdbException("connection lost")
            }
        }
        val engine = OptimizationEngine(fake)

        val ok = engine.optimize()

        assertFalse("При AdbException optimize() должен вернуть false", ok)
    }

    @Test
    fun restoreEnablesAllPackagesAndKeys() = runBlocking {
        val fake = FakeAdb().apply {
            disabledPackages.addAll(ServiceRegistry.PACKAGES)
        }
        val engine = OptimizationEngine(fake)

        val ok = engine.restore()

        assertTrue(ok)
        ServiceRegistry.PACKAGES.forEach { pkg ->
            assertTrue(
                "Должен был быть вызван pm enable $pkg",
                fake.commands.contains("pm enable $pkg")
            )
        }
        assertTrue(
            "Список отключённых пакетов должен быть пуст",
            fake.disabledPackages.isEmpty()
        )
        assertTrue(
            "Должны были примениться ключи восстановления",
            fake.commands.any { it.contains("miui_region RU") })
    }

    @Test
    fun verifyAllAcceptsEitherKeysOrPackages() = runBlocking {
        // Кейс 1: только ключ применён, пакеты все включены
        val fakeKeyOnly = FakeAdb().apply { keyValue = "0" }
        val engineKeyOnly = OptimizationEngine(fakeKeyOnly)
        assertTrue(engineKeyOnly.optimize())

        // Кейс 2: пакеты отключены, ключ не применён
        val fakePkgOnly = FakeAdb().apply { keyValue = "1" }
        val enginePkgOnly = OptimizationEngine(fakePkgOnly)
        assertTrue(enginePkgOnly.optimize())
    }
}