package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbPortResolverTest {

    @Test
    fun mergePortsAddsFallbackWhenNothingDiscovered() {
        assertEquals(listOf(5555), AdbPortResolver.mergePorts(emptyList(), 5555))
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
}