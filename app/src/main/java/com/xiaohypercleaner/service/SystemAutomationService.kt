package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accessibility Service, используемый ТОЛЬКО для проверки работоспособности
 * Accessibility API в фазе TEST_CLICK.
 *
 * Почему отдельный от AdbEnablerService:
 * - AdbEnablerService выполняет реальную автоматизацию шагов (SimpleSteps).
 * - SystemAutomationService отвечает ТОЛЬКО за тестовый клик в TestActivity.
 * - Разделение ответственности: при сбое теста мы можем перезапустить только тестовый сервис.
 *
 * Механизм:
 * 1. TestActivity устанавливает [awaitingTestClick] = true и показывает кнопку "Проверить автоматику"
 * 2. Service получает WINDOW_STATE_CHANGED, находит кнопку по тексту и кликает
 * 3. По клику TestActivity шлёт broadcast TEST_CLICK_SUCCESS
 * 4. Если за 5 секунд клик не произошёл — TEST_CLICK_TIMEOUT
 */
class SystemAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "SystemAutoSvc"

        /**
         * Глобальный флаг: ждать ли тестовый клик.
         * AtomicBoolean для thread-safety (Accessibility callbacks на main,
         * но TestActivity может менять флаг из других корутин).
         */
        val awaitingTestClick = AtomicBoolean(false)

        /** Константы для broadcast'ов */
        const val ACTION_TEST_CLICK_SUCCESS = "com.xiaohypercleaner.TEST_CLICK_SUCCESS"
        const val ACTION_TEST_CLICK_TIMEOUT = "com.xiaohypercleaner.TEST_CLICK_TIMEOUT"

        /** Текст кнопки, которую ищем (должен совпадать с strings.xml) */
        const val TEST_BUTTON_TEXT_RU = "Проверить автоматику"
        const val TEST_BUTTON_TEXT_EN = "Test automation"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i(TAG, "SystemAutomationService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Реагируем только когда TEST_CLICK активен
        if (!awaitingTestClick.get()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                tryTestClick()
            }

            else -> {}
        }
    }

    /**
     * Пытается найти тестовую кнопку и кликнуть по ней.
     * Использует несколько стратегий поиска (RU/EN варианты текста).
     */
    private fun tryTestClick() {
        val root = rootInActiveWindow ?: return

        // Ищем по RU-тексту
        var node = findNodeByText(root, TEST_BUTTON_TEXT_RU)
        // Если не нашли — пробуем EN
        if (node == null) node = findNodeByText(root, TEST_BUTTON_TEXT_EN)

        if (node != null) {
            AppLog.i(TAG, "Test button found, performing click")
            val clicked = performClick(node)
            if (clicked) {
                // Флаг сбрасываем ДО broadcast, чтобы избежать повторных попыток
                awaitingTestClick.set(false)
                sendBroadcast(ACTION_TEST_CLICK_SUCCESS)
            }
        }
    }

    /**
     * Рекурсивный поиск ноды по тексту.
     * Возвращает кликабельную ноду (или первого кликабельного родителя).
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        if (nodeText != null && nodeText.contains(text, ignoreCase = true)) {
            // Если сама нода кликабельна — возвращаем её
            if (node.isClickable) return node
            // Иначе ищем кликабельного родителя
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
            // В крайнем случае возвращаем исходную ноду
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
        }
        return null
    }

    /**
     * Выполняет клик с fallback-стратегиями:
     * 1. ACTION_CLICK на самой ноде
     * 2. ACTION_CLICK на родителе
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
            false
        } catch (e: Exception) {
            AppLog.w(TAG, "performClick failed: ${e.message}")
            false
        }
    }

    private fun sendBroadcast(action: String) {
        try {
            val intent = Intent(action).apply {
                setPackage(packageName)  // internal broadcast only
            }
            sendBroadcast(intent)
            AppLog.i(TAG, "Broadcast sent: $action")
        } catch (e: Exception) {
            AppLog.w(TAG, "sendBroadcast failed: ${e.message}")
        }
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "SystemAutomationService interrupted")
    }

    override fun onDestroy() {
        awaitingTestClick.set(false)
        super.onDestroy()
        AppLog.i(TAG, "SystemAutomationService destroyed")
    }
}