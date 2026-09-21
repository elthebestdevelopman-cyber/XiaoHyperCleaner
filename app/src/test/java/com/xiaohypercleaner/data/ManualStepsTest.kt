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
 * Ручная памятка каталога: состав, паритет семи локалей и нейтральная лексика.
 *
 * Памятка — user-facing текст, поэтому обязана быть непустой во всех локалях и
 * не содержать запрещённых токенов про рекламные блокировщики.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ManualStepsTest {

    private val expectedIds = listOf(
        "package_installer",
        "hyperos3_search",
        "hyperos3_carousel_disable",
        "region_change",
        "daemon_warning",
        "dns_pointer",
        "ads_identity_google"
    )

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    @Test
    fun `every manual step has title and body in all seven locales`() {
        val original = Locale.getDefault()
        try {
            SemanticCatalog.LOCALES.forEach { lang ->
                Locale.setDefault(Locale(lang))
                val steps = SemanticCatalog.manualSteps()
                assertEquals("состав памятки (${lang})", expectedIds, steps.map { it.id })
                steps.forEach { step ->
                    assertTrue("$lang/${step.id}: пустой заголовок", step.title.isNotBlank())
                    assertTrue("$lang/${step.id}: пустое описание", step.body.isNotBlank())
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `warning items are exactly the risky ones`() {
        val warnings = SemanticCatalog.manualSteps().filter { it.warning }.map { it.id }
        assertEquals(
            listOf(
                "hyperos3_carousel_disable",
                "region_change",
                "daemon_warning",
                // Сброс/удаление идентификатора персонализации необратим и влияет на все
                // приложения — автоматизация этого не делает, только памятка.
                "ads_identity_google"
            ),
            warnings
        )
    }

    @Test
    fun `manual texts avoid forbidden vocabulary`() {
        val forbidden = listOf("реклам", "advert", "adblock", "ad block")
        val original = Locale.getDefault()
        try {
            listOf("ru", "en").forEach { lang ->
                Locale.setDefault(Locale(lang))
                SemanticCatalog.manualSteps().forEach { step ->
                    val text = "${step.title} ${step.body}".lowercase(Locale.ROOT)
                    forbidden.forEach { token ->
                        assertFalse("$lang/${step.id}: запрещённый токен '$token'", text.contains(token))
                    }
                }
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `never touch packages are the four system services`() {
        assertEquals(
            setOf(
                "com.miui.daemon",
                "com.miui.guardprovider",
                "com.miui.securitycore",
                "com.xiaomi.simactivate.service"
            ),
            SemanticCatalog.neverTouchPackages()
        )
    }
}