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
 * Старые снапшоты (без поля `simpleToggleStates`) обязаны читаться, но откат по ним
 * больше не выдумывает состояние: значения нет — шаг получает третий исход
 * `unreadable` (список невосстановленных), пока пользователь не сделает новый прогон.
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
        assertTrue("миграция: снапшот без значений не даёт подставить инверсию target", restored?.simpleToggleStates?.isEmpty() == true)
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

    @Test
    fun `disabled packages survive json round trip`() {
        val snapshot = RestoreSnapshot(
            settings = emptyMap(),
            dnsApplied = false,
            dnsMode = null,
            dnsHost = null,
            disabledPackages = listOf("com.miui.analytics", "com.miui.systemAdSolution")
        )

        val restored = RestoreSnapshot.fromJson(snapshot.toJson())

        assertEquals(snapshot, restored)
        assertEquals(
            listOf("com.miui.analytics", "com.miui.systemAdSolution"),
            restored?.disabledPackages
        )
    }

    @Test
    fun `legacy snapshot without disabled packages parses with empty list`() {
        val legacy = """
            {"settings":{},"dnsApplied":false,"dnsMode":null,"dnsHost":null,"simpleToggleStates":{"carousel":true}}
        """.trimIndent()

        val restored = RestoreSnapshot.fromJson(legacy)

        assertTrue(
            "старый снапшот: список пакетов пуст → откат по ServiceRegistry",
            restored?.disabledPackages?.isEmpty() == true
        )
        assertEquals(true, restored?.simpleToggleStates?.get("carousel"))
    }

    @Test
    fun `toggle record keeps the earliest checked_before and marks the step once`() {
        // D2 (прогон rmvgvhbzu): msa переключился, но вердикт не прочитал состояние — шаг
        // обязан остаться в списке отката, а checked_before первого прогона не перезаписываться.
        val first = RestoreSnapshot(
            settings = emptyMap(),
            dnsApplied = false,
            dnsMode = null,
            dnsHost = null
        ).withSimpleToggle("msa", checkedBefore = true)

        val second = first.withSimpleToggle("msa", checkedBefore = false)

        assertEquals("первое исходное значение побеждает", true, second.simpleToggleStates["msa"])
        assertEquals("шаг помечен один раз", setOf("msa"), second.simpleToggledSteps)
    }

    @Test
    fun `extra target records checked_before without joining the reverse list`() {
        // Ключ `<stepId>#extra:<подпись>` не шаг отката: он возвращается внутри своего шага
        // (ReverseExtras), поэтому в списке отката ему делать нечего.
        val snapshot = RestoreSnapshot(
            settings = emptyMap(),
            dnsApplied = false,
            dnsMode = null,
            dnsHost = null
        ).withSimpleToggle("carousel#extra:Реклама", checkedBefore = true, markToggled = false)

        assertEquals(true, snapshot.simpleToggleStates["carousel#extra:Реклама"])
        assertTrue("доп. цель не попадает в список отката", snapshot.simpleToggledSteps.isEmpty())
    }

    @Test
    fun `simple toggled steps survive json round trip`() {
        val snapshot = RestoreSnapshot(
            settings = emptyMap(),
            dnsApplied = false,
            dnsMode = null,
            dnsHost = null,
            simpleToggleStates = mapOf("msa" to true),
            simpleToggledSteps = setOf("msa", "carousel")
        )

        val restored = RestoreSnapshot.fromJson(snapshot.toJson())

        assertEquals(snapshot, restored)
        assertEquals(setOf("msa", "carousel"), restored?.simpleToggledSteps)
    }

    @Test
    fun `legacy snapshot without toggled steps parses with empty set`() {
        val legacy = """
            {"settings":{},"dnsApplied":false,"dnsMode":null,"dnsHost":null,"simpleToggleStates":{"carousel":true}}
        """.trimIndent()

        val restored = RestoreSnapshot.fromJson(legacy)

        assertTrue(
            "старый снапшот: список отката берётся из легаси-ключа",
            restored?.simpleToggledSteps?.isEmpty() == true
        )
        assertEquals("значения старого снапшота не теряются", true, restored?.simpleToggleStates?.get("carousel"))
    }
}