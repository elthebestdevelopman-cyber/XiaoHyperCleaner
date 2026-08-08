package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogMaskerTest {

    @Test
    fun masksIpv4() {
        assertEquals("connect to *.*.*.* failed", LogMasker.mask("connect to 127.0.0.1 failed"))
    }

    @Test
    fun masksSettingsValue() {
        assertEquals(
            "settings put secure miui_ad_filtering_enabled *",
            LogMasker.mask("settings put secure miui_ad_filtering_enabled 0")
        )
    }

    @Test
    fun keepsPackageCommand() {
        val cmd = "pm disable-user --user 0 com.miui.analytics"
        assertEquals(cmd, LogMasker.mask(cmd))
    }
}