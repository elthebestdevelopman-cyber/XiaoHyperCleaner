package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RomProfileTest {

    @Test
    fun `global region prefers global market alias`() {
        val profile = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "V816",
            hyperOsHint = true,
            isTablet = false
        )
        val ordered = profile.preferPackages(
            listOf("com.xiaomi.market", "com.mi.global.market", "com.miui.market")
        )
        assertEquals("com.mi.global.market", ordered.first())
    }

    @Test
    fun `cn region prefers miui or xiaomi aliases`() {
        val profile = RomProfile(
            region = RomRegion.CN,
            miuiVersion = "V14",
            hyperOsHint = false,
            isTablet = true
        )
        val ordered = profile.preferPackages(
            listOf("com.mi.global.market", "com.xiaomi.market")
        )
        assertEquals("com.xiaomi.market", ordered.first())
        assertTrue(profile.isTablet)
    }
}
