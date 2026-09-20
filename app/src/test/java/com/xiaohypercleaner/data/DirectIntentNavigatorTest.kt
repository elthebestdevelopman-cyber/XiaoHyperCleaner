package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
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
 * Точка входа приложения-шага (прогон rmu8qhjhi).
 *
 * Регресс: launcher-интенты (`MAIN` + `CATEGORY_LAUNCHER`, без `CATEGORY_DEFAULT`)
 * отбраковывались проверкой `MATCH_DEFAULT_ONLY` — у `getapps` оставался только
 * Settings-фолбэк, магазин поднимался последним рубежом и бурение шло по сплэшу
 * (`Intent not available: android.intent.action.MAIN / com.xiaomi.mipicks`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DirectIntentNavigatorTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val profile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V130",
        hyperOsHint = false,
        isTablet = false,
        family = RomFamily.MIUI,
        uiVersion = "13"
    )

    @Before
    fun setUp() {
        // Кэш интентов статический — иначе тесты делят один результат шага.
        DirectIntentNavigator.clearCache()
    }

    @After
    fun tearDown() {
        DirectIntentNavigator.clearCache()
    }

    /** Пакет с launcher-активностью, объявленной без CATEGORY_DEFAULT (как GetApps). */
    private fun installLauncherOnly(pkg: String) {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        shadowPm.installPackage(PackageInfo().apply { packageName = pkg })
        val component = ComponentName(pkg, "$pkg.Launcher")
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun getappsStep() = SimpleSteps.ALL.first { it.id == "getapps" }

    @Test
    fun `installed getapps keeps a launcher entry in the chain`() {
        installLauncherOnly("com.xiaomi.mipicks")

        val intents = DirectIntentNavigator.buildIntentsForStep(
            context,
            getappsStep(),
            "com.xiaomi.mipicks",
            profile
        )

        assertTrue(
            "у getapps должна быть рабочая точка входа в магазин, а не только фолбэк",
            intents.any { it.`package` == "com.xiaomi.mipicks" }
        )
    }

    @Test
    fun `absent getapps package has no launcher entry`() {
        val intents = DirectIntentNavigator.buildIntentsForStep(
            context,
            getappsStep(),
            "com.xiaomi.mipicks",
            profile
        )

        assertFalse(
            "неустановленный магазин не должен давать интент-призрак",
            intents.any { it.`package` == "com.xiaomi.mipicks" }
        )
    }
}