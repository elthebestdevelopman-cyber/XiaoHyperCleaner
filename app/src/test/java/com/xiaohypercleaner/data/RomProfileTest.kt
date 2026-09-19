package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `miui version labels are parsed from property and incremental`() {
        assertEquals("13", RomProfile.uiVersionLabel("V130"))
        assertEquals("12.5", RomProfile.uiVersionLabel("V12.5.4"))
        assertEquals("14", RomProfile.uiVersionLabel("V14"))
        assertEquals("13", RomProfile.uiVersionLabel("V13.0.5.0.SJURUXM"))
        assertEquals("2", RomProfile.uiVersionLabel("OS2.0.4.0.ABCDEF"))
        assertEquals("3", RomProfile.uiVersionLabel("OS3.0.1.0"))
        // Код сборки (V816) — не версия оболочки.
        assertEquals(null, RomProfile.uiVersionLabel("V816"))
        assertEquals(null, RomProfile.uiVersionLabel(""))
    }

    @Test
    fun `rom family is detected from props and incremental prefix`() {
        assertEquals(RomFamily.MIUI, RomProfile.detectFamily("V130", null, "V13.0.5.0.SJURUXM"))
        assertEquals(RomFamily.HYPEROS, RomProfile.detectFamily(null, "OS2.0.4.0.X", "OS2.0.4.0.X"))
        assertEquals(RomFamily.HYPEROS, RomProfile.detectFamily("V816", null, "OS1.0.7.0.UMOEUXM"))
        assertEquals(RomFamily.MIUI, RomProfile.detectFamily(null, null, "V12.5.4.0.SJURUXM"))
        assertEquals(RomFamily.UNKNOWN, RomProfile.detectFamily(null, null, "RKQ1.211001.001"))
    }

    @Test
    fun `ui ordinal and os range matching work for miui 13 and hyperos 2`() {
        val miui13 = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "V130",
            hyperOsHint = false,
            isTablet = false,
            family = RomFamily.MIUI,
            uiVersion = "13"
        )
        val hyperOs2 = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "OS2.0.4.0.X",
            hyperOsHint = true,
            isTablet = false,
            family = RomFamily.HYPEROS,
            uiVersion = "2"
        )

        assertEquals(13.0, miui13.uiOrdinal, 0.001)
        assertEquals(2.0, hyperOs2.uiOrdinal, 0.001)
        assertTrue("MIUI 13 попадает в диапазон 13..14", miui13.matchesOs(RomFamily.MIUI, 13.0, 14.0))
        assertFalse("MIUI 13 не попадает в HyperOS-диапазон", miui13.matchesOs(RomFamily.HYPEROS, 1.0, 3.0))
        assertTrue("HyperOS 2 попадает в диапазон 2..3", hyperOs2.matchesOs(RomFamily.HYPEROS, 2.0, 3.0))
        assertFalse("HyperOS 2 не попадает в MIUI 12.5-диапазон", hyperOs2.matchesOs(RomFamily.MIUI, 12.0, 12.5))
    }
}
