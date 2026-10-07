package com.xiaohypercleaner.service

import com.xiaohypercleaner.data.SemanticCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Кнопки установки/обновления/скачивания не нажимаются НИКОГДА.
 *
 * Инцидент 06.10.2026: тап разведки по мастеру GetApps попал в «СКАЧАТЬ(3077.1MB)»,
 * и магазин установил 11 приложений пачкой (дампы `diag-dumps/fresh/getapps_*`).
 * Тест фиксирует инвариант: ни консент, ни маршрут, ни тап по подписи не выбирают
 * такую кнопку, а тексты пропуска мастера не могут пересекаться с этими подписями.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerInstallGuardTest {

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

    @Test
    fun `install, update and download labels are blocked`() {
        for (label in listOf(
            "СКАЧАТЬ(3077.1MB)", "Скачать", "Установить", "Обновить", "Обновить все",
            "Загрузить", "Install", "Update", "Download"
        )) {
            assertTrue("подпись '$label' обязана быть заблокирована", SimpleRunner.isInstallBlockedLabel(label))
        }
    }

    @Test
    fun `safe labels are not blocked`() {
        for (label in listOf("Пропустить", "Пропуск", "Настройки", "Отмена", "Нет, спасибо", "OK", "")) {
            assertFalse("подпись '$label' не установочная", SimpleRunner.isInstallBlockedLabel(label))
        }
    }

    /**
     * Инвариант каталога: подписи пропуска мастера не пересекаются с install/update —
     * иначе «закрытие промо» превратилось бы в нажатие установки.
     */
    @Test
    fun `master skip texts never contain install or update labels`() {
        val skipTexts = SemanticCatalog.masterSkipTextsAllLocales()
        assertTrue("тексты пропуска мастера обязаны быть заданы", skipTexts.isNotEmpty())
        skipTexts.forEach { skip ->
            assertFalse(
                "«$skip» не должен быть подписью установки/обновления",
                SimpleRunner.isInstallBlockedLabel(skip)
            )
        }
    }
}