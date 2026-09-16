package com.xiaohypercleaner.data

import android.content.Intent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Порядок навигационных интентов discovery-плана.
 *
 * Вариантные подсказки обязаны идти первыми (поведение cn_hyperos не меняем),
 * найденные сканером — после них; дубликаты компонентов не повторяются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScanOrchestratorTest {

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test
    fun `legacy hints keep priority over discovered intents`() {
        val legacy = listOf(componentIntent("com.miui.player", "com.miui.player.ui.MainActivity"))
        val discovered = listOf(componentIntent("com.miui.player", "com.miui.player.ui.MusicActivity"))
        val plan = ScanOrchestrator.NavigationPlan(
            legacyIntents = legacy,
            discoveredIntents = discovered,
            candidates = emptyList(),
            fromCache = false
        )

        val ordered = plan.orderedIntents()

        assertEquals(2, ordered.size)
        assertEquals("com.miui.player.ui.MainActivity", ordered[0].component?.className)
        assertEquals("com.miui.player.ui.MusicActivity", ordered[1].component?.className)
    }

    @Test
    fun `duplicate components are not retried twice`() {
        val same = componentIntent("com.miui.player", "com.miui.player.ui.MainActivity")
        val plan = ScanOrchestrator.NavigationPlan(
            legacyIntents = listOf(same),
            discoveredIntents = listOf(componentIntent("com.miui.player", "com.miui.player.ui.MainActivity")),
            candidates = emptyList(),
            fromCache = false
        )

        assertEquals(1, plan.orderedIntents().size)
    }

    @Test
    fun `plan without target package returns legacy intents only`() = runTest {
        val legacy = listOf(Intent(android.provider.Settings.ACTION_SETTINGS))
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        val plan = ScanOrchestrator.planNavigation(
            context = context,
            pkg = null,
            legacyIntents = legacy,
            keywords = listOf("recommendations"),
            cache = null
        )

        assertTrue("для системных шагов скан не запускается", plan.discoveredIntents.isEmpty())
        assertEquals(legacy, plan.orderedIntents())
    }

    @Test
    fun `scanner intents target the resolved package`() {
        val candidates = listOf(
            ActivityScanner.ActivityCandidate("com.miui.player.ui.MusicActivity", "Музыка", 90)
        )

        val intents = ScanOrchestrator.buildIntents("com.miui.player", candidates)

        assertEquals(1, intents.size)
        assertEquals("com.miui.player", intents[0].component?.packageName)
        assertEquals("com.miui.player.ui.MusicActivity", intents[0].component?.className)
    }
}