package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationReportFormatter

/**
 * Accessibility Service для автоматического включения Wireless ADB.
 *
 * Исправления:
 * - ChainFlagsAutoReturn() → chainFlagsAutoReturn() (camelCase)
 * - Добавлен recycle() для AccessibilityNodeInfo на API < R
 * - Поиск нод вынесен в отдельные методы с правильной очисткой
 * - buildReportSummary перенесён в OptimizationReportFormatter
 */
class AdbEnablerService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbEnablerService"
        private const val DEV_WATCHDOG_MS = 15_000L

        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.ACTION_SIMPLE_STEP"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.ACTION_RETRY_DEV"
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.ACTION_START_CHAIN"

        // Тексты для поиска UI элементов на разных языках
        private val DEV_OPTIONS_TEXTS = arrayOf(
            "Developer options", "Параметры разработчика", "Для разработчиков",
            "Режим разработчика", "Настройки разработчика"
        )

        private val WIRELESS_DEBUG_TEXTS = arrayOf(
            "Wireless debugging", "Беспроводная отладка", "Отладка по Wi-Fi"
        )

        private val ALLOW_TEXTS = arrayOf(
            "Allow", "Разрешить", "OK", "ОК", "Да", "Yes"
        )
    }

    private var lastActionTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i(TAG, "Service connected")
        chainFlagsAutoReturn()  // Исправлено: было ChainFlagsAutoReturn()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < 2000) return
        lastActionTime = currentTime

        if (currentTime - ChainFlags.lastRedirectTime > DEV_WATCHDOG_MS) {
            AppLog.w(TAG, "Watchdog timeout")
            return
        }

        val root = rootInActiveWindow ?: return

        try {
            when {
                handleDevSettings(root) -> AppLog.i(TAG, "Handled dev settings")
                handleWirelessDebug(root) -> AppLog.i(TAG, "Handled wireless debug")
                handleAllowDialog(root) -> AppLog.i(TAG, "Handled allow dialog")
            }
        } finally {
            recycleNode(root)
        }
    }

    private fun handleDevSettings(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, DEV_OPTIONS_TEXTS) ?: return false
        val clickable = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }

        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            AppLog.i(TAG, "Clicked dev options")
        }
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, WIRELESS_DEBUG_TEXTS) ?: return false

        // Проверяем, включена ли уже
        if (node.isChecked) {
            recycleNode(node)
            return false
        }

        val clickable = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }

        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            AppLog.i(TAG, "Enabled wireless debugging")
        }
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    private fun handleAllowDialog(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, ALLOW_TEXTS) ?: return false
        val clickable = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }

        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            AppLog.i(TAG, "Clicked allow")
            ChainFlags.waitingAccessibilityReturn = false
            SimpleStepBridge.onResult?.invoke(true)
        }
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    /**
     * Поиск ноды по массиву текстов.
     * ВАЖНО: рециклирует неиспользованные ноды из списка результатов.
     */
    private fun findNodeByTexts(
        root: AccessibilityNodeInfo,
        texts: Array<String>
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                // Рециклируем остальные ноды (важно для API < R)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    for (i in 1 until nodes.size) {
                        nodes[i].recycle()
                    }
                }
                return node
            }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0

        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Безопасный recycle ноды (только для API < R).
     * На Android 11+ ноды управляются автоматически.
     */
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                node?.recycle()
            } catch (e: Exception) {
                // Игнорируем — нода могла быть уже рециклирована
            }
        }
    }

    /**
     * Исправлено: camelCase вместо PascalCase.
     * Было: ChainFlagsAutoReturn()
     */
    private fun chainFlagsAutoReturn() {
        ChainFlags.waitingAccessibilityReturn = true
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "Service interrupted")
    }
}