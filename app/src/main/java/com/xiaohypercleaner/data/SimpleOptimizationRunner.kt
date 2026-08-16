package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

@Suppress("DEPRECATION")
class SimpleOptimizationRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"
        private const val WAIT_FOR_SCREEN_MS = 1500L
        private const val WAIT_AFTER_CLICK_MS = 600L
        private const val MAX_TREE_VISITED = 1000

        /**
         * Все возможные имена Switch-элементов на MIUI/HyperOS/AOSP.
         * Включены кастомные Xiaomi классы, которые часто используются.
         */
        private val SWITCH_CLASS_KEYWORDS = listOf(
            "Switch", "Toggle", "CheckBox",
            "SwitchCompat", "AppCompatSwitch", "ToggleSwitch",
            "SwitchEx", "MiuiSwitch", "CompoundButton"
        )
    }

    data class StepResult(
        val stepId: String,
        val success: Boolean,
        val skipped: Boolean = false,
        val reason: String? = null
    )

    // ═══════════════════════════════════════════════════════════════
    // ГЛАВНЫЙ МЕТОД — выполняет один шаг оптимизации
    // ═══════════════════════════════════════════════════════════════
    suspend fun executeStep(step: SimpleSteps.Step): StepResult {
        AppLog.i(TAG, "Executing step: ${step.id}")

        // 1. Пробуем открыть экран — перебираем все intent-ы
        if (!tryOpenScreen(step)) {
            AppLog.e(TAG, "Step ${step.id}: ALL intents failed to open screen")
            return StepResult(step.id, false, reason = "all_intents_failed")
        }

        delay(WAIT_FOR_SCREEN_MS)

        // 2. Ищем switch: по тексту → первый на странице → первый Toggle
        val switchNode = findSwitchNode(step.searchTexts)
            ?: run {
                AppLog.w(TAG, "Step ${step.id}: switch not found by texts, trying fallback")
                findFirstSwitchOnPage()
            }

        if (switchNode == null) {
            AppLog.w(TAG, "Step ${step.id}: no switch found at all")
            return StepResult(step.id, false, reason = "switch_not_found")
        }

        AppLog.i(
            TAG,
            "Step ${step.id}: found switch, isChecked=${switchNode.isChecked}, clickable=${switchNode.isClickable}"
        )

        // 3. Уже в целевом состоянии — шаг выполнен
        if (switchNode.isChecked == step.targetChecked) {
            AppLog.i(TAG, "Step ${step.id}: already in target state")
            return StepResult(step.id, true)
        }

        // 4. Кликаем — с множеством fallback-ов
        if (!clickSwitch(switchNode)) {
            AppLog.w(TAG, "Step ${step.id}: all click attempts failed")
            return StepResult(step.id, false, reason = "click_failed")
        }

        AppLog.i(TAG, "Step ${step.id}: clicked")
        delay(WAIT_AFTER_CLICK_MS)

        // 5. Проверяем результат
        val switchedNode = findSwitchNode(step.searchTexts) ?: findFirstSwitchOnPage()
        val newState = switchedNode?.isChecked ?: !switchNode.isChecked
        val success = newState == step.targetChecked

        AppLog.i(
            TAG,
            "Step ${step.id}: new state=$newState, target=${step.targetChecked}, success=$success"
        )
        return StepResult(step.id, success, reason = if (!success) "state_not_changed" else null)
    }

    // ═══════════════════════════════════════════════════════════════
    // ОТКРЫТИЕ ЭКРАНА — перебирает все intent-ы шага
    // ═══════════════════════════════════════════════════════════════
    private fun tryOpenScreen(step: SimpleSteps.Step): Boolean {
        for (intent in step.intents) {
            try {
                service.startActivity(intent)
                AppLog.i(
                    TAG,
                    "Step ${step.id}: opened screen with intent ${intent.action ?: intent.component}"
                )
                return true
            } catch (e: ActivityNotFoundException) {
                AppLog.w(
                    TAG,
                    "Step ${step.id}: intent not found: ${intent.action ?: intent.component}"
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Step ${step.id}: intent failed: ${e.message}")
            }
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // КЛИК — множество способов, включая клик по координатам
    // ═══════════════════════════════════════════════════════════════
    private fun clickSwitch(node: AccessibilityNodeInfo): Boolean {
        // Способ 1: клик на самом node
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                AppLog.i(TAG, "clicked self")
                return true
            }
        }

        // Способ 2: клик на родителях (до 5 уровней)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    AppLog.i(TAG, "clicked parent at depth $depth")
                    return true
                }
            }
            parent = parent.parent
            depth++
        }

        // Способ 3: КЛИК ПО КООРДИНАТАМ через dispatchGesture (последний шанс)
        // Работает даже когда clickable=false — эмулирует тап пальцем
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val result = clickByCoordinates(node)
            if (result) {
                AppLog.i(TAG, "clicked by coordinates (gesture)")
                return true
            }
        }

        return false
    }

    /**
     * Клик по координатам через AccessibilityService.dispatchGesture.
     * Эмулирует реальный тап пальцем по центру элемента.
     * Это "последняя линия обороны" когда node не clickable.
     */
    private fun clickByCoordinates(node: AccessibilityNodeInfo): Boolean {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val x = ((rect.left + rect.right) / 2).toFloat()
            val y = ((rect.top + rect.bottom) / 2).toFloat()

            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AppLog.w(TAG, "clickByCoordinates failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ПОИСК SWITCH ПО ТЕКСТАМ
    // ═══════════════════════════════════════════════════════════════
    private fun findSwitchNode(texts: List<String>): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            for (text in texts) {
                val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
                for (node in nodes) {
                    val switch = findSwitchInHierarchy(node)
                    if (switch != null) return switch
                }
            }
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "findSwitchNode error: ${e.message}")
            null
        }
    }

    /**
     * FALLBACK: находит первый Switch/Toggle на странице.
     * Критично для MIUI где главный switch уведомлений всегда первый.
     * Ищет по ключевым словам в className (расширенный список).
     */
    private fun findFirstSwitchOnPage(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            findByClassName(root, SWITCH_CLASS_KEYWORDS)
        } catch (e: Exception) {
            AppLog.w(TAG, "findFirstSwitchOnPage error: ${e.message}")
            null
        }
    }

    /**
     * Ищет Switch поднимаясь по иерархии от найденного по тексту узла.
     * Ищет среди детей родителей (до 5 уровней вверх).
     */
    private fun findSwitchInHierarchy(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            val className = current.className?.toString() ?: ""

            // Сам узел — switch?
            if (isSwitchClass(className)) return current

            // Ищем среди детей родителя
            val parent = current.parent ?: break
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val childClass = child.className?.toString() ?: ""
                if (isSwitchClass(childClass)) return child
            }

            current = parent
            depth++
        }
        return null
    }

    /**
     * Проверяет, является ли className switch/toggle.
     * Работает со всеми вариантами включая кастомные MIUI.
     */
    private fun isSwitchClass(className: String): Boolean {
        return SWITCH_CLASS_KEYWORDS.any { keyword ->
            className.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Поиск по дереву элементов. Ищет первый узел, чей className
     * содержит любое из ключевых слов (Switch, Toggle, CheckBox, etc).
     * Использует DFS с ограничением на количество посещённых узлов.
     */
    private fun findByClassName(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_TREE_VISITED) {
            val node = stack.removeLast()
            visited++

            val nodeClass = node.className?.toString() ?: ""
            if (keywords.any { nodeClass.contains(it, ignoreCase = true) }) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}