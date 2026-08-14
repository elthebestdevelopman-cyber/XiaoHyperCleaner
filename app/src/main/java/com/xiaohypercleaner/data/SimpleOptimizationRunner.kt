package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

/**
 * Запускает шаги SimpleSteps через Accessibility Service.
 *
 * Для каждого шага:
 * 1. Пробует все intent-ы по очереди (первый рабочий используем)
 * 2. Ищет тумблер по списку текстов
 * 3. Проверяет isChecked
 * 4. Если состояние не то что нужно — кликает
 * 5. Через 600ms проверяет снова
 * 6. Возвращает успех/неудачу
 */
@Suppress("DEPRECATION")
class SimpleOptimizationRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"
        private const val WAIT_FOR_SCREEN_MS = 1500L
        private const val WAIT_AFTER_CLICK_MS = 600L
    }

    data class StepResult(
        val stepId: String,
        val success: Boolean,
        val skipped: Boolean = false,
        val reason: String? = null
    )

    /**
     * Выполняет один шаг. Возвращает результат.
     */
    suspend fun executeStep(step: SimpleSteps.Step): StepResult {
        AppLog.i(TAG, "Executing step: ${step.id}")

        // 1. Пробуем все intent-ы по очереди
        var screenOpened = false
        for (intent in step.intents) {
            try {
                service.startActivity(intent)
                AppLog.i(
                    TAG,
                    "Step ${step.id}: opened screen with intent ${intent.action ?: intent.component}"
                )
                screenOpened = true
                break
            } catch (e: ActivityNotFoundException) {
                AppLog.w(
                    TAG,
                    "Step ${step.id}: intent not found: ${intent.action ?: intent.component}"
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Step ${step.id}: intent failed: ${e.message}")
            }
        }

        if (!screenOpened) {
            AppLog.e(TAG, "Step ${step.id}: ALL intents failed")
            return StepResult(step.id, false, reason = "all_intents_failed")
        }

        delay(WAIT_FOR_SCREEN_MS)

        // 2. Ищем тумблер по списку текстов
        val switchNode = findSwitchNode(step.searchTexts)
        if (switchNode == null) {
            AppLog.w(
                TAG,
                "Step ${step.id}: switch not found by any of ${step.searchTexts.size} texts"
            )
            return StepResult(step.id, false, reason = "switch_not_found")
        }
        AppLog.i(TAG, "Step ${step.id}: found switch, isChecked=${switchNode.isChecked}")

        // 3. Проверяем состояние
        if (switchNode.isChecked == step.targetChecked) {
            AppLog.i(TAG, "Step ${step.id}: already in target state")
            return StepResult(step.id, true)
        }

        // 4. Кликаем
        val clicked = switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            AppLog.w(TAG, "Step ${step.id}: click failed")
            return StepResult(step.id, false, reason = "click_failed")
        }
        AppLog.i(TAG, "Step ${step.id}: clicked")

        delay(WAIT_AFTER_CLICK_MS)

        // 5. Проверяем новое состояние
        val switchedNode = findSwitchNode(step.searchTexts)
        val newState = switchedNode?.isChecked ?: !switchNode.isChecked
        val success = newState == step.targetChecked

        AppLog.i(
            TAG,
            "Step ${step.id}: new state=$newState, target=${step.targetChecked}, success=$success"
        )
        return StepResult(step.id, success, reason = if (!success) "state_not_changed" else null)
    }

    private fun findSwitchNode(texts: List<String>): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            for (text in texts) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    val switch = findSwitchInHierarchy(node)
                    if (switch != null) return switch
                }
            }
            findByClassName(root, "Switch") ?: findByClassName(root, "Toggle")
        } finally {
            // root не recycle на Android 11+
        }
    }

    private fun findSwitchInHierarchy(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            val className = current.className?.toString() ?: ""
            if (className.contains("Switch") || className.contains("CheckBox") || className.contains(
                    "Toggle"
                )
            ) {
                return current
            }
            val parent = current.parent ?: break
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val childClass = child.className?.toString() ?: ""
                if (childClass.contains("Switch") || childClass.contains("Toggle")) {
                    return child
                }
            }
            current = parent
            depth++
        }
        return null
    }

    private fun findByClassName(
        root: AccessibilityNodeInfo,
        className: String
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 500) {
            val node = stack.removeLast()
            visited++
            val nodeClass = node.className?.toString() ?: ""
            if (nodeClass.contains(className)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}