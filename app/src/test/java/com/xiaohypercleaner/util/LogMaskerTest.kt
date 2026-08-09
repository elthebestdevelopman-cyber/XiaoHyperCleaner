package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogMaskerTest {

    @Test
    fun masksRemoteIp() {
        assertEquals(
            "connect to *.*.*.* failed",
            LogMasker.mask("connect to 192.168.1.10 failed")
        )
    }

    @Test
    fun keepsLocalhost() {
        assertEquals(
            "shell on 127.0.0.1",
            LogMasker.mask("shell on 127.0.0.1")
        )
    }

    @Test
    fun masksLongHex() {
        assertEquals(
            "token <hex>",
            LogMasker.mask("token 0123456789abcdef0123456789abcdef")
        )
    }

    @Test
    fun masksUserPath() {
        assertEquals(
            "path /data/user/*",
            LogMasker.mask("path /data/user/0/com.xiaohypercleaner")
        )
    }

    @Test
    fun masksSettingsValue() {
        assertEquals(
            "settings put secure miui_ad_filtering_enabled *",
            LogMasker.mask("settings put secure miui_ad_filtering_enabled 0")
        )
    }

    @Test
    fun plainTextUntouched() {
        assertEquals(
            "optimize failed: timeout",
            LogMasker.mask("optimize failed: timeout")
        )
    }
}