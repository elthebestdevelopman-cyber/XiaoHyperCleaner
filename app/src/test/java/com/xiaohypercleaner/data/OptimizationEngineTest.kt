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
    var failAfterCommands = -1

    override suspend fun connect(): Boolean = true

    override suspend fun executeCommand(command: String): String {
        commands.add(command)
        if (failAfterCommands in 0 until commands.size) throw AdbException("timeout")
        return when {
            command.startsWith("settings get secure miui_ad_filtering_enabled") -> keyValue
            command.startsWith("pm list packages -d") ->
                disabledPackages.joinToString("\n") { "package:$it" }

            command.startsWith("pm disable-user") -> {
                if (failDisable) "Failure" else {
                    disabledPackages.add(command.substringAfterLast(' '))
                    "Success"
                }
            }

            command.startsWith("pm enable") -> {
                disabledPackages.remove(command.substringAfterLast(' '))
                "Success"
            }

            else -> ""
        }
    }

    override fun disconnect() {}
}

class OptimizationEngineTest {

    @Test
    fun optimizeSucceedsWhenKeysApplied() = runBlocking {
        val fake = FakeAdb()
        val engine = OptimizationEngine(fake)
        assertTrue(engine.optimize())
        assertTrue(fake.commands.any { it.contains("miui_ad_filtering_enabled 0") })
    }

    @Test
    fun optimizeFallsBackToPackagesWhenKeysFail() = runBlocking {
        val fake = FakeAdb().apply { keyValue = "1" }
        val engine = OptimizationEngine(fake)
        assertTrue(engine.optimize())
        assertTrue(fake.disabledPackages.isNotEmpty())
    }

    @Test
    fun optimizeFailsWhenNothingApplied() = runBlocking {
        val fake = FakeAdb().apply { failDisable = true }
        val engine = OptimizationEngine(fake)
        assertFalse(engine.optimize())
    }

    @Test
    fun partialApplicationContinuesToNextMethod() = runBlocking {
        val fake = FakeAdb().apply { keyValue = "1" }
        val engine = OptimizationEngine(fake)
        assertTrue(engine.optimize())
        assertTrue(fake.commands.count { it.startsWith("pm disable-user") } > 0)
    }

    @Test
    fun optimizeSurvivesSingleConnectionDrop() = runBlocking {
        val fake = FakeAdb().apply { failAfterCommands = 3 }
        val engine = OptimizationEngine(fake)
        assertTrue(engine.optimize())
    }

    @Test
    fun restoreEnablesAllPackagesAndKeys() = runBlocking {
        val fake = FakeAdb().apply { disabledPackages.addAll(ServiceRegistry.PACKAGES) }
        val engine = OptimizationEngine(fake)
        assertTrue(engine.restore())
        ServiceRegistry.PACKAGES.forEach { pkg ->
            assertTrue(fake.commands.contains("pm enable $pkg"))
        }
        assertTrue(fake.disabledPackages.isEmpty())
        assertTrue(fake.commands.any { it.contains("miui_region RU") })
    }
}