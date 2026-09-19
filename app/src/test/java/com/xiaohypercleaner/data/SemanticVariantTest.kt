package com.xiaohypercleaner.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Версионные варианты шагов: выбор ветки по RomProfile (MIUI 13 vs HyperOS 2/3),
 * фолбэки для HyperOS 4+ и нераспознанной версии, авторитетный drillPath варианта.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SemanticVariantTest {

    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        // Каталог написан под RU-устройство: маркеры/тексты проверяем в ru.
        Locale.setDefault(Locale.forLanguageTag("ru"))
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun profile(family: RomFamily, ui: String) = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "test-$ui",
        hyperOsHint = family == RomFamily.HYPEROS,
        isTablet = false,
        family = family,
        uiVersion = ui
    )

    @Test
    fun `msa on miui13 drills to personal data without privacy level`() {
        SemanticCatalog.selectVariant(profile(RomFamily.MIUI, "13"))

        assertEquals("miui12_14", SemanticCatalog.selection("msa")?.variant?.id)
        val path = SemanticCatalog.variantDrillPath("msa")
        assertTrue("первый уровень — пароли/биометрия", path.first().any { it.contains("Пароли") || it.contains("Отпечатки") })
        assertTrue("целевой уровень — доступ к личным данным", path.last().any { it.contains("Доступ к личным данным") })
        assertFalse(
            "маршрут msa не должен идти через Конфиденциальность",
            path.flatten().any { it == "Конфиденциальность" }
        )
        assertFalse(
            "маркер Конфиденциальность из шага msa убран",
            SemanticCatalog.screenMarkers("msa").any { it == "Конфиденциальность" }
        )
    }

    @Test
    fun `msa on hyperos2 uses hyperos variant different from miui`() {
        SemanticCatalog.selectVariant(profile(RomFamily.HYPEROS, "2"))

        assertEquals("hyperos1_3", SemanticCatalog.selection("msa")?.variant?.id)
        assertTrue(
            "HyperOS: биометрия первой строкой",
            SemanticCatalog.variantDrillPath("msa").first().first().contains("Отпечатки")
        )
        assertTrue(
            "маркеры экрана от варианта",
            SemanticCatalog.screenMarkers("msa").any { it.contains("Доступ к личным данным") }
        )
    }

    @Test
    fun `filemanager main route is a toggle with clear data fallback`() {
        SemanticCatalog.selectVariant(profile(RomFamily.MIUI, "13"))

        assertEquals(SemanticCatalog.ActionType.TOGGLE, SemanticCatalog.variantControl("filemanager"))
        assertEquals("clear_data_decline", SemanticCatalog.fallbackAction("filemanager"))
        assertTrue(
            "основной путь: меню → Настройки → Информация",
            SemanticCatalog.variantDrillPath("filemanager").last().any { it.contains("Информация") }
        )
    }

    @Test
    fun `appvault on hyperos3 goes through settings with service management target`() {
        SemanticCatalog.selectVariant(profile(RomFamily.HYPEROS, "3"))

        assertEquals("settings", SemanticCatalog.entry("appvault_services"))
        val extras = SemanticCatalog.extraTargets("appvault_services")
        assertEquals(1, extras.size)
        assertTrue(
            "доп. цель: ⋮ → Управление службами",
            extras.first().drillPath.last().any { it.contains("Управление службами") }
        )
    }

    @Test
    fun `hyperos4 falls back to newest hyperos variant`() {
        val variants = SemanticCatalog.step("home_suggestions")!!.variants
        val selection = SemanticCatalog.resolveVariant(
            variants, SemanticCatalog.OsTarget(RomFamily.HYPEROS, 4.0)
        )

        assertEquals("hyperos2_3", selection!!.variant.id)
        assertTrue("HyperOS 4 обслуживается фолбэком", selection.fallback)
    }

    @Test
    fun `unknown rom and miui 11 use fallback variant`() {
        val data = SemanticCatalog.step("home_suggestions")!!.variants
        val unknown = SemanticCatalog.resolveVariant(data, SemanticCatalog.OsTarget(RomFamily.UNKNOWN, 0.0))
        assertEquals("miui12_12_5", unknown!!.variant.id)
        assertTrue(unknown.fallback)

        val msaVariants = SemanticCatalog.step("msa")!!.variants
        val miui11 = SemanticCatalog.resolveVariant(msaVariants, SemanticCatalog.OsTarget(RomFamily.MIUI, 11.0))
        assertEquals("miui12_14", miui11!!.variant.id)
        assertTrue(miui11.fallback)
    }

    @Test
    fun `fallback variant keeps legacy drill path as suggestion`() {
        SemanticCatalog.selectVariant(profile(RomFamily.UNKNOWN, "0"))

        // Неявный фолбэк: путь варианта добавляется к базовым подсказкам, а не заменяет их.
        assertTrue(SemanticCatalog.variantDrillPath("filemanager").isEmpty())
        assertTrue(SemanticCatalog.fallbackDrillPath("filemanager").isNotEmpty())
    }
}