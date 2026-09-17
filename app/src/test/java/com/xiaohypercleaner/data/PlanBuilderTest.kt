package com.xiaohypercleaner.data

import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Префильтр плана: ложные `app_not_installed` (P0 package visibility).
 *
 * Регресс-кейс: установленный GetApps (`com.xiaomi.mipicks`) и App Vault
 * (`com.mi.android.globalminusscreen`) обязаны попадать в план, а не
 * исключаться по устаревшим market/personalassistant-именам.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PlanBuilderTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val profile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V816",
        hyperOsHint = false,
        isTablet = false
    )

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(context)
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    private fun install(pkg: String) {
        val info = PackageInfo().apply { packageName = pkg }
        Shadows.shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `installed mipicks keeps getapps steps in plan`() {
        install("com.xiaomi.mipicks")

        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertTrue("getapps должен попасть в план", plan.contains("getapps"))
        assertTrue("notif_getapps должен попасть в план", plan.contains("notif_getapps"))
        assertFalse("messages_sys: Xiaomi Messages нет", plan.contains("messages_sys"))
    }

    @Test
    fun `installed globalminusscreen keeps appvault steps in plan`() {
        install("com.mi.android.globalminusscreen")

        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertTrue(plan.contains("appvault_services"))
        assertTrue(plan.contains("appvault_about"))
        assertTrue(plan.contains("notif_appvault"))
    }

    @Test
    fun `notif transparency off excludes notification steps`() {
        install("com.xiaomi.mipicks")
        install("com.mi.android.globalminusscreen")

        val plan = PlanBuilder.build(context, profile, notifTransparency = false).map { it.id }

        assertFalse("notif_* исключены при выключенной прозрачности", plan.any { it.startsWith("notif_") })
        assertTrue("обычные шаги остаются", plan.contains("getapps"))
    }

    @Test
    fun `unknown package steps are excluded`() {
        val plan = PlanBuilder.build(context, profile).map { it.id }

        // GetApps/App Vault не установлены в этом сценарии — шаги вне плана.
        assertFalse(plan.contains("getapps"))
        assertFalse(plan.contains("appvault_services"))
    }
}