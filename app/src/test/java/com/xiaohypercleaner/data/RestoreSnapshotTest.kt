package com.xiaohypercleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Снапшот отката: `checked_before` простых тумблеров и обратная совместимость.
 *
 * Старые снапшоты (без поля `simpleToggleStates`) обязаны читаться: откат
 * в этом случае работает по прежней логике (инверсия targetChecked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RestoreSnapshotTest {

    @Test
    fun `simple toggle states survive json round trip`() {
        val snapshot = RestoreSnapshot(
            settings = mapOf("secure user_experience_program" to "1"),
            dnsApplied = true,
            dnsMode = "hostname",
            dnsHost = "dns.example",
            simpleToggleStates = mapOf("carousel" to true, "ads_personalization" to false)
        )

        val restored = RestoreSnapshot.fromJson(snapshot.toJson())

        assertEquals(snapshot, restored)
    }

    @Test
    fun `legacy snapshot without toggle states parses with empty map`() {
        val legacy = """
            {"settings":{"secure limit_ad_tracking":"0"},"dnsApplied":false,"dnsMode":null,"dnsHost":null}
        """.trimIndent()

        val restored = RestoreSnapshot.fromJson(legacy)

        assertEquals("должны читаться настройки старого формата", "0", restored?.settings?.get("secure limit_ad_tracking"))
        assertTrue("миграция: откат по инверсии target", restored?.simpleToggleStates?.isEmpty() == true)
    }

    @Test
    fun `boolean values are not lost for unchecked toggles`() {
        val snapshot = RestoreSnapshot(
            settings = emptyMap(),
            dnsApplied = false,
            dnsMode = null,
            dnsHost = null,
            simpleToggleStates = mapOf("themes" to false)
        )

        val restored = RestoreSnapshot.fromJson(snapshot.toJson())

        assertFalse(restored?.simpleToggleStates?.get("themes") ?: true)
    }
}