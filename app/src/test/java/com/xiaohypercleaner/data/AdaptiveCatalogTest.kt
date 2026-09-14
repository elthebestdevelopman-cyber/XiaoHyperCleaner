package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale

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
        AdaptiveCatalog.resetForTest()
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

        assertEquals(merged.size, merged.distinct().size)
        assertEquals(1, merged.count { it == "Карусель обоев" })
        assertEquals("Карусель обоев", merged.first())
    }

    @Test
    fun `search texts return defaults when step absent from catalog`() {
        val defaults = listOf("Первый", "Второй")
        val merged = AdaptiveCatalog.mergeSearchTexts(context, "notif_gamecenter", defaults)

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

    // ── Диспетчер вариантов каталога ────────────────────────────────────

    @Test
    fun `variant dispatcher selects global_ru for global russian non-hyperos device`() {
        withLocale("ru") {
            val variant = AdaptiveCatalog.selectVariant(context, globalProfile())

            assertEquals("global_ru", variant)
            assertEquals("global_ru", AdaptiveCatalog.currentVariant())
        }
    }

    @Test
    fun `variant dispatcher keeps cn_hyperos for non-russian global locale`() {
        withLocale("en") {
            assertEquals("cn_hyperos", AdaptiveCatalog.selectVariant(context, globalProfile()))
        }
    }

    @Test
    fun `variant dispatcher keeps cn_hyperos for china region`() {
        val cnProfile = RomProfile(
            region = RomRegion.CN,
            miuiVersion = "V14.0.0",
            hyperOsHint = false,
            isTablet = false
        )
        withLocale("ru") {
            assertEquals("cn_hyperos", AdaptiveCatalog.selectVariant(context, cnProfile))
        }
    }

    @Test
    fun `variant dispatcher keeps cn_hyperos for hyperos device`() {
        val hyperProfile = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "OS1.0.0",
            hyperOsHint = true,
            isTablet = false
        )
        withLocale("ru") {
            assertEquals("cn_hyperos", AdaptiveCatalog.selectVariant(context, hyperProfile))
        }
    }

    @Test
    fun `global_ru replaces drill path for overridden steps`() {
        withLocale("ru") {
            AdaptiveCatalog.selectVariant(context, globalProfile())

            val merged = AdaptiveCatalog.mergeDrillPath(
                context, "msa", listOf(listOf("DEFAULT_CN_LEVEL"))
            )

            // replaceDrillPath=true: базовый CN-путь отбрасывается целиком.
            assertFalse(merged.any { it == listOf("DEFAULT_CN_LEVEL") })
            assertTrue(merged.any { level -> level.any { it == "Реклама" } })
        }
    }

    @Test
    fun `global_ru inherits cn_hyperos for non-overridden steps`() {
        withLocale("ru") {
            AdaptiveCatalog.selectVariant(context, globalProfile())

            val merged = AdaptiveCatalog.mergeDrillPath(
                context, "browser_sys", listOf(listOf("BASE"))
            )

            // browser_sys не переопределён в global_ru -> берётся из cn_hyperos.
            assertEquals(4, merged.size)
            assertTrue(merged[1].contains("Профиль"))
        }
    }

    /** Выполняет блок с временно установленной локалью (для диспетчера вариантов). */
    private fun withLocale(lang: String, block: () -> Unit) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale(lang))
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
    private fun installPackage(packageName: String) {
        Shadows.shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { this.packageName = packageName }
        )
    }
}