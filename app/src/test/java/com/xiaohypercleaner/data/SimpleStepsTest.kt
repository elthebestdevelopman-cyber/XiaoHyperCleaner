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
        assertFalse(profileLevel.any { it.length <= 2 })
        assertTrue(profileLevel.any { it.contains("Профиль") || it.contains("Profile") })
    }

    @Test
    fun `all steps with required packages have non-empty ids`() {
        SimpleSteps.ALL.forEach { step ->
            assertTrue(step.id.isNotBlank())
            if (step.requiredPackages.isNotEmpty()) {
                assertTrue(
                    "step ${step.id} has blank package",
                    step.requiredPackages.none { it.isBlank() }
                )
            }
        }
    }
}
