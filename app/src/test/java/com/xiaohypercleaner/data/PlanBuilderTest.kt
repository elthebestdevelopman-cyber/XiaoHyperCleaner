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

    @Test
    fun `folder step is excluded when the launcher is not MIUI`() {
        // Шаг папок рабочего стола имеет смысл только в лаунчере MIUI/HyperOS.
        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertFalse(
            "папки рабочего стола только для лаунчера MIUI",
            plan.contains("folder_recommendations")
        )
    }

    @Test
    fun `hyperos3 keeps security and cleaner steps in plan`() {
        // Красный список не исключает UI-шаги: на HyperOS 3 Security = com.miui.securitycore.
        install("com.miui.securitycore")
        val hyperOs3 = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "OS3.0.1.0",
            hyperOsHint = true,
            isTablet = false,
            family = RomFamily.HYPEROS,
            uiVersion = "3"
        )

        val plan = PlanBuilder.build(context, hyperOs3).map { it.id }

        assertTrue("security_sys остаётся в плане", plan.contains("security_sys"))
        assertTrue("cleaner остаётся в плане", plan.contains("cleaner"))
    }

    @Test
    fun `destructive action over never touch package is forbidden`() {
        val neverTouch = SemanticCatalog.neverTouchPackages()
        assertTrue("красный список загружен", neverTouch.isNotEmpty())

        assertTrue(
            "disable com.miui.securitycore запрещён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.TOGGLE,
                destructiveAction = "disable",
                packages = listOf("com.miui.securitycore"),
                neverTouch = neverTouch
            )
        )
        assertTrue(
            "clear data over never-touch package запрещён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.CLEAR_DATA_DECLINE,
                destructiveAction = null,
                packages = listOf("com.miui.daemon", "com.miui.securitycenter"),
                neverTouch = neverTouch
            )
        )
        assertFalse(
            "обычный тумблер в приложении пакета разрешён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.TOGGLE,
                destructiveAction = null,
                packages = listOf("com.miui.securitycore"),
                neverTouch = neverTouch
            )
        )
        assertFalse(
            "clear data обычного приложения разрешён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.CLEAR_DATA_DECLINE,
                destructiveAction = null,
                packages = listOf("com.mi.android.globalFileexplorer"),
                neverTouch = neverTouch
            )
        )
    }
}