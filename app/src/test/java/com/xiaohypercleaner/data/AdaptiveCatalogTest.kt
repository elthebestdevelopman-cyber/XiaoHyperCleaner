package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Тесты адаптивного каталога [AdaptiveCatalog].
 *
 * Проверяют:
 * - парсинг assets/catalog/adaptive_catalog.json;
 * - мердж searchTexts / drillPath / additionalToggles / confirmTexts без дублей;
 * - фолбэк к defaults, когда шага нет в uiSteps;
 * - фильтрацию по установленным пакетам и приоритизацию региона (global-first).
 *
 * Требуется Robolectric: AdaptiveCatalog читает ассеты, установка пакетов
 * симулируется через ShadowPackageManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdaptiveCatalogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Кэши мерджа ключуются по stepId; сбрасываем между тестами, чтобы
        // разные значения defaults не «слипались» через общий ключ кэша.
        AdaptiveCatalog.clearCache()
    }

    private fun globalProfile(): RomProfile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V14.0.0",
        hyperOsHint = false,
        isTablet = false
    )

    @Test
    fun `catalog loads and appends search texts`() {
        val merged = AdaptiveCatalog.mergeSearchTexts(context, "carousel", listOf("MY_BASE"))

        assertEquals("MY_BASE", merged.first())
        assertTrue(
            merged.containsAll(listOf("Карусель обоев", "Wallpaper Carousel", "Glance", "壁纸轮播"))
        )
    }

    @Test
    fun `search texts do not duplicate base and catalog values`() {
        val merged = AdaptiveCatalog.mergeSearchTexts(context, "carousel", listOf("Карусель обоев"))

        assertEquals(4, merged.size)
        assertEquals(1, merged.count { it == "Карусель обоев" })
    }

    @Test
    fun `search texts return defaults when step absent from catalog`() {
        val defaults = listOf("Первый", "Второй")
        val merged = AdaptiveCatalog.mergeSearchTexts(context, "ux_program", defaults)

        assertEquals(defaults, merged)
    }

    @Test
    fun `drill path appends catalog levels after base`() {
        val merged = AdaptiveCatalog.mergeDrillPath(context, "browser_sys", listOf(listOf("BASE")))

        assertEquals(4, merged.size)
        assertEquals(listOf("BASE"), merged[0])
        assertTrue(merged[1].contains("Профиль"))
        assertTrue(merged[2].any { it == "⚙" || it == "⚙️" || it == "Settings" })
    }

    @Test
    fun `additional toggles merge base and catalog`() {
        val merged = AdaptiveCatalog.mergeAdditionalToggles(context, "themes", listOf("BASE"))

        assertTrue(merged.contains("BASE"))
        assertTrue(merged.contains("Персональные рекомендации"))
        assertTrue(merged.contains("Personalized recommendations"))
    }

    @Test
    fun `confirm texts fall back to defaults when catalog has none`() {
        val defaults = listOf("OK", "Отключить")
        val merged = AdaptiveCatalog.mergeConfirmTexts(context, "security_sys", defaults)

        assertEquals(defaults, merged)
    }

    @Test
    fun `resolved package is null when nothing installed`() {
        val resolved = AdaptiveCatalog.resolveInstalledPackageForGroup(context, "market", globalProfile())

        assertNull(resolved)
    }

    @Test
    fun `resolved package prefers installed global package`() {
        installPackage("com.xiaomi.market")
        installPackage("com.mi.global.market")

        val resolved = AdaptiveCatalog.resolveInstalledPackageForGroup(context, "market", globalProfile())

        assertEquals("com.mi.global.market", resolved)
    }

    @Test
    fun `packages for step return only installed packages`() {
        installPackage("com.mi.globalbrowser")

        val installed = AdaptiveCatalog.packagesForStep(
            context,
            "browser_sys",
            listOf("com.mi.globalbrowser", "com.miui.browser"),
            globalProfile()
        )

        assertEquals(listOf("com.mi.globalbrowser"), installed)
    }

    private fun installPackage(packageName: String) {
        Shadows.shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { this.packageName = packageName }
        )
    }
}