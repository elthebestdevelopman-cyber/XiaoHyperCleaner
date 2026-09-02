package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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
 * Accessibility Service для XiaoHyperCleaner.
 *
 * Выполняет две основные роли:
 *
 * **1. Исполнитель Simple Mode** (через [SimpleRunner])
 *    - Запускает шаги по [ACTION_SIMPLE_STEP]
 *    - Обновляет статусы в оверлее через [OverlayController]
 *    - Сообщает результаты через [SimpleStepBridge]
 *
 * **2. Авто-кликер системных диалогов** (watchdog):
 *    - **Во время шага** ([SimpleRunner.isRunning] == true) — ТОЛЬКО исключения:
 *      * Lock screen update → «Отклонить»
 *      * Системные ошибки (реальные, а не ложные срабатывания)
 *    - **Вне шага** — watchdog PRO-цепочки:
 *      * Developer options → включение
 *      * Wireless debugging → включение
 *      * Allow dialog → подтверждение
 *      * Диалоги первого запуска → авто-согласие
 *
 * ИСПРАВЛЕНИЯ (beta11):
 * - Wake Lock: экран не гаснет во время оптимизации (SCREEN_DIM_WAKE_LOCK)
 * - Автосброс wake lock: на последнем шаге / при отмене / в onDestroy
 * - ACTION_RELEASE_WAKE — для внешнего принудительного освобождения
 * - ERROR_MARKERS сужены до специфичных текстов (beta5)
 * - Debounce 10 сек для handleSystemErrors — защита от цикла "OK + BACK"
 * - Убраны delay(STATUS_VISIBLE_MS) между шагами — быстрее UX
 */
class AdbEnablerService : AccessibilityService() {

    companion object {
        private const val TAG = "AdbEnablerService"

        /** Таймаут watchdog'а PRO-цепочки (15 секунд после redirect) */
        private const val DEV_WATCHDOG_MS = 15_000L

        /** Минимальный интервал между обработкой событий (защита от спама) */
        private const val EVENT_THROTTLE_MS = 300L

        /** Глубина рекурсии при сборе текста (как в SimpleRunner) */
        private const val TEXT_DEPTH = 7

        /** Максимальная глубина поиска кликабельного родителя */
        private const val MAX_PARENT_DEPTH = 5

        /**
         * Debounce для handleSystemErrors.
         * Защита от бесконечного цикла, когда ERROR_MARKERS случайно
         * совпадает с обычным контентом в приложениях.
         */
        private const val SYSTEM_ERROR_DEBOUNCE_MS = 10_000L

        /** Максимальное время удержания Wake Lock (30 минут) как страховка от утечки */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        // ── Actions для Intent ──
        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.ACTION_SIMPLE_STEP"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.ACTION_RETRY_DEV"
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.ACTION_START_CHAIN"

        /** НОВОЕ (beta11): принудительное освобождение wake lock */
        const val ACTION_RELEASE_WAKE = "com.xiaohypercleaner.ACTION_RELEASE_WAKE"

        /** Синглтон-ссылка на активный сервис (null если не подключён) */
        var instance: AdbEnablerService? = null
            private set

        // ── PRO-цепочка: тексты для watchdog ──
        private val DEV_OPTIONS_TEXTS: Array<String> = arrayOf(
            "Developer options", "Параметры разработчика", "Для разработчиков",
            "Режим разработчика", "Настройки разработчика"
        )
        private val WIRELESS_DEBUG_TEXTS: Array<String> = arrayOf(
            "Wireless debugging", "Беспроводная отладка", "Отладка по Wi-Fi"
        )
        private val ALLOW_TEXTS: Array<String> = arrayOf(
            "Allow", "Разрешить", "OK", "ОК", "Да", "Yes"
        )

        // ── Кнопки диалогов первого запуска (только ВНЕ шага) ──
        private val AUTO_ALLOW_TEXTS: Array<String> = arrayOf(
            "Согласиться", "Принять", "Разрешить", "Продолжить", "Начать",
            "Agree", "Accept", "Allow", "Continue", "Start", "OK", "Got it"
        )

        // ── Маркеры системных ошибок: узкие, специфичные ──
        private val ERROR_MARKERS: Array<String> = arrayOf(
            "Невозможно подключиться к серверу",
            "Не удается подключиться к серверу",
            "Не удалось подключиться к серверу",
            "Нет подключения к интернету",
            "Нет интернет-соединения",
            "Ошибка соединения",
            "Cannot connect to server",
            "Unable to connect to server",
            "No internet connection",
            "Network error"
        )

        private val ERROR_DISMISS: Array<String> = arrayOf(
            "ОТМЕНА", "Отмена", "Закрыть", "ОК", "OK", "Cancel", "Close"
        )
    }

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val simpleRunner: SimpleRunner by lazy { SimpleRunner(this) }

    /** Timestamp последнего обработанного события (для throttling) */
    private var lastActionTime: Long = 0L

    /** Флаг: открыто ли окно dev options (для watchdog) */
    private var devWindowOpen: Boolean = false

    /** Timestamp последней обработки системной ошибки (debounce) */
    private var lastSystemErrorHandledAt: Long = 0L

    // ═══════════════════════════════════════════════════════════════
    // Wake Lock — предотвращает отключение экрана во время оптимизации
    // ═══════════════════════════════════════════════════════════════
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Захватывает wake lock. Используем SCREEN_DIM_WAKE_LOCK (deprecated но работает) —
     * экран остаётся тускло освещённым, CPU не спит.
     * Таймаут 30 минут как страховка от утечки.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "XHC:Optimization"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            AppLog.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            AppLog.w(TAG, "Wake lock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLog.i(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Wake lock release failed: ${e.message}")
        }
        wakeLock = null
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

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
                val total: Int = SimpleSteps.ALL.size
                if (index in SimpleSteps.ALL.indices) {
                    // НОВОЕ (beta11): захватываем wake lock на первом шаге
                    if (index == 0) acquireWakeLock()
                    scope.launch { runSimpleStep(index, total) }
                } else {
                    AppLog.w(TAG, "onStartCommand: invalid step_index=$index")
                }
            }

            ACTION_RELEASE_WAKE -> {
                // НОВОЕ (beta11): принудительное освобождение извне
                releaseWakeLock()
            }

            ACTION_RETRY_DEV, ACTION_START_CHAIN -> {
                ChainFlags.lastRedirectTime = System.currentTimeMillis()
                devWindowOpen = true
                AppLog.i(TAG, "onStartCommand: ${intent.action}, devWindowOpen=true")
            }
        }
        return START_NOT_STICKY
    }

    override fun onInterrupt() {
        AppLog.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy: cleaning up")
        releaseWakeLock()  // НОВОЕ (beta11): гарантированное освобождение
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // Исполнитель Simple Mode
    // ═══════════════════════════════════════════════════════════════

    /**
     * Выполняет один шаг Simple Mode.
     *
     * @param index индекс шага в [SimpleSteps.ALL]
     * @param total общее количество шагов (для определения последнего)
     */
    private suspend fun runSimpleStep(index: Int, total: Int) {
        try {
            val step: SimpleSteps.Step = SimpleSteps.ALL[index]

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
                    OverlayController.updateStatus(
                        this, getString(R.string.automation_status_skip)
                    )
                    SimpleStepBridge.onSkipped?.invoke(step.id)
                }

                else -> {
                    AppLog.i(
                        TAG,
                        "runSimpleStep: step ${step.id} result=${result.success}, " +
                                "reason=${result.reason}"
                    )
                    OverlayController.updateStatus(
                        this,
                        getString(
                            if (result.success) R.string.automation_status_done
                            else R.string.automation_status_fail
                        )
                    )
                    SimpleStepBridge.onResult?.invoke(result.success)
                }
            }

            // НОВОЕ (beta11): освобождаем wake lock на последнем шаге
            if (index == total - 1) {
                releaseWakeLock()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "runSimpleStep error: ${LogMasker.mask(e.message ?: "")}", e)
            SimpleStepBridge.onResult?.invoke(false)
        }
    }

    /**
     * Отменяет выполнение текущего шага SimpleRunner.
     * Вызывается при отмене оптимизации пользователем.
     */
    fun cancelRunner() {
        AppLog.i(TAG, "cancelRunner: cancelling SimpleRunner")
        releaseWakeLock()  // НОВОЕ (beta11): освобождаем при отмене
        simpleRunner.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // Accessibility Event Handling
    // ═══════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val currentTime: Long = System.currentTimeMillis()
        if (currentTime - lastActionTime < EVENT_THROTTLE_MS) return
        lastActionTime = currentTime

        if (SimpleRunner.isRunning) {
            handleStepTimeExceptions()
            return
        }

        if (currentTime - ChainFlags.lastRedirectTime > DEV_WATCHDOG_MS) {
            if (devWindowOpen) {
                devWindowOpen = false
                AppLog.i(TAG, "Dev watchdog window closed")
            }
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
     * Во время шага: системные ошибки, lock-screen upsell, и first-launch попапы
     * (Пропустить / Позже) — иначе на свежем телефоне шаги залипают.
     * Agree/Accept НЕ жмём здесь — этим управляет SimpleRunner (политика vs decline).
     */
    private fun handleStepTimeExceptions() {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        try {
            if (handleSystemErrors(root)) {
                AppLog.i(TAG, "Auto-dialog: system error dismissed (step running)")
                return
            }

            val screenText: String = collectAllText(root)

            if (screenText.contains("Обновите свой Экран блокировки", ignoreCase = true) ||
                screenText.contains("Update your Lock screen", ignoreCase = true)
            ) {
                clickByText(root, arrayOf("Отклонить", "Decline", "Нет", "No", "Закрыть", "Close"))
                AppLog.i(TAG, "Auto-dialog: Lock screen update → Decline")
                return
            }

            // Свежий телефон: асинхронные попапы поверх шага
            val skipTexts = arrayOf(
                "Пропустить", "Skip", "Позже", "Later", "Не сейчас", "Not now",
                "Закрыть", "Close", "Напомнить позже", "Remind me later"
            )
            if (clickByText(root, skipTexts)) {
                AppLog.i(TAG, "Auto-dialog: fresh-device skip (step running)")
            }
        } finally {
            recycleNode(root)
        }
    }

    /**
     * Системные ошибки: реальные сетевые ошибки — гасим всегда.
     * Debounce 10 сек — защита от бесконечного цикла "OK + BACK".
     */
    private fun handleSystemErrors(root: AccessibilityNodeInfo): Boolean {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastSystemErrorHandledAt < SYSTEM_ERROR_DEBOUNCE_MS) {
            return false
        }

        val screenText: String = collectAllText(root)
        val hasError: Boolean = ERROR_MARKERS.any {
            screenText.contains(it, ignoreCase = true)
        }
        if (!hasError) return false

        lastSystemErrorHandledAt = currentTime

        if (clickByText(root, ERROR_DISMISS)) {
            AppLog.i(TAG, "Auto-dialog: system error dismissed")
            return true
        }

        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            AppLog.i(TAG, "Auto-dialog: system error → BACK")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "Auto-dialog: system error BACK failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Авто-кликер диалогов первого запуска (только ВНЕ шага)
    // ═══════════════════════════════════════════════════════════════

    private fun handleFirstRunDialogs(root: AccessibilityNodeInfo) {
        if (handleSystemErrors(root)) return

        for (text: String in AUTO_ALLOW_TEXTS) {
            val nodes: List<AccessibilityNodeInfo> = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: emptyList()

            val btn: AccessibilityNodeInfo = nodes.firstOrNull { it.isClickable } ?: continue

            if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLog.i(TAG, "Auto-dialog: clicked '${LogMasker.mask(text)}'")
            }
            recycleNode(btn)
            return
        }
    }

    private fun clickByText(root: AccessibilityNodeInfo, texts: Array<String>): Boolean {
        for (text: String in texts) {
            val nodes: List<AccessibilityNodeInfo> = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: emptyList()

            val btn: AccessibilityNodeInfo = nodes.firstOrNull { it.isClickable } ?: continue

            val ok: Boolean = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycleNode(btn)
            if (ok) return true
        }
        return false
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

    // ═══════════════════════════════════════════════════════════════
    // Утилиты для работы с деревом узлов
    // ═══════════════════════════════════════════════════════════════

    private fun findNodeByTexts(
        root: AccessibilityNodeInfo,
        texts: Array<String>
    ): AccessibilityNodeInfo? {
        for (text: String in texts) {
            val nodes: List<AccessibilityNodeInfo> = runCatching {
                root.findAccessibilityNodeInfosByText(text)
            }.getOrNull() ?: emptyList()

            nodes.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth: Int = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        collectTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextRecursive(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > TEXT_DEPTH) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            collectTextRecursive(node.getChild(i), sb, depth + 1)
        }
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
}