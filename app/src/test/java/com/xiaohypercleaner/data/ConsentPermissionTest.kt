package com.xiaohypercleaner.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Runtime-permission: кому адресован запрос и разрешаем ли мы его.
 *
 * Правило: deny по умолчанию, allow — по политике каталога (`allowOverrides`)
 * ИЛИ когда доступ просят для приложения-цели шага. Второе критично для шагов,
 * которым без доступа не отдают свой экран: Проводник после отказа показывал
 * «Нет доступа к файлам. Разрешите Проводнику доступ к файлам в разделе Настройки»
 * (прогон rmua0pt7i → `clear_button_not_found`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentPermissionTest {

    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun classify(screenText: String, stepId: String, labels: List<String> = emptyList()) =
        ConsentWallHandler.classify(
            screenText = screenText,
            ownerPackage = "com.android.permissioncontroller",
            stepPackages = listOf("com.mi.android.globalFileexplorer"),
            stepId = stepId,
            stepConfirmTexts = emptyList(),
            stepConsentTexts = emptyList(),
            alertDialog = false,
            stepLabels = labels
        )

    @Test
    fun `permission asked for the step app is allowed`() = runTest {
        val action = classify(
            screenText = "Разрешить приложению Проводник доступ к фото и мультимедиа на " +
                "устройстве? ЗАПРЕТИТЬ РАЗРЕШИТЬ",
            stepId = "filemanager",
            labels = listOf("Проводник")
        )

        assertEquals("permission", action?.kind)
        assertEquals("allow", action?.decision)
        assertEquals("target_app", action?.cause)
    }

    @Test
    fun `permission for a foreign app stays denied`() = runTest {
        val action = classify(
            screenText = "Разрешить приложению Камера доступ к местоположению? ЗАПРЕТИТЬ РАЗРЕШИТЬ",
            stepId = "filemanager",
            labels = listOf("Проводник")
        )

        assertEquals("permission", action?.kind)
        assertEquals("deny", action?.decision)
        assertEquals("deny_default", action?.cause)
    }

    @Test
    fun `unknown dialog leads to no action`() = runTest {
        assertNull(classify(screenText = "Настройки Отпечатки", stepId = "filemanager"))
    }
}