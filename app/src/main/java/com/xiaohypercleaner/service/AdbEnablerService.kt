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
 * Accessibility Service: включение Wireless ADB + выполнение шагов простой оптимизации.
 *
 * Использует [SimpleRunner] для выполнения шагов, включая internal navigation
 * (последовательные клики внутри приложений: Безопасность, GetApps, Темы и т.д.)
 *
 * ИСПРАВЛЕНО в этой версии:
 *  - Добавлен singleton `instance` для доступа из MainViewModel (кнопка «Отменить»)
 *  - Метод `cancelRunner()` останавливает SimpleRunner при отмене из оверлея
 *  - Защита SimpleStepBridge.onResult / onSkipped сохранена
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

        // НОВОЕ: singleton для доступа из MainViewModel (отмена из оверлея робокота)
        var instance: AdbEnablerService? = null
            private set

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Ленивая инициализация runner'а — после onServiceConnected service уже готов
    private val simpleRunner by lazy { SimpleRunner(this) }

    private var lastActionTime = 0L
    private var devWindowOpen = false
    private var isSimpleStepRunning = false  // Защита: блокирует watchdog при работе шага

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this  // НОВОЕ: сохраняем singleton-инстанс
        AppLog.i(TAG, "Service connected")
        ChainFlags.waitingAccessibilityReturn = true
    }

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
                ChainFlags.lastRedirectTime = System.currentTimeMillis()
                devWindowOpen = true
                AppLog.i(TAG, "Dev window reopened by ${intent.action}")
            }

            else -> AppLog.w(TAG, "onStartCommand: unknown action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /**
     * Выполняет шаг через SimpleRunner с учётом internal navigation.
     *
     * Обработка result.skipped — если приложение не установлено,
     * вызываем onSkipped (переход к следующему шагу без ретраев).
     * Защиты SimpleStepBridge.onResult / onSkipped сохранены.
     */
    private suspend fun runSimpleStep(index: Int) {
        isSimpleStepRunning = true
        try {
            val step = SimpleSteps.ALL[index]
            val total = SimpleSteps.ALL.size

            // Робокот показывает, что делает
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
                    OverlayController.updateStatus(
                        this, getString(R.string.automation_status_skip)
                    )
                    AppLog.i(TAG, "runSimpleStep: ${step.id} skipped (app not installed)")
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
                    AppLog.i(
                        TAG,
                        "runSimpleStep: success=${result.success}, reason=${result.reason}"
                    )
                    delay(STATUS_VISIBLE_MS)
                    SimpleStepBridge.onResult?.invoke(result.success)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "runSimpleStep: unexpected error: ${e.message}", e)
            try {
                OverlayController.updateStatus(
                    this, getString(R.string.automation_status_fail)
                )
            } catch (_: Exception) {
            }
            delay(STATUS_VISIBLE_MS)
            SimpleStepBridge.onResult?.invoke(false)
        } finally {
            isSimpleStepRunning = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Защита: пропускаем события пока runner работает
        if (isSimpleStepRunning) return

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
        instance = null  // НОВОЕ: сбрасываем singleton
        scope.cancel()
        AppLog.i(TAG, "Service destroyed, scope cancelled")
        super.onDestroy()
    }

    /**
     * НОВОЕ: остановить runner извне.
     * Вызывается из OverlayController.setOnCancel при клике «Отменить» в оверлее робокота.
     */
    fun cancelRunner() {
        AppLog.i(TAG, "cancelRunner: stopping current simple step")
        simpleRunner.cancel()
    }
}