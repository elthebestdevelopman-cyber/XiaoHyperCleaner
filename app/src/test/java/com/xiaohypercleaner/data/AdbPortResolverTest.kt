package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты для [AdbPortResolver].
 *
 * Проверяют только чистую логику mergePorts() — mDNS discovery не тестируется
 * здесь, т.к. требует Android-контекста (для этого есть instrumentation-тесты).
 */
class AdbPortResolverTest {

    @Test
    fun mergePortsAddsFallbackWhenNothingDiscovered() {
        assertEquals(
            listOf(5555),
            AdbPortResolver.mergePorts(emptyList(), 5555)
        )
    }

    @Test
    fun mergePortsPutsDiscoveredFirstAndDeduplicates() {
        assertEquals(
            listOf(41231, 5555),
            AdbPortResolver.mergePorts(listOf(41231, 5555), 5555)
        )
    }

    @Test
    fun mergePortsKeepsMultipleDiscoveredPorts() {
        assertEquals(
            listOf(41231, 43521, 5555),
            AdbPortResolver.mergePorts(listOf(41231, 43521), 5555)
        )
    }

    @Test
    fun mergePortsDeduplicatesAcrossAllPorts() {
        // Если fallback уже есть в discovered — не должно быть дубликата
        assertEquals(
            listOf(5555, 41231),
            AdbPortResolver.mergePorts(listOf(5555, 41231), 5555)
        )
    }

    @Test
    fun mergePortsPreservesOrderOfDiscovered() {
        // Порядок discovered портов сохраняется (важно для приоритета подключения)
        assertEquals(
            listOf(43521, 41231, 5555),
            AdbPortResolver.mergePorts(listOf(43521, 41231), 5555)
        )
    }
}