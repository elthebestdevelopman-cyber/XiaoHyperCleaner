package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility Service.
 *
 * НОВОЕ: авто-кликер диалогов первого запуска («Согласиться», «Разрешить», «Пропустить»)
 * с ИСКЛЮЧЕНИЯМИ:
 *  - «Добро пожаловать в Проводник!» → жмём «Отмена» (не принимаем политику)
 *  - «Обновите свой Экран блокировки» → жмём «Отклонить»
 * Работает только пока выполняется шаг (isSimpleStepRunning), чтобы не мешать пользователю.
 */
class AdbEnablerService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbEnablerService"
        private const val DEV_WATCHDOG_MS = 15_000L
        private const val EVENT_THROTTLE_MS = 300L
        private const val STATUS_VISIBLE_MS = 600L

        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.ACTION_SIMPLE_STEP"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.ACTION_RETRY_DEV"
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.ACTION_START_CHAIN"

        var instance: AdbEnablerService? = null
            private set

        /** Кнопки диалогов первого запуска, которые нажимаем автоматически */
        private val AUTO_ALLOW_TEXTS = arrayOf(
            "Согласиться", "Принять", "Разрешить", "Продолжить", "Начать",
            "Agree", "Accept", "Allow", "Continue", "Start", "OK", "Got it"
        )

        private val DEV_OPTIONS_TEXTS = arrayOf(
            "Developer options", "Параметры разработчика", "Для разработчиков",
            "Режим разработчика", "Настройки разработчика"
        )
        private val WIRELESS_DEBUG_TEXTS = arrayOf(
            "Wireless debugging", "Беспроводная отладка", "Отладка по Wi-Fi"
        )
        private val ALLOW_TEXTS = arrayOf("Allow", "Разрешить", "OK", "ОК", "Да", "Yes")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val simpleRunner by lazy { SimpleRunner(this) }

    private var lastActionTime = 0L
    private var devWindowOpen = false
    private var isSimpleStepRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(TAG, "Service connected")
        ChainFlags.waitingAccessibilityReturn = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SIMPLE_STEP -> {
                val index = intent.getIntExtra("step_index", -1)
                if (index in SimpleSteps.ALL.indices) {
                    scope.launch { runSimpleStep(index) }
                }
            }

            ACTION_RETRY_DEV, ACTION_START_CHAIN -> {
                ChainFlags.lastRedirectTime = System.currentTimeMillis()
                devWindowOpen = true
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSimpleStep(index: Int) {
        isSimpleStepRunning = true
        try {
            val step = SimpleSteps.ALL[index]
            val total = SimpleSteps.ALL.size
            OverlayController.updateAutomation(this, index + 1, total, step.titleRu)
            OverlayController.updateStatus(
                this,
                getString(
                    R.string.automation_status_search,
                    step.searchTexts.take(2).joinToString(" / ")
                )
            )
            val result = simpleRunner.run(step)
            when {
                result.skipped -> {
                    OverlayController.updateStatus(this, getString(R.string.automation_status_skip))
                    delay(STATUS_VISIBLE_MS)
                    SimpleStepBridge.onSkipped?.invoke(step.id)
                }

                else -> {
                    OverlayController.updateStatus(
                        this,
                        getString(
                            if (result.success) R.string.automation_status_done
                            else R.string.automation_status_fail
                        )
                    )
                    delay(STATUS_VISIBLE_MS)
                    SimpleStepBridge.onResult?.invoke(result.success)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "runSimpleStep error: ${e.message}", e)
            SimpleStepBridge.onResult?.invoke(false)
        } finally {
            isSimpleStepRunning = false
        }
    }

    fun cancelRunner() {
        simpleRunner.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // ВО ВРЕМЯ ШАГА: только авто-кликер диалогов (runner работает сам)
        if (isSimpleStepRunning) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastActionTime < EVENT_THROTTLE_MS) return
            lastActionTime = currentTime
            val root = rootInActiveWindow ?: return
            try {
                handleFirstRunDialogs(root)
            } finally {
                recycleNode(root)
            }
            return
        }

        // Вне шага: watchdog цепочки Wireless ADB
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < EVENT_THROTTLE_MS) return
        lastActionTime = currentTime

        if (currentTime - ChainFlags.lastRedirectTime > DEV_WATCHDOG_MS) {
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

    // ═══════════════════════════════════════════════════════════════
    // Авто-кликер диалогов первого запуска (с исключениями)
    // ═══════════════════════════════════════════════════════════════

    private fun handleFirstRunDialogs(root: AccessibilityNodeInfo) {
        val screenText = collectAllText(root)

        // ИСКЛЮЧЕНИЕ 1: Проводник — НЕ принимаем политику, жмём «Отмена»
        if (screenText.contains("Добро пожаловать в Проводник", ignoreCase = true) ||
            screenText.contains("Welcome to File Manager", ignoreCase = true)
        ) {
            clickByText(root, arrayOf("Отмена", "Cancel"))
            AppLog.i(TAG, "Auto-dialog: File Manager welcome → Cancel")
            return
        }

        // ИСКЛЮЧЕНИЕ 2: редактор экрана блокировки — жмём «Отклонить»
        if (screenText.contains("Обновите свой Экран блокировки", ignoreCase = true) ||
            screenText.contains("Update your Lock screen", ignoreCase = true)
        ) {
            clickByText(root, arrayOf("Отклонить", "Decline", "Нет", "No"))
            AppLog.i(TAG, "Auto-dialog: Lock screen update → Decline")
            return
        }

        // Обычные диалоги первого запуска: согласия/разрешения
        for (text in AUTO_ALLOW_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            val btn = nodes.firstOrNull { it.isClickable }
            if (btn != null) {
                // Нажимаем только если это похоже на диалог (кнопка в диалоговом окне)
                if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    AppLog.i(TAG, "Auto-dialog: clicked '$text'")
                }
                recycleNode(btn)
                return
            }
        }
    }

    private fun clickByText(root: AccessibilityNodeInfo, texts: Array<String>): Boolean {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            val btn = nodes.firstOrNull { it.isClickable } ?: continue
            val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycleNode(btn)
            if (ok) return true
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        collectTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 4) return
        node.text?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectTextRecursive(node.getChild(i), sb, depth + 1)
    }

    // ═══ Watchdog-обработчики PRO-цепочки (без изменений) ═══

    private fun handleDevSettings(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, DEV_OPTIONS_TEXTS) ?: return false
        val clickable = findClickableParent(node) ?: run { recycleNode(node); return false }
        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        recycleNode(node); recycleNode(clickable)
        return result
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, WIRELESS_DEBUG_TEXTS) ?: return false
        @Suppress("DEPRECATION")
        if (node.isChecked) {
            recycleNode(node); return false
        }
        val clickable = findClickableParent(node) ?: run { recycleNode(node); return false }
        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        recycleNode(node); recycleNode(clickable)
        return result
    }

    private fun handleAllowDialog(root: AccessibilityNodeInfo): Boolean {
        val node = findNodeByTexts(root, ALLOW_TEXTS) ?: return false
        val clickable = findClickableParent(node) ?: run { recycleNode(node); return false }
        val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            ChainFlags.waitingAccessibilityReturn = false
            SimpleStepBridge.onResult?.invoke(true)
        }
        recycleNode(node); recycleNode(clickable)
        return result
    }

    private fun findNodeByTexts(
        root: AccessibilityNodeInfo,
        texts: Array<String>
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            if (nodes.isNotEmpty()) return nodes[0]
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent; depth++
        }
        return null
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION") node?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}