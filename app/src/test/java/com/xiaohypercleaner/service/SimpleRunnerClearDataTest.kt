package com.xiaohypercleaner.service

import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit-тесты сценария CLEAR_DATA_DECLINE в [SimpleRunner].
 *
 * Проверяют ключевое поведение: очистка данных выполняется как отдельный
 * сценарий и НЕ выдаёт ложный успех, если кнопка «Очистить данные»
 * не найдена или не может быть нажата.
 *
 * АРХИТЕКТУРА:
 * - Robolectric — Android-рантайм (Log, AccessibilityNodeInfo).
 * - Mockito — подмена AdbEnablerService и дерева узлов.
 * - runTest — виртуальное время (delay не тормозит тест).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerClearDataTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner

    @Before
    fun setUp() {
        // Изоляция: executeClearDataDecline вызывает ensureLoaded(service);
        // mock-сервис не должен «отравлять» синглтон каталога для других
        // тестов (AdaptiveCatalogTest), поэтому сбрасываем состояние.
        AdaptiveCatalog.resetForTest()
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
    }

    /** Mock-узел дерева Accessibility: текст, кликабельность, дети. */
    private fun node(
        text: String?,
        clickable: Boolean,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    private fun clearDataStep() = SimpleSteps.Step(
        id = "filemanager",
        titleRu = "Конфиденциальность Проводника",
        titleEn = "File Manager privacy",
        descRu = "Очистка данных Проводника и отклонение телеметрии.",
        descEn = "Clearing File Manager data and declining telemetry.",
        intents = emptyList(),
        searchTexts = emptyList(),
        manualHintRu = "Настройки → Приложения → Проводник → Очистить все данные.",
        manualHintEn = "Settings → Apps → File Manager → Clear all data.",
        actionType = SimpleSteps.ActionType.CLEAR_DATA_DECLINE,
        launchPackage = "com.mi.android.globalFileexplorer",
        confirmTexts = emptyList()
    )

    @Test
    fun `clear data button found tapped and confirmed`() = runTest {
        val clearButton = node("Clear data", clickable = true)
        Mockito.`when`(clearButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            .thenReturn(true)
        val root = node(null, clickable = false, clearButton)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val result = runner.executeClearDataDecline(clearDataStep())

        assertTrue("ожидался успех очистки данных", result.success)
        assertEquals("clear_data_done", result.reason)
    }

    @Test
    fun `non actionable clear button reports honest failure`() = runTest {
        // Текст кнопки виден (экран подтверждён), но узел не кликабельный —
        // сценарий не должен выдавать ложный успех.
        val clearButton = node("Clear data", clickable = false)
        val root = node(null, clickable = false, clearButton)
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val result = runner.executeClearDataDecline(clearDataStep())

        assertFalse("ложный успех недопустим", result.success)
        assertEquals("clear_button_not_found", result.reason)
    }
}
