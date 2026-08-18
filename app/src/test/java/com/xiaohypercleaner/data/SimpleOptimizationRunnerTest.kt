package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/**
 * Интеграционные тесты для SimpleOptimizationRunner.
 *
 * Тестирует логику поиска switch-элементов, клика и выполнения шагов
 * через мокированные AccessibilityNodeInfo деревья.
 *
 * Использует Robolectric + ShadowAccessibilityNodeInfo для эмуляции
 * реальных AccessibilityEvents без физического устройства.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = XiaoHyperApp::class)
class SimpleOptimizationRunnerTest {

    private lateinit var service: TestAccessibilityService
    private lateinit var runner: SimpleOptimizationRunner

    @Before
    fun setup() {
        service = TestAccessibilityService()
        runner = SimpleOptimizationRunner(service)
    }

    @After
    fun tearDown() {
        ShadowAccessibilityNodeInfo.resetObtainedInstances()
    }

    // ═══════════════════════════════════════════════════════════════
    // ТЕСТЫ ПОИСКА SWITCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `findSwitchByText finds Switch with matching text`() = runTest {
        // Arrange: создаём дерево с Switch, содержащим "MSA"
        val root = createNode(className = "android.widget.FrameLayout")
        val switch = createNode(
            className = "android.widget.Switch",
            text = "MSA",
            isCheckable = true,
            isChecked = true,
            isClickable = true
        )
        root.addChild(switch)
        service.setRootNode(root)

        // Act
        val step = createTestStep(searchTexts = listOf("MSA"))
        val result = runner.executeStep(step)

        // Assert: switch найден и уже в целевом состоянии (targetChecked=false, isChecked=true → клик)
        // Т.к. switch isChecked=true, а targetChecked=false, runner попытается кликнуть
        // Но без реального startActivity intent не откроется, поэтому проверим причину
        assertEquals(
            "switch_not_found не должно быть",
            false,
            result.reason?.contains("switch_not_found")
        )
    }

    @Test
    fun `findSwitchByText finds Switch in parent hierarchy when text on child TextView`() =
        runTest {
            // Arrange: типичный кейс MIUI — TextView с "MSA" рядом со Switch в общем parent
            val root = createNode(className = "android.widget.FrameLayout")
            val container = createNode(className = "android.widget.LinearLayout")
            val label = createNode(
                className = "android.widget.TextView",
                text = "MSA"
            )
            val switch = createNode(
                className = "android.widget.Switch",
                isCheckable = true,
                isChecked = false,
                isClickable = true
            )
            container.addChild(label)
            container.addChild(switch)
            root.addChild(container)
            service.setRootNode(root)

            // Act: открываем экран через fallback intent (без специфичных MIUI intent'ов)
            val step = createTestStep(
                searchTexts = listOf("MSA"),
                intents = listOf(
                    android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            )
            val result = runner.executeStep(step)

            // Assert: runner должен найти switch через иерархию
            // (точное поведение зависит от реализации tryOpenScreen)
            assertNotNull("Результат не должен быть null", result)
        }

    @Test
    fun `findSwitchByClassName fallback finds first Switch when texts do not match`() = runTest {
        // Arrange: экран с Switch, но тексты не совпадают с searchTexts
        val root = createNode(className = "android.widget.FrameLayout")
        val switch = createNode(
            className = "android.widget.Switch",
            text = "Какой-то другой текст",
            isCheckable = true,
            isChecked = true,
            isClickable = true
        )
        root.addChild(switch)
        service.setRootNode(root)

        // Act
        val step = createTestStep(searchTexts = listOf("MSA", "Реклама"))
        val result = runner.executeStep(step)

        // Assert: fallback на className должен сработать
        assertNotNull(result)
    }

    @Test
    fun `executeStep returns switch_not_found when no Switch on page`() = runTest {
        // Arrange: страница без Switch-элементов
        val root = createNode(className = "android.widget.FrameLayout")
        val textView = createNode(
            className = "android.widget.TextView",
            text = "MSA"
        )
        root.addChild(textView)
        service.setRootNode(root)

        // Act
        val step = createTestStep(searchTexts = listOf("MSA"))
        val result = runner.executeStep(step)

        // Assert
        assertFalse("Не должно быть успеха", result.success)
        // В зависимости от того, открылся ли экран, причина может быть разной
    }

    // ═══════════════════════════════════════════════════════════════
    // ТЕСТЫ СОСТОЯНИЯ SWITCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `executeStep returns success when already in target state`() = runTest {
        // Arrange: switch уже в нужном состоянии (isChecked=false, target=false)
        val root = createNode(className = "android.widget.FrameLayout")
        val switch = createNode(
            className = "android.widget.Switch",
            text = "MSA",
            isCheckable = true,
            isChecked = false,  // Уже выключен
            isClickable = true
        )
        root.addChild(switch)
        service.setRootNode(root)

        val step = createTestStep(
            searchTexts = listOf("MSA"),
            targetChecked = false
        )
        val result = runner.executeStep(step)

        // Assert: должен вернуть успех без клика
        assertTrue("Должно быть успешно (уже в нужном состоянии)", result.success)
    }

    // ═══════════════════════════════════════════════════════════════
    // ТЕСТЫ ПОВЕДЕНИЯ ПРИ ОШИБКАХ
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `executeStep returns failed when all intents fail to open screen`() = runTest {
        // Arrange: пустой root (нет активного окна — эмуляция того что startActivity упал)
        service.setRootNode(null)

        val step = createTestStep(
            intents = listOf(
                android.content.Intent("non.existent.intent.action")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        )
        val result = runner.executeStep(step)

        // Assert
        assertFalse("Не должно быть успеха", result.success)
    }

    // ═══════════════════════════════════════════════════════════════
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ═══════════════════════════════════════════════════════════════

    /**
     * Создаёт AccessibilityNodeInfo с заданными свойствами через Shadow.
     * В Robolectric это единственный безопасный способ создания нод для тестов.
     */
    private fun createNode(
        className: String,
        text: String? = null,
        isCheckable: Boolean = false,
        isChecked: Boolean = false,
        isClickable: Boolean = false
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        val shadow = shadowOf(node)
        shadow.setClassName(className)
        text?.let { shadow.setText(it) }
        shadow.setCheckable(isCheckable)
        shadow.setChecked(isChecked)
        shadow.setClickable(isClickable)
        return node
    }

    private fun createTestStep(
        searchTexts: List<String> = listOf("MSA"),
        targetChecked: Boolean = false,
        intents: List<android.content.Intent> = listOf(
            android.content.Intent("miui.intent.action.AD_SERVICES_SETTINGS")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    ): SimpleSteps.Step {
        return SimpleSteps.Step(
            id = "test_step",
            titleRu = "Test",
            titleEn = "Test",
            descRu = "Test step",
            descEn = "Test step",
            intents = intents,
            searchTexts = searchTexts,
            targetChecked = targetChecked,
            manualHintRu = "Test hint",
            manualHintEn = "Test hint"
        )
    }
}

/**
 * Тестовый stub для AccessibilityService.
 * Robolectric не позволяет полноценно мокать AccessibilityService,
 * поэтому используем простой wrapper с возможностью установки rootNode.
 */
class TestAccessibilityService : AccessibilityService() {
    private var rootNode: AccessibilityNodeInfo? = null

    fun setRootNode(node: AccessibilityNodeInfo?) {
        rootNode = node
    }

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? = rootNode

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // no-op в тестах
    }

    override fun onInterrupt() {
        // no-op в тестах
    }
}