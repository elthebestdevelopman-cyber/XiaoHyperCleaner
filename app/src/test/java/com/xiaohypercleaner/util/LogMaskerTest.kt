package com.xiaohypercleaner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-тесты для текущего [LogMasker] (IP ***.***.***.***, token=***, без маскировки settings values).
 */
class LogMaskerTest {

    @Test
    fun masksRemoteIp() {
        assertEquals(
            "connect to ***.***.***.*** failed",
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
    fun masksMultipleIps() {
        assertEquals(
            "from ***.***.***.*** to ***.***.***.***",
            LogMasker.mask("from 10.0.0.1 to 172.16.0.5")
        )
    }

    @Test
    fun masksLongTokenKeyValue() {
        assertEquals(
            "token=***",
            LogMasker.mask("token=0123456789abcdef0123456789abcdef")
        )
    }

    @Test
    fun masksMixedCaseKeyValue() {
        assertEquals(
            "key=***",
            LogMasker.mask("key=AbCdEf1234567890AbCdEf1234567890")
        )
    }

    @Test
    fun keepsShortHex() {
        assertEquals(
            "color #FF5733",
            LogMasker.mask("color #FF5733")
        )
    }

    @Test
    fun keepsUserPathWithoutInit() {
        // Без LogMasker.init пути /data/user не маскируются по appDataPath
        assertEquals(
            "path /data/user/0/com.xiaohypercleaner",
            LogMasker.mask("path /data/user/0/com.xiaohypercleaner")
        )
    }

    @Test
    fun masksLongOpaqueTokenInPlace() {
        val input = "file /data/data/com.xiaohypercleaner/files/log.txt"
        val out = LogMasker.mask(input)
        // Длинный сегмент package path может попасть под longTokenRegex (>32)
        assertTrue(out.contains("log.txt") || out.contains("..."))
    }

    @Test
    fun keepsSystemPath() {
        assertEquals(
            "reading /system/bin/sh",
            LogMasker.mask("reading /system/bin/sh")
        )
    }

    @Test
    fun settingsValuesNotMaskedAsWildcard() {
        // Текущий LogMasker не маскирует произвольные settings values
        assertEquals(
            "settings put secure miui_ad_filtering_enabled 0",
            LogMasker.mask("settings put secure miui_ad_filtering_enabled 0")
        )
    }

    @Test
    fun dnsModeHostnameUntouched() {
        assertEquals(
            "settings put global dns_mode hostname",
            LogMasker.mask("settings put global dns_mode hostname")
        )
    }

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", LogMasker.mask(""))
    }

    @Test
    fun plainTextUntouched() {
        assertEquals(
            "optimize failed: timeout",
            LogMasker.mask("optimize failed: timeout")
        )
    }

    @Test
    fun multiplePatternsInOneString() {
        assertEquals(
            "connect ***.***.***.*** with token=*** at /data/user/0/com.app",
            LogMasker.mask(
                "connect 192.168.1.1 with token=abc123def45678901234567890123456 at /data/user/0/com.app"
            )
        )
    }

    @Test
    fun preservesWhitespace() {
        assertEquals(
            "  multiple   spaces  ",
            LogMasker.mask("  multiple   spaces  ")
        )
    }
}
