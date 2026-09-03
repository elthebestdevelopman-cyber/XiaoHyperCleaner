package com.xiaohypercleaner.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Регрессии карты Simple Mode: alias пакетов и опасные короткие ключи поиска.
 * Нужен Robolectric — SimpleSteps создаёт Intent в static init.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleStepsTest {

    @Test
    fun `launcher steps include global launcher alias`() {
        val ads = SimpleSteps.ALL.first { it.id == "search_ads" }
        val page = SimpleSteps.ALL.first { it.id == "search_page" }
        assertTrue(ads.requiredPackages.contains("com.mi.android.globallauncher"))
        assertTrue(page.requiredPackages.contains("com.mi.android.globallauncher"))
    }

    @Test
    fun `profile drill path has no single-letter Me or Ya`() {
        val themes = SimpleSteps.ALL.first { it.id == "themes" }
        val profileLevel = themes.drillPath.firstOrNull() ?: emptyList()
        assertFalse(profileLevel.any { it.equals("Я", ignoreCase = true) })
        assertFalse(profileLevel.any { it.equals("Me", ignoreCase = true) })
        assertTrue(profileLevel.any { it.contains("Профиль") || it.contains("Profile") })
    }

    @Test
    fun `notif steps include global market and video aliases`() {
        val getapps = SimpleSteps.ALL.first { it.id == "notif_getapps" }
        val video = SimpleSteps.ALL.first { it.id == "notif_mivideo" }
        assertTrue(getapps.requiredPackages.contains("com.mi.global.market"))
        assertTrue(video.requiredPackages.contains("com.mi.global.video"))
    }

    @Test
    fun `music and messages do not use generic AOSP or Google packages`() {
        val music = SimpleSteps.ALL.first { it.id == "music_sys" }
        val messages = SimpleSteps.ALL.first { it.id == "messages_sys" }
        assertFalse(music.requiredPackages.contains("com.android.music"))
        assertFalse(messages.requiredPackages.contains("com.google.android.apps.messaging"))
    }

    @Test
    fun `manual guide additions exist and have valid settings`() {
        val home = SimpleSteps.ALL.first { it.id == "home_suggestions" }
        assertTrue(home.searchTexts.any { it.contains("Показывать предложения") || it.contains("Show suggestions") })
        assertFalse(home.targetChecked)

        val notifMsa = SimpleSteps.ALL.first { it.id == "notif_msa" }
        assertTrue(notifMsa.requiredPackages.contains("com.miui.msa.global"))
        assertFalse(notifMsa.targetChecked)

        val downloads = SimpleSteps.ALL.first { it.id == "downloads" }
        assertTrue(downloads.searchTexts.contains("Показывать рекламу") || downloads.searchTexts.contains("Show ads"))

        val sec = SimpleSteps.ALL.first { it.id == "security_sys" }
        assertTrue(sec.launchPackage == "com.miui.securitycenter")

        val ads = SimpleSteps.ALL.first { it.id == "ads_personalization" }
        assertTrue(ads.searchTexts.contains("Персонализированная реклама"))

        val carousel = SimpleSteps.ALL.first { it.id == "carousel" }
        assertTrue(carousel.searchTexts.contains("Включить"))

        assertTrue(home.requiredPackages.contains("com.miui.home"))
    }
}
