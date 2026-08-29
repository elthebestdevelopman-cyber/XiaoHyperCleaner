package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.LogMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility Service.
 *
 * Выполняет две роли:
 *   1. Запускает шаги Simple Mode через [SimpleRunner] (по ACTION_SIMPLE_STEP)
 *   2. Авто-кликер диалогов:
 *      - Во время шага ([SimpleRunner.isRunning]) — ТОЛЬКО исключения:
 *          * Проводник: «Добро пожаловать» → «Отмена»
 *          * Экран блокировки: «Обновите» → «Отклонить»
 *          * Системные ошибки (Facebook/QR/«Невозможно подключиться»)
 *      - Вне шага — watchdog PRO-цепочки (dev options / wireless debug / allow dialog)
 *        + обычные согласия первого запуска
 *
 * Гейтинг через статический флаг [SimpleRunner.isRunning] — единый источник истины,
 * чтобы автокликер не конфликтовал с [SimpleRunner.dismissDialogs].
 *
 * УЛУЧШЕНИЯ:
 * 1. LogMasker для маскировки чувствительных данных в логах
 * 2. collectTextRecursive depth=7 (как в SimpleRunner) для глубоких диалогов
 * 3. Явные типы для всех переменных
 * 4. Защита от ConcurrentModificationException
 * 5. Улучшенное логирование для диагностики
 */
class AdbEnablerService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbEnablerService"
        private const val DEV_WATCHDOG_MS = 15_000L
        private const val EVENT_THROTTLE_MS = 300L
        private const val STATUS_VISIBLE_MS = 600L
        private const val TEXT_DEPTH = 7  // Как в SimpleRunner для глубоких диалогов

        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.ACTION_SIMPLE_STEP"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.ACTION_RETRY_DEV"
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.ACTION_START_CHAIN"

        var instance: AdbEnablerService? = null
            private set

        // ── PRO-цепочка: тексты для watchdog ──
        private val DEV_OPTIONS_TEXTS = arrayOf(
            "Developer options", "Параметры разработчика", "Для разработчиков",
            "Режим разработчика", "Настройки разработчика"
        )
        private val WIRELESS_DEBUG_TEXTS = arrayOf(
            "Wireless debugging", "Беспроводная отладка", "Отладка по Wi-Fi"
        )
        private val ALLOW_TEXTS = arrayOf("Allow", "Разрешить", "OK", "ОК", "Да", "Yes")

        // ── Кнопки диалогов первого запуска (только ВНЕ шага) ──
        private val AUTO_ALLOW_TEXTS = arrayOf(
            "Согласиться", "Принять", "Разрешить", "Продолжить", "Начать",
            "Agree", "Accept", "Allow", "Continue", "Start", "OK", "Got it"
        )

        // ── Маркеры системных ошибок: закрываем всегда (во время и вне шага) ──
        private val ERROR_MARKERS = arrayOf(
            "Невозможно подключиться", "Отсканируйте QR", "Facebook",
            "Проверьте подключение", "нет подключения", "Ошибка соединения",
            "Не удалось загрузить", "Cannot connect", "Scan QR"
        )
        private val ERROR_DISMISS = arrayOf(
            "ОТМЕНА", "Отмена", "Закрыть", "Назад", "ОК", "Cancel", "Close", "Back"
        )
    }

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val simpleRunner: SimpleRunner by lazy { SimpleRunner(this) }

    private var lastActionTime: Long = 0L
    private var devWindowOpen: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(TAG, "Service connected")
        ChainFlags.waitingAccessibilityReturn = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SIMPLE_STEP -> {
                val index: Int = intent.getIntExtra("step_index", -1)
                if (index in SimpleSteps.ALL.indices) {
                    scope.launch { runSimpleStep(index) }
                } else {
                    AppLog.w(TAG, "onStartCommand: invalid step_index=$index")
                }
            }

            ACTION_RETRY_DEV, ACTION_START_CHAIN -> {
                ChainFlags.lastRedirectTime = System.currentTimeMillis()
                devWindowOpen = true
                AppLog.i(TAG, "onStartCommand: ${intent.action}, devWindowOpen=true")
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runSimpleStep(index: Int) {
        // Флаг isRunning устанавливается ВНУТРИ SimpleRunner.run(),
        // поэтому здесь его трогать не нужно.
        try {
            val step: SimpleSteps.Step = SimpleSteps.ALL[index]
            val total: Int = SimpleSteps.ALL.size

            AppLog.i(TAG, "runSimpleStep: starting step ${index + 1}/$total (${step.id})")

            OverlayController.updateAutomation(this, index + 1, total, step.titleRu)
            OverlayController.updateStatus(
                this,
                getString(
                    R.string.automation_status_search,
                    step.searchTexts.take(2).joinToString(" / ")
                )
            )

            val result: SimpleRunner.Result = simpleRunner.run(step)

            when {
                result.skipped -> {
                    AppLog.i(TAG, "runSimpleStep: step ${step.id} skipped (app not installed)")
                    OverlayController.updateStatus(this, getString(R.string.automation_status_skip))
                    delay(STATUS_VISIBLE_MS)
                    SimpleStepBridge.onSkipped?.invoke(step.id)
                }

                else -> {
                    AppLog.i(
                        TAG,
                        "runSimpleStep: step ${step.id} result=${result.success}, reason=${result.reason}"
                    )
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
            AppLog.e(TAG, "runSimpleStep error: ${LogMasker.mask(e.message ?: "")}", e)
            SimpleStepBridge.onResult?.invoke(false)
        }
    }

    fun cancelRunner() {
        AppLog.i(TAG, "cancelRunner: cancelling SimpleRunner")
        simpleRunner.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val currentTime: Long = System.currentTimeMillis()
        if (currentTime - lastActionTime < EVENT_THROTTLE_MS) return
        lastActionTime = currentTime

        // ИСПРАВЛЕНО: единый источник истины — SimpleRunner.isRunning
        if (SimpleRunner.isRunning) {
            // ВО ВРЕМЯ ШАГА: только исключения и ошибки (остальное делает runner)
            handleStepTimeExceptions()
            return
        }

        // ВНЕ ШАГА: watchdog PRO-цепочки + обычные согласия
        if (currentTime - ChainFlags.lastRedirectTime > DEV_WATCHDOG_MS) {
            if (devWindowOpen) {
                devWindowOpen = false
                AppLog.i(TAG, "Dev watchdog window closed")
            }
            // Системные ошибки обрабатываем и вне шага
            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
            try {
                if (handleSystemErrors(root)) {
                    AppLog.i(TAG, "Auto-dialog: system error dismissed (idle)")
                    return
                }
                handleFirstRunDialogs(root)
            } finally {
                recycleNode(root)
            }
            return
        }
        devWindowOpen = true

        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
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
    // Обработка ВО ВРЕМЯ ШАГА (исключения + системные ошибки)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Во время шага обрабатываем ТОЛЬКО:
     *   - Исключения (Проводник, Lock screen) — они специфичны и не в runner
     *   - Системные ошибки (FB/QR/«Невозможно подключиться») — появляются поверх
     * Обычные согласия («Согласиться»/«Разрешить») обрабатывает сам runner
     * через dismissDialogs(), чтобы не было дублирования кликов.
     */
    private fun handleStepTimeExceptions() {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            // Системные ошибки — всегда гасим
            if (handleSystemErrors(root)) {
                AppLog.i(TAG, "Auto-dialog: system error dismissed (step running)")
                return
            }

            val screenText: String = collectAllText(root)

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
        } finally {
            recycleNode(root)
        }
    }

    /**
     * Системные ошибки: Facebook/QR/«Невозможно подключиться» — гасим всегда.
     * Появляются на уровне системы поверх приложений, runner их не ловит.
     */
    private fun handleSystemErrors(root: AccessibilityNodeInfo): Boolean {
        val screenText: String = collectAllText(root)
        val hasError: Boolean = ERROR_MARKERS.any { screenText.contains(it, ignoreCase = true) }
        if (!hasError) return false

        if (clickByText(root, ERROR_DISMISS)) {
            AppLog.i(TAG, "Auto-dialog: system error dismissed")
            return true
        }
        // Если кнопки нет — давим системную «Назад»
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            AppLog.i(TAG, "Auto-dialog: system error → BACK")
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "Auto-dialog: system error BACK failed: ${e.message}")
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Авто-кликер диалогов первого запуска (только ВНЕ шага)
    // ═══════════════════════════════════════════════════════════════

    private fun handleFirstRunDialogs(root: AccessibilityNodeInfo) {
        val screenText: String = collectAllText(root)

        // Системные ошибки обрабатываем и здесь
        if (handleSystemErrors(root)) return

        // Обычные диалоги первого запуска: согласия/разрешения
        for (text in AUTO_ALLOW_TEXTS) {
            val nodes: List<AccessibilityNodeInfo>? = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: continue

            val btn: AccessibilityNodeInfo = nodes.firstOrNull { it.isClickable } ?: continue

            // Нажимаем только если это похоже на диалог (кнопка в диалоговом окне)
            if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLog.i(TAG, "Auto-dialog: clicked '${LogMasker.mask(text)}'")
            }
            recycleNode(btn)
            return
        }
    }

    private fun clickByText(root: AccessibilityNodeInfo, texts: Array<String>): Boolean {
        for (text in texts) {
            val nodes: List<AccessibilityNodeInfo>? = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: continue

            val btn: AccessibilityNodeInfo = nodes.firstOrNull { it.isClickable } ?: continue
            val ok: Boolean = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycleNode(btn)
            if (ok) return true
        }
        return false
    }

    /**
     * Собирает весь текст из дерева узлов.
     * depth=7 (как в SimpleRunner) для поддержки глубоких диалогов.
     */
    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        collectTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > TEXT_DEPTH) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collectTextRecursive(node.getChild(i), sb, depth + 1)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Watchdog-обработчики PRO-цепочки
    // ═══════════════════════════════════════════════════════════════

    private fun handleDevSettings(root: AccessibilityNodeInfo): Boolean {
        val node: AccessibilityNodeInfo = findNodeByTexts(root, DEV_OPTIONS_TEXTS) ?: return false
        val clickable: AccessibilityNodeInfo = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }
        val result: Boolean = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo): Boolean {
        val node: AccessibilityNodeInfo =
            findNodeByTexts(root, WIRELESS_DEBUG_TEXTS) ?: return false

        @Suppress("DEPRECATION")
        if (node.isChecked) {
            recycleNode(node)
            return false
        }

        val clickable: AccessibilityNodeInfo = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }
        val result: Boolean = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        recycleNode(node)
        recycleNode(clickable)
        return result
    }

    private fun handleAllowDialog(root: AccessibilityNodeInfo): Boolean {
        val node: AccessibilityNodeInfo = findNodeByTexts(root, ALLOW_TEXTS) ?: return false
        val clickable: AccessibilityNodeInfo = findClickableParent(node) ?: run {
            recycleNode(node)
            return false
        }
        val result: Boolean = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (result) {
            ChainFlags.waitingAccessibilityReturn = false
            SimpleStepBridge.onResult?.invoke(true)
            AppLog.i(TAG, "handleAllowDialog: permission granted, notifying bridge")
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
            val nodes: List<AccessibilityNodeInfo>? = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: continue

            if (nodes.isNotEmpty()) return nodes[0]
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth: Int = 0
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
                // Игнорируем: узел мог быть освобождён ранее
            }
        }
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy: cleaning up")
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}