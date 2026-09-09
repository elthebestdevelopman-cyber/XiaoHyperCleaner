package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.DirectIntentNavigator
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.DiagnosticSnapshotManager
import com.xiaohypercleaner.util.StepDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Автоматизация шагов Simple Mode через Accessibility.
 *
 * ИНТЕГРАЦИЯ (пункты оптимизации 1–6):
 * П.1 — Базовая автоматизация и Accessibility Service.
 * П.2 — Адаптивные тайм-ауты (учет HyperOS и глубины маршрута).
 * П.3 — Полная интеграция с AdaptiveCatalog (мердж текстов, пакетов, путей).
 * П.4 — Структурный поиск ⋮/⚙ (4 уровня: текст, description, позиция, жест).
 * П.5 — Перехват системных диалогов (аудио, приложения по умолчанию).
 * П.6 — Пропуск повторного сброса настроек (переиспользование окна).
 */
class SimpleRunner(private val service: AdbEnablerService) {

    companion object {
        private const val TAG = "SimpleRunner"

        // ═══════════════════════════════════════════════════════════════
        // П.2: Адаптивные таймауты
        // ═══════════════════════════════════════════════════════════════
        private const val BASE_TIMEOUT_MS = 16_000L
        private const val LONG_PATH_TIMEOUT_MS = 22_000L
        private const val VERY_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_TIMEOUT_MS = 20_000L
        private const val APP_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_VERY_LONG_PATH_TIMEOUT_MS = 32_000L
        private const val HYPEROS_MULTIPLIER = 1.3f
        private const val MSA_TIMEOUT_MS = 40_000L
        private const val SECURITY_CLEANER_TIMEOUT_MS = 22_500L

        private val SPECIAL_TIMEOUTS = mapOf(
            "msa" to MSA_TIMEOUT_MS,
            "security_sys" to SECURITY_CLEANER_TIMEOUT_MS,
            "cleaner" to SECURITY_CLEANER_TIMEOUT_MS
        )

        // ═══════════════════════════════════════════════════════════════
        // П.6: Возобновляемые шаги (не требуют сброса настроек)
        // ═══════════════════════════════════════════════════════════════
        private val SETTINGS_RESUMABLE_STEPS = setOf(
            "msa", "sys_recommendations", "ads_personalization", "ux_program", "carousel"
        )

        // ═══════════════════════════════════════════════════════════════
        // П.5: Системные диалоги
        // ═══════════════════════════════════════════════════════════════
        private val MEDIA_APP_STEPS = setOf("music_sys", "mivideo")

        // ═══════════════════════════════════════════════════════════════
        // П.4: Структурный поиск ⋮/⚙
        // ═══════════════════════════════════════════════════════════════
        private val OVERFLOW_TEXTS = listOf(
            "⋮", "Ещё", "Еще", "Больше", "Меню",
            "⚙", "⚙️", "Настройки", "Настройка",
            "More", "More options", "Menu", "Settings",
            "Больше опций", "Дополнительно", "Дополнительные настройки"
        )

        private val OVERFLOW_GESTURE_POINTS = listOf(
            Pair(0.96f, 0.06f), Pair(0.90f, 0.06f),
            Pair(0.96f, 0.12f), Pair(0.90f, 0.12f)
        )

        // ═══════════════════════════════════════════════════════════════
        // Общие константы
        // ═══════════════════════════════════════════════════════════════
        private const val UI_SETTLE_DELAY_MS = 900L
        private const val APP_LAUNCH_DELAY_MS = 2000L
        private const val CONTENT_WAIT_MS = 2500L
        private const val CONFIRM_RETRY_MS = 2500L
        private const val SWITCH_FALLBACK_SCROLLS = 3
        private const val FRESH_DEVICE_DISMISS_LIMIT = 3

        private val SYSTEM_DIALOG_SKIPS = listOf(
            "Пропустить", "Пропустить настройку", "Не сейчас", "Закрыть", "Отозвать", "Отмена"
        )

        @Volatile
        var isRunning: Boolean = false; private set
        @Volatile
        var currentStepId: String? = null; private set
        @Volatile
        var lastFailureReason: String? = null; private set
    }

    data class Result(val success: Boolean, val reason: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: kotlinx.coroutines.Job? = null

    @Volatile
    private var cancelled: Boolean = false
    private var romProfile: RomProfile = RomProfile.detect(service)

    // П.6: Флаг переиспользования окна настроек
    private var canResumeSettings: Boolean = false

    // ─── Fresh-device state ───────────────────────────────────────────────
    private var freshDeviceDismisses = 0
    private var freshDeviceActive = false
    private var mutedForFreshDevice = false
    private var originalVolume: Int = -1

    private fun muteMediaVolume(): Int {
        val audio = service.getSystemService(AudioManager::class.java) ?: return -1
        val prev = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (prev > 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            AppLog.i(TAG, "media muted (was=$prev)")
        }
        return prev
    }

    private fun restoreMediaVolume(prev: Int) {
        if (prev < 0) return
        service.getSystemService(AudioManager::class.java)
            ?.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
        AppLog.i(TAG, "media volume restored to $prev")
    }

    private fun markFreshDevice() {
        if (freshDeviceActive) return
        freshDeviceActive = true
        freshDeviceDismisses = 0
        originalVolume = muteMediaVolume()
        mutedForFreshDevice = originalVolume > 0
    }

    private fun cleanupFreshDevice() {
        if (!freshDeviceActive) return
        if (mutedForFreshDevice) restoreMediaVolume(originalVolume)
        freshDeviceActive = false
        mutedForFreshDevice = false
        freshDeviceDismisses = 0
        originalVolume = -1
    }

    private fun freshDeviceDismiss(text: String): Boolean {
        if (!freshDeviceActive || freshDeviceDismisses >= FRESH_DEVICE_DISMISS_LIMIT) return false
        freshDeviceDismisses++
        AppLog.i(
            TAG,
            "fresh-device dismiss ($freshDeviceDismisses/$FRESH_DEVICE_DISMISS_LIMIT): '$text'"
        )
        return true
    }

    // ─── Public API ───────────────────────────────────────────────────────
    fun run(step: SimpleSteps.Step, profile: RomProfile, callback: (Result) -> Unit) {
        cancel()
        cancelled = false
        isRunning = true
        currentStepId = step.id
        lastFailureReason = null
        romProfile = profile

        val timeout = computeTimeout(step, profile)
        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${timeout}ms)")

        job = scope.launch {
            val start = System.currentTimeMillis()
            StepDiagnostics.stepStart(step.id, 0, SimpleSteps.ALL.size, null, profile)

            val result = try {
                val r = withTimeoutOrNull(timeout) { runInternal(step, profile) } ?: Result(
                    false,
                    "timeout"
                )

                val root = service.rootInActiveWindow
                StepDiagnostics.stepResult(
                    step.id,
                    r.success,
                    r.reason ?: if (r.success) "ok" else "unknown",
                    System.currentTimeMillis() - start,
                    root,
                    service
                )

                if (!r.success && r.reason == "timeout") {
                    DiagnosticSnapshotManager.captureAndSaveSnapshot(
                        service,
                        step.id,
                        r.reason ?: "unknown",
                        root,
                        profile,
                        root?.packageName?.toString()
                    )
                }
                recycleNode(root)
                r
            } catch (e: Exception) {
                AppLog.e(TAG, "Step ${step.id} failed: ${e.message}", e)
                lastFailureReason = "error"
                Result(false, "error")
            } finally {
                cleanupFreshDevice()
                isRunning = false
                currentStepId = null
            }
            callback(result)
        }
    }

    // П.2: Расчёт адаптивного таймаута
    private fun computeTimeout(step: SimpleSteps.Step, profile: RomProfile): Long {
        SPECIAL_TIMEOUTS[step.id]?.let { return it }
        val isApp = step.launchPackage != null
        val drillDepth = step.drillPath.size

        val base = when {
            isApp && drillDepth >= 4 -> APP_VERY_LONG_PATH_TIMEOUT_MS
            isApp && drillDepth == 3 -> APP_LONG_PATH_TIMEOUT_MS
            isApp -> APP_TIMEOUT_MS
            drillDepth >= 5 -> VERY_LONG_PATH_TIMEOUT_MS
            drillDepth == 4 -> LONG_PATH_TIMEOUT_MS
            else -> BASE_TIMEOUT_MS
        }

        val timeout = if (profile.hyperOsHint) (base * HYPEROS_MULTIPLIER).toLong() else base
        return timeout.coerceIn(12_000L, 45_000L)
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        isRunning = false
        currentStepId = null
        AppLog.i(TAG, "Runner cancellation requested")
    }

    // ─── Internal execution ───────────────────────────────────────────────
    private suspend fun runInternal(step: SimpleSteps.Step, profile: RomProfile): Result {
        if (cancelled) return Result(false, "cancelled")

        // П.5: Перехват системных диалогов
        val mediaGrant = step.id in MEDIA_APP_STEPS
        interceptSystemDialogs(step, mediaGrant)

        // П.3: Резолвинг пакета через AdaptiveCatalog
        val resolvedPkg = AdaptiveCatalog.resolveInstalledPackageForGroup(service, step.id, profile)
            ?: AdaptiveCatalog.packagesForStep(service, step.id, step.requiredPackages, profile)
                .firstOrNull { isInstalled(it) }

        if (step.requiredPackages.isNotEmpty() && resolvedPkg == null) {
            return Result(false, "app_not_installed")
        }

        // П.6: Умный сброс настроек
        if (step.launchPackage == null) {
            if (!canResumeSettings) resetSettingsToRoot()
        } else {
            resetToHome()
            delay(300)
            if (step.forceStopBeforeLaunch && resolvedPkg != null) {
                forceStopPackage(resolvedPkg)
                delay(200)
            }
        }

        // П.3: Открытие экрана через DirectIntentNavigator
        val intents = DirectIntentNavigator.buildIntentsForStep(service, step, resolvedPkg, profile)
        var screenOpened = false
        for (intent in intents) {
            if (cancelled) return Result(false, "cancelled")
            try {
                service.startActivity(intent)
                screenOpened = true
                break
            } catch (e: Exception) {
                AppLog.w(TAG, "DirectIntentNavigator failed: ${e.message}")
            }
        }

        if (!screenOpened) {
            for (intent in step.intents) {
                if (cancelled) return Result(false, "cancelled")
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    service.startActivity(intent)
                    screenOpened = true
                    break
                } catch (e: Exception) {
                    AppLog.w(TAG, "step.intents fallback failed: ${e.message}")
                }
            }
        }

        if (!screenOpened) return Result(false, "no_screen_opened")

        delay(if (step.launchPackage != null) APP_LAUNCH_DELAY_MS else UI_SETTLE_DELAY_MS)
        interceptSystemDialogs(step, mediaGrant)

        // П.3: Навигация по маршруту
        if (step.drillPath.isNotEmpty()) {
            if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)
            val mergedDrillPath = AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
            for ((levelIndex, levelTexts) in mergedDrillPath.withIndex()) {
                if (cancelled) return Result(false, "cancelled")
                if (!drillIntoLevel(step, levelIndex, levelTexts)) return Result(
                    false,
                    "drill_failed"
                )
                delay(UI_SETTLE_DELAY_MS)
            }
        }

        val result = findAndToggleSwitch(step)

        // П.6: Помечаем, что следующий шаг может переиспользовать окно
        canResumeSettings = result.success && step.id in SETTINGS_RESUMABLE_STEPS
        return result
    }

    // ─── Drill navigation ─────────────────────────────────────────────────
    private suspend fun drillIntoLevel(
        step: SimpleSteps.Step,
        levelIndex: Int,
        levelTexts: List<String>
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)

        val isGearLevel = levelTexts.any { it in listOf("⚙", "⚙️", "⋮") }
        if (isGearLevel && findAndTapOverflow(levelTexts)) return true

        val node = findClickableByTextWithScroll(levelTexts)
        if (node == null) {
            AppLog.w(
                TAG,
                "Drill level '${levelTexts.firstOrNull()}' not found, screen=[${screenText.take(120)}]"
            )
            return false
        }

        if (!tapNode(node)) {
            recycleNode(node); return false
        }
        recycleNode(node)

        val nextTexts = AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
            .getOrNull(levelIndex + 1)?.firstOrNull() ?: return true
        return awaitScreen(listOf(nextTexts))
    }

    // ─── Switch finding and toggling ──────────────────────────────────────
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        val mergedSearchTexts = AdaptiveCatalog.mergeSearchTexts(service, step.id, step.searchTexts)
        if (mergedSearchTexts.isEmpty()) return Result(true, "no_switch")

        if (!awaitScreen(mergedSearchTexts)) return Result(false, "switch_not_found")

        var currentRoot: AccessibilityNodeInfo? =
            service.rootInActiveWindow ?: return Result(false, "no_root_window")
        var switchNode: AccessibilityNodeInfo? = findSwitchByText(currentRoot, mergedSearchTexts)

        // Fallback: скролл (ИСПРАВЛЕНО: не ресайклим root, если нашли ноду, чтобы избежать IllegalStateException)
        if (switchNode == null && mergedSearchTexts.isNotEmpty()) {
            recycleNode(currentRoot)
            repeat(SWITCH_FALLBACK_SCROLLS) {
                if (cancelled) return Result(false, "cancelled")
                val r = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                val scrollable = findScrollableContainer(r)
                if (scrollable == null) {
                    recycleNode(r); return@repeat
                }

                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                recycleNode(scrollable); recycleNode(r)
                if (!scrolled) swipeUp()
                delay(400)

                currentRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                switchNode = findSwitchByText(currentRoot, mergedSearchTexts)
                if (switchNode != null) return@repeat // Оставляем currentRoot в живых для switchNode
                recycleNode(currentRoot)
                currentRoot = null
            }
        }

        if (switchNode == null) {
            recycleNode(currentRoot)
            return Result(true, "feature_absent")
        }

        val isChecked = switchNode.isChecked
        val text = switchNode.text?.toString() ?: mergedSearchTexts.first()

        if (isChecked == step.targetChecked) {
            recycleNode(switchNode); recycleNode(currentRoot)
            return Result(true, "already_done")
        }

        if (!tapNode(switchNode)) {
            recycleNode(switchNode); recycleNode(currentRoot)
            return Result(false, "tap_failed")
        }
        recycleNode(switchNode); recycleNode(currentRoot)

        delay(600)
        if (!verifySwitchState(step, mergedSearchTexts)) {
            val retryRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
            val retryNode = findSwitchByText(retryRoot, mergedSearchTexts)
            if (retryNode != null) tapNode(retryNode)
            recycleNode(retryNode); recycleNode(retryRoot)
            delay(600)
            if (!verifySwitchState(step, mergedSearchTexts)) return Result(false, "verify_failed")
        }

        // П.3: Подтверждение
        if (step.confirmTexts.isNotEmpty()) {
            val mergedConfirmTexts =
                AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts)
            if (step.confirmWaitMs > 0) delay(step.confirmWaitMs)
            for (attempt in 1..3) {
                val confirmRoot = service.rootInActiveWindow ?: break
                val confirmNode = findClickableByText(confirmRoot, mergedConfirmTexts)
                if (confirmNode != null) {
                    tapNode(confirmNode)
                    recycleNode(confirmNode); recycleNode(confirmRoot)
                    break
                }
                recycleNode(confirmRoot)
                delay(CONFIRM_RETRY_MS)
            }
        }

        // П.3: Дополнительные переключатели
        val mergedAdditionalToggles =
            AdaptiveCatalog.mergeAdditionalToggles(service, step.id, step.additionalToggles)
        for (toggleText in mergedAdditionalToggles) {
            if (cancelled) break
            val addRoot = service.rootInActiveWindow ?: continue
            val addNode = findSwitchByText(addRoot, listOf(toggleText))
            if (addNode != null && addNode.isChecked != step.targetChecked) tapNode(addNode)
            recycleNode(addNode); recycleNode(addRoot)
            delay(400)
        }

        return Result(true, "toggled")
    }

    private suspend fun verifySwitchState(step: SimpleSteps.Step, texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return true
        val switchNode = findSwitchByText(root, texts)
        val result = switchNode?.let { it.isChecked == step.targetChecked } ?: true
        recycleNode(switchNode); recycleNode(root)
        return result
    }

    // ═════════════════════════════════════════════════════════════════════
    // П.4: Структурный поиск ⋮/⚙ (4 уровня)
    // ═════════════════════════════════════════════════════════════════════
    private suspend fun findAndTapOverflow(texts: List<String> = OVERFLOW_TEXTS): Boolean {
        val root = service.rootInActiveWindow ?: return false

        findClickableByText(root, texts)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByContentDescription(root)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByPosition(root)?.let {
            val tapped = tapNode(it); recycleNode(it); recycleNode(root); return tapped
        }
        recycleNode(root)
        return performOverflowGesture()
    }

    private fun findOverflowByContentDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.isNotBlank() && OVERFLOW_TEXTS.any { desc.contains(it, ignoreCase = true) }) {
                result.add(node); return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result.firstOrNull()
    }

    private fun findOverflowByPosition(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = service.resources.displayMetrics
        val xMin = (dm.widthPixels * 0.85f).toInt()
        val yMax = (dm.heightPixels * 0.15f).toInt()
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() >= xMin && rect.centerY() <= yMax) candidates.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return candidates.maxByOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.centerY() * 1000 - r.centerX()
        }
    }

    private suspend fun performOverflowGesture(): Boolean {
        val dm = service.resources.displayMetrics
        for ((fx, fy) in OVERFLOW_GESTURE_POINTS) {
            if (cancelled) return false
            if (tapAt((dm.widthPixels * fx).toInt(), (dm.heightPixels * fy).toInt())) {
                delay(UI_SETTLE_DELAY_MS); return true
            }
        }
        return false
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return suspendCancellableCoroutine { cont ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // П.5: Перехват системных диалогов
    // ═════════════════════════════════════════════════════════════════════
    private suspend fun interceptSystemDialogs(step: SimpleSteps.Step, mediaGrant: Boolean) {
        var attempts = 0
        while (attempts < 5 && !cancelled) {
            val root = service.rootInActiveWindow ?: break
            val screenText = collectAllText(root)
            recycleNode(root)

            val isAudioDialog =
                screenText.contains("Разрешить приложению") && (screenText.contains("музык") || screenText.contains(
                    "аудио"
                ))
            val isDefaultAppDialog =
                screenText.contains("приложением по умолчанию") || screenText.contains("приложением для обмена")

            if (!isAudioDialog && !isDefaultAppDialog) break
            attempts++

            if (isAudioDialog) {
                val targetTexts = if (mediaGrant) listOf(
                    "РАЗРЕШИТЬ",
                    "Разрешить",
                    "ALLOW",
                    "Allow"
                ) else listOf("ЗАПРЕТИТЬ", "Запретить", "DENY", "Deny")
                if (tapSystemDialogButton(targetTexts)) {
                    delay(UI_SETTLE_DELAY_MS); continue
                }
            }
            if (isDefaultAppDialog) {
                if (tapSystemDialogButton(listOf("ОТМЕНА", "Отмена", "CANCEL", "Cancel"))) {
                    delay(UI_SETTLE_DELAY_MS); continue
                }
            }
            break
        }
    }

    private suspend fun tapSystemDialogButton(texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes.orEmpty()) {
                if (node.isClickable) {
                    val tapped = tapNode(node)
                    recycleNode(node); recycleNode(root)
                    return tapped
                }
            }
        }
        recycleNode(root)
        return false
    }

    // ─── Navigation & Utilities ───────────────────────────────────────────
    private suspend fun resetSettingsToRoot(): Boolean {
        return try {
            service.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            delay(CONTENT_WAIT_MS); true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resetToHome(): Boolean {
        return try {
            service.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            delay(500); true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun swipeUp() {
        val dm = service.resources.displayMetrics
        performGesture(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.8f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.2f,
            300
        )
    }

    private fun findSwitchByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        for (text in texts) {
            for (node in root.findAccessibilityNodeInfosByText(text).orEmpty()) {
                if (isSwitchLike(node)) return node
                if (node.parent != null && isSwitchLike(node.parent)) return node.parent
            }
        }
        return null
    }

    private fun isSwitchLike(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        return cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("ToggleButton") || node.isCheckable
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo? = service.rootInActiveWindow,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        for (text in texts) {
            for (node in root.findAccessibilityNodeInfosByText(text).orEmpty()) {
                if (node.isClickable) return node
                var parent = node.parent;
                var depth = 0
                while (parent != null && depth < 5) {
                    if (parent.isClickable) return parent
                    parent = parent.parent; depth++
                }
            }
        }
        return null
    }

    private suspend fun findClickableByTextWithScroll(texts: List<String>): AccessibilityNodeInfo? {
        repeat(SWITCH_FALLBACK_SCROLLS) {
            findClickableByText(texts = texts)?.let { return it }
            val root = service.rootInActiveWindow ?: return null
            val scrollable = findScrollableContainer(root)
            if (scrollable == null) {
                recycleNode(root); return null
            }
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) swipeUp()
            recycleNode(scrollable); recycleNode(root)
            delay(400)
        }
        return findClickableByText(texts = texts)
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = root
        while (node != null) {
            if (node.isScrollable) return node; node = node.parent
        }
        return null
    }

    private suspend fun awaitScreen(markers: List<String>, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cancelled) return false
            val root = service.rootInActiveWindow
            if (root != null) {
                val text = collectAllText(root)
                if (markers.any { text.contains(it, ignoreCase = true) }) {
                    recycleNode(root); return true
                }
                recycleNode(root)
            }
            delay(200)
        }
        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent;
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent; depth++
        }
        val rect = Rect(); node.getBoundsInScreen(rect)
        return performGesture(
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            100
        )
    }

    private suspend fun performGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched =
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    private fun isInstalled(pkg: String): Boolean = try {
        service.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun forceStopPackage(pkg: String) {
        try {
            val am = service.getSystemService(android.app.ActivityManager::class.java)
            am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        } catch (e: Exception) {
            AppLog.w(TAG, "forceStop failed: ${e.message}")
        }
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }
}