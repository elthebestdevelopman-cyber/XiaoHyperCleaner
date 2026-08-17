package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.SimpleOptimizationRunner
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility Service: включение Wireless ADB + выполнение шагов простой оптимизации.
 *
 * Исправления:
 * - Добавлен onStartCommand: ACTION_SIMPLE_STEP теперь реально выполняется
 *   (раньше intent уходил в пустоту — шаги висли в WORKING).
 * - Watchdog стал edge-triggered: «window closed» логируется ОДИН раз,
 *   а не на каждое событие (убран спам из логов).
 * - CoroutineScope с SupervisorJob, отменяется в onDestroy (нет утечек).
 */
class AdbEnablerService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbEnablerService"
        private const val DEV_WATCHDOG_MS = 15_000L
        private const val EVENT_THROTTLE_MS = 2_000L

        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.ACTION_SIMPLE_STEP"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.ACTION_RETRY_DEV"
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.ACTION_START_CHAIN"

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

    // Шаги выполняем на Main: AccessibilityNodeInfo требует main thread,
    // а delay() внутри runner не блокирует поток.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastActionTime = 0L
    private var devWindowOpen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.i(TAG, "Service connected")
        ChainFlags.waitingAccessibilityReturn = true
    }

    // ═══════════════════════════════════════════════════════════════
    // ПРИЁМ КОМАНД — то, чего не хватало
    // ═══════════════════════════════════════════════════════════════
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SIMPLE_STEP -> {
                val index = intent.getIntExtra("step_index", -1)
                if (index in SimpleSteps.ALL.indices) {
                    scope.launch { runSimpleStep(index) }
                } else {
                    AppLog.w(TAG, "ACTION_SIMPLE_STEP: invalid index=$index")
                }
            }

            ACTION_RETRY_DEV, ACTION_START_CHAIN -> {
                // Открываем новое окно watchdog, чтобы сервис реагировал на события
                ChainFlags.lastRedirectTime = System.currentTimeMillis()
                devWindowOpen = true
                AppLog.i(TAG, "Dev window reopened by ${intent.action}")
            }

            else -> AppLog.w(TAG, "onStartCommand: unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private suspend fun runSimpleStep(index: Int) {
        val step = SimpleSteps.ALL[index]
        AppLog.i(TAG, "runSimpleStep: index=$index, id=${step.id}")
        val result = SimpleOptimizationRunner(this).executeStep(step)
        AppLog.i(TAG, "runSimpleStep: success=${result.success}, reason=${result.reason}")
        SimpleStepBridge.onResult?.invoke(result.success)
    }

    // ═══════════════════════════════════════════════════════════════
    // СОБЫТИЯ — watchdog без спама
    // ═══════════════════════════════════════════════════════════════
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < EVENT_THROTTLE_MS) return
        lastActionTime = currentTime

        if (currentTime - ChainFlags.lastRedirectTime > DEV_WATCHDOG_MS) {
            // Edge-triggered: логируем закрытие окна ровно один раз
            if (devWindowOpen) {
                devWindowOpen = false
                AppLog.i(TAG, "Dev watchdog window closed")
            }
            return
        }
        devWindowOpen = true

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
        if (result) AppLog.i(TAG, "Clicked dev options")
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, WIRELESS_DEBUG_TEXTS) ?: return false
        @Suppress("DEPRECATION")
        if (node.isChecked) {
            recycleNode(node)
            return false
        }
        val clickable = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }
        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) AppLog.i(TAG, "Enabled wireless debugging")
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

    private fun findNodeByTexts(
        root: AccessibilityNodeInfo,
        texts: Array<String>
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            if (nodes.isNotEmpty()) {
                val node = nodes[0]
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    for (i in 1 until nodes.size) nodes[i].recycle()
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

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION")
                node?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        scope.cancel()
        AppLog.i(TAG, "Service destroyed, scope cancelled")
        super.onDestroy()
    }
}