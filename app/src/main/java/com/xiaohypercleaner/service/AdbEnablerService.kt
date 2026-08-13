package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.OptimizationOptions
import com.xiaohypercleaner.data.OptimizationReport
import com.xiaohypercleaner.data.SimpleOptimizationRunner
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object SimpleStepBridge {
    var onResult: ((Boolean) -> Unit)? = null
}

@Suppress("DEPRECATION")
@OptIn(FlowPreview::class)
class AdbEnablerService : AccessibilityService() {

    companion object {
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.action.START_CHAIN"
        const val ACTION_RETRY_DEV = "com.xiaohypercleaner.action.RETRY_DEV"
        const val ACTION_SIMPLE_STEP = "com.xiaohypercleaner.action.SIMPLE_STEP"
        private const val TAG = "XHC"
        private const val STEP_DELAY_MS = 1500L
        private const val OPTIMIZATION_DELAY_MS = 2000L
        private const val DEV_WATCHDOG_MS = 8000L
        private const val EVENT_DEBOUNCE_MS = 250L
    }

    private enum class Step {
        IDLE, OVERLAY_TOGGLE, DEV_SETTINGS, WIRELESS_DEBUG, ALLOW_DIALOG, OPTIMIZATION, VERIFICATION, DONE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var chainActive = false
    private var chainCancelled = false
    private var currentStep = Step.IDLE
    private var lastOverlayUpdate = 0L
    private var lastProgressUpdate = 0L

    private var paused = false
    private var devToggleFound = false
    private var devWatchdogRunnable: Runnable? = null

    private val accessibilityEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayController.register(object : OverlayController.CancelHandler {
            override fun cancelChain() {
                this@AdbEnablerService.cancelChain()
            }
        })
        AppLog.i(TAG, "AdbEnablerService: connected")

        if (ChainFlags.waitingAccessibilityReturn) {
            ChainFlags.waitingAccessibilityReturn = false
            AppLog.i(
                TAG,
                "AdbEnablerService: service enabled — returning user to app automatically"
            )
            try {
                val intent = Intent(this, com.xiaohypercleaner.ui.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            } catch (e: Exception) {
                AppLog.w(TAG, "AdbEnablerService: failed to return to app: ${e.message}")
            }
        }

        scope.launch {
            accessibilityEvents
                .debounce(EVENT_DEBOUNCE_MS)
                .collect {
                    processLatestAccessibilityEvent()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CHAIN -> startChain()
            ACTION_RETRY_DEV -> retryDevSettings()
            ACTION_SIMPLE_STEP -> {
                val stepIndex = intent.getIntExtra("step_index", 0)
                executeSimpleStep(stepIndex)
            }
        }
        return START_NOT_STICKY
    }

    private fun executeSimpleStep(stepIndex: Int) {
        if (stepIndex >= SimpleSteps.ALL.size) {
            AppLog.w(TAG, "executeSimpleStep: index out of range: $stepIndex")
            return
        }
        val step = SimpleSteps.ALL[stepIndex]
        AppLog.i(TAG, "executeSimpleStep: ${step.id}")

        scope.launch {
            val runner = SimpleOptimizationRunner(this@AdbEnablerService)
            val result = runner.executeStep(step)
            AppLog.i(
                TAG,
                "executeSimpleStep: result success=${result.success}, reason=${result.reason}"
            )
            SimpleStepBridge.onResult?.invoke(result.success)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!chainActive || chainCancelled) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == packageName
        ) {
            AppLog.i(TAG, "AdbEnablerService: user in app (step=$currentStep)")
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            scope.launch {
                accessibilityEvents.emit(Unit)
            }
        }
    }

    private fun processLatestAccessibilityEvent() {
        if (!chainActive || chainCancelled) return
        val root = rootInActiveWindow ?: return
        try {
            processEvent(root)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                root.recycle()
            }
        }
    }

    private fun startDevWatchdog() {
        cancelDevWatchdog()
        devToggleFound = false
        val r = Runnable {
            if (chainActive && !chainCancelled && currentStep == Step.DEV_SETTINGS && !devToggleFound) {
                AppLog.i(
                    TAG,
                    "AdbEnablerService: dev watchdog fired — developer mode likely disabled"
                )
                paused = true
                OptimizationNotifier.setDevModeRequired()
            }
        }
        devWatchdogRunnable = r
        handler.postDelayed(r, DEV_WATCHDOG_MS)
    }

    private fun cancelDevWatchdog() {
        devWatchdogRunnable?.let { handler.removeCallbacks(it) }
        devWatchdogRunnable = null
    }

    private fun retryDevSettings() {
        if (!chainActive || chainCancelled) return
        AppLog.i(TAG, "AdbEnablerService: retryDevSettings — user says dev mode enabled")
        paused = false
        startOverlay()
        currentStep = Step.DEV_SETTINGS
        overlayHint(getString(R.string.hint_dev))
        openDevSettings()
        startDevWatchdog()
    }

    private fun processEvent(root: AccessibilityNodeInfo) {
        when (currentStep) {
            Step.OVERLAY_TOGGLE -> handleOverlayToggle(root)
            Step.DEV_SETTINGS -> handleDevSettings(root)
            Step.WIRELESS_DEBUG -> handleWirelessDebug(root)
            Step.ALLOW_DIALOG -> handleAllowDialog(root)
            else -> {}
        }
    }

    override fun onInterrupt() {
        cancelChain()
    }

    private fun startChain() {
        if (chainActive) return
        chainActive = true
        chainCancelled = false
        paused = false
        OptimizationNotifier.setRunning()
        startOverlay()
        AppLog.i(TAG, "AdbEnablerService: chain started")

        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)

        if (overlayGranted) {
            AppLog.i(TAG, "AdbEnablerService: overlay already granted, going to dev settings")
            currentStep = Step.DEV_SETTINGS
            overlayHint(getString(R.string.hint_dev))
            openDevSettings()
            startDevWatchdog()
        } else {
            currentStep = Step.OVERLAY_TOGGLE
            overlayHint(getString(R.string.hint_overlay))
            openOverlaySettings()
        }
    }

    private fun goToDevSettingsStep() {
        currentStep = Step.DEV_SETTINGS
        overlayHint(getString(R.string.hint_dev))
        openDevSettings()
        startDevWatchdog()
    }

    private fun handleOverlayToggle(root: AccessibilityNodeInfo) {
        val texts = listOf(
            getString(R.string.overlay_permission_title),
            "Display over other apps",
            "Поверх других приложений",
            "Отображать поверх других окон",
            "Над другими приложениями"
        )
        val node = findNodeByText(root, texts) ?: return

        val switch = findSwitchNode(node)
        if (switch != null && !switch.isChecked) {
            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i(TAG, "AdbEnablerService: overlay toggle clicked")
            handler.postDelayed({
                if (!chainCancelled && currentStep == Step.OVERLAY_TOGGLE) {
                    goToDevSettingsStep()
                }
            }, STEP_DELAY_MS)
        } else if (switch != null && switch.isChecked && currentStep == Step.OVERLAY_TOGGLE) {
            AppLog.i(TAG, "AdbEnablerService: overlay already on, going to dev settings")
            goToDevSettingsStep()
        }
    }

    private fun handleDevSettings(root: AccessibilityNodeInfo) {
        val texts = listOf(
            "Wireless debugging", "Беспроводная отладка",
            "Отладка по Wi-Fi", "Отладка по беспроводной сети",
            "Wireless ADB", "Wi-Fi debugging", "Wi-Fi ADB", "Wireless debug",
            "Отладка по сети", "Network debugging", "ADB over network",
            "Отладка ADB", "ADB debugging",
            "Беспроводная отладка ADB",
            "Wireless ADB debugging",
            "Отладка по беспроводной сети ADB",
            "Wi-Fi ADB отладка",
            "Беспроводной ADB",
            "Отладка Wi-Fi",
            "Wi-Fi отладка",
            "Отладка по Wi‑Fi",
            "Беспроводная отладка по сети"
        )

        AppLog.i(
            TAG,
            "AdbEnablerService: searching for wireless debug toggle in ${texts.size} variants"
        )

        val node = findNodeByText(root, texts)

        if (node != null) {
            devToggleFound = true
            cancelDevWatchdog()
            AppLog.i(TAG, "AdbEnablerService: found node with text: ${node.text}")

            val switch = findSwitchNode(node)
            if (switch == null) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLog.i(
                    TAG,
                    "AdbEnablerService: wireless debugging row clicked (opening subscreen)"
                )
                return
            }
            if (!switch.isChecked) {
                switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLog.i(TAG, "AdbEnablerService: wireless debug toggle clicked")
                currentStep = Step.ALLOW_DIALOG
                overlayHint(getString(R.string.hint_allow))
            } else if (currentStep == Step.DEV_SETTINGS) {
                AppLog.i(TAG, "AdbEnablerService: wireless debug already on, starting optimization")
                currentStep = Step.OPTIMIZATION
                overlaySafe(getString(R.string.overlay_connecting))
                handler.postDelayed({
                    if (!chainCancelled) runOptimization()
                }, 500)
            }
            return
        }

        val usbDebugTexts = listOf(
            "USB debugging", "Отладка по USB", "USB debug",
            "USB отладка", "Отладка USB", "USB отладка по USB"
        )
        val usbNode = findNodeByText(root, usbDebugTexts)
        if (usbNode != null) {
            val usbSwitch = findSwitchNode(usbNode)
            if (usbSwitch != null && !usbSwitch.isChecked) {
                usbSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AppLog.i(TAG, "AdbEnablerService: USB debugging enabled (MIUI prerequisite)")
                overlayHint(getString(R.string.hint_dev))
                return
            }
        }

        val screenText = StringBuilder()
        collectScreenText(root, screenText, 0)
        val screenPreview = screenText.toString().take(2000)
        AppLog.w(
            TAG,
            "AdbEnablerService: no wireless/USB toggle found. Screen content (first 2000 chars):\n$screenPreview"
        )
        AppLog.i(TAG, "AdbEnablerService: waiting for watchdog")
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo) {
        val texts = listOf(
            "Wireless debugging", "Беспроводная отладка",
            "Отладка по Wi-Fi", "Отладка по беспроводной сети",
            "Wireless ADB", "Wi-Fi debugging", "Wi-Fi ADB", "Wireless debug",
            "Отладка по сети", "Network debugging", "ADB over network",
            "Отладка ADB", "ADB debugging"
        )
        val node = findNodeByText(root, texts) ?: return

        val switch = findSwitchNode(node)
        if (switch != null && !switch.isChecked) {
            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i(TAG, "AdbEnablerService: wireless debug enabled")
            currentStep = Step.ALLOW_DIALOG
            overlayHint(getString(R.string.hint_allow))
        } else if (switch != null && switch.isChecked && currentStep == Step.WIRELESS_DEBUG) {
            AppLog.i(TAG, "AdbEnablerService: wireless debug already on, starting optimization")
            currentStep = Step.OPTIMIZATION
            overlaySafe(getString(R.string.overlay_connecting))
            handler.postDelayed({
                if (!chainCancelled) runOptimization()
            }, 500)
        }
    }

    private fun handleAllowDialog(root: AccessibilityNodeInfo) {
        val texts = listOf(
            getString(R.string.allow_button_ru),
            getString(R.string.allow_button_en),
            "Разрешить", "Allow", "ОК", "OK",
            "Разрешить в любом случае", "Allow anyway",
            "Продолжить", "Continue",
            "Понятно", "Got it",
            "Подтвердить", "Confirm",
            "Включить", "Turn on"
        )
        val node = findNodeByText(root, texts) ?: return

        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLog.i(TAG, "AdbEnablerService: allow/warning button clicked")

        handler.postDelayed({
            if (!chainCancelled) {
                currentStep = Step.DEV_SETTINGS
                overlayHint(getString(R.string.hint_dev))
            }
        }, OPTIMIZATION_DELAY_MS)
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
            }
        }
    }

    private fun openDevSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
            }
        }
    }

    private fun runOptimization() {
        AppLog.i(TAG, "AdbEnablerService: runOptimization started")
        scope.launch {
            try {
                val app = application as XiaoHyperApp
                val deps = XiaoHyperApp.testDeps ?: app.deps

                val dnsEnabled = deps.preferencesManager.getDnsFilterEnabled()
                val aggressiveEnabled = deps.preferencesManager.aggressiveMode.first()
                AppLog.i(
                    TAG,
                    "AdbEnablerService: dnsFilter=$dnsEnabled, aggressive=$aggressiveEnabled"
                )

                val engine = deps.newEngine()
                val options = OptimizationOptions(
                    dnsFilter = dnsEnabled,
                    aggressiveMode = aggressiveEnabled
                )

                val callbacks = OptimizationEngine.Callbacks(
                    onStage = { stage ->
                        AppLog.i(TAG, "AdbEnablerService: stage=$stage")
                        overlayStage(stage)
                    },
                    onProgress = { p -> overlayProgress(p) },
                    onError = { err ->
                        AppLog.e(TAG, "AdbEnablerService: engine error=$err")
                        overlayError(err)
                    }
                )

                overlaySafe(getString(R.string.overlay_connecting))
                val report = engine.optimize(options, callbacks)
                AppLog.i(TAG, "AdbEnablerService: optimize result success=${report.success}")
                AppLog.i(
                    TAG,
                    "AdbEnablerService: disabled ${report.disabledPackages.size} packages, applied ${report.appliedSettings.size} settings"
                )

                report.rollbackReport?.let { rb ->
                    AppLog.i(TAG, "AdbEnablerService: rollback report: ${rb.summary()}")
                }

                if (report.success) {
                    currentStep = Step.VERIFICATION
                    overlaySafe(getString(R.string.overlay_verifying))

                    deps.preferencesManager.setHiddenSettingsApplied(true)
                    OptimizationNotifier.setSuccess(buildReportSummary(report))
                    overlaySafe(getString(R.string.overlay_done))
                } else {
                    OptimizationNotifier.setFailure(
                        report.failedActions,
                        buildReportSummary(report)
                    )
                    overlaySafe(getString(R.string.overlay_failed))
                }

                finishChain()
            } catch (e: Exception) {
                AppLog.e(TAG, "AdbEnablerService: optimization exception: ${e.message}")
                OptimizationNotifier.setFailure(listOf("exception"), e.message ?: "Unknown error")
                overlaySafe(getString(R.string.overlay_failed))
                finishChain()
            }
        }
    }

    private fun buildReportSummary(report: OptimizationReport): String {
        return buildString {
            append("✅ Отключено сервисов: ${report.disabledPackages.size}\n")
            append("✅ Применено параметров: ${report.appliedSettings.size}\n")
            if (report.failedActions.isNotEmpty()) {
                append("⚠️ Не удалось: ${report.failedActions.joinToString(", ")}\n")
            }
            report.rollbackReport?.let { rb ->
                append("\n")
                append(rb.summary())
            }
            append("\n")
            append(if (report.success) "✅ Все проверки пройдены" else "❌ Проверка не пройдена")
        }
    }

    private fun finishChain() {
        chainActive = false
        currentStep = Step.DONE
        cancelDevWatchdog()
        OverlayController.clear()
        AppLog.i(TAG, "AdbEnablerService: chain finished")
        handler.postDelayed({
            stopOverlay()
            disableSelf()
            stopSelf()
        }, 1500L)
    }

    private fun cancelChain() {
        if (chainCancelled) return
        chainCancelled = true
        chainActive = false
        currentStep = Step.IDLE
        cancelDevWatchdog()
        OptimizationNotifier.reset()
        AppLog.i(TAG, "AdbEnablerService: chain cancelled")
        OverlayController.clear()
        stopOverlay()
        disableSelf()
        stopSelf()
    }

    override fun onDestroy() {
        cancelDevWatchdog()
        OverlayController.clear()
        SimpleStepBridge.onResult = null
        scope.cancel()
        super.onDestroy()
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                return nodes.firstOrNull()
            }
        }
        return null
    }

    private fun findSwitchNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch") || className.contains("Toggle")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSwitchNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun collectScreenText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 10 || sb.length > 3000) return

        node.text?.let { text ->
            if (text.isNotEmpty()) {
                sb.append("  ".repeat(depth)).append(text).append("\n")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenText(child, sb, depth + 1)
        }
    }

    private fun startOverlay() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("status", getString(R.string.status_working))
            intent.putExtra("progress", 0f)
            startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "AdbEnablerService: failed to start overlay: ${e.message}")
        }
    }

    private fun stopOverlay() {
        try {
            stopService(Intent(this, OverlayService::class.java))
        } catch (e: Exception) {
            AppLog.w(TAG, "AdbEnablerService: failed to stop overlay: ${e.message}")
        }
    }

    private fun overlaySafe(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastOverlayUpdate < 500) return
        lastOverlayUpdate = now
        try {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("detail", text)
            startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "AdbEnablerService: overlay detail failed: ${e.message}")
        }
    }

    private fun overlayHint(text: String) {
        try {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("hint", text)
            startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "AdbEnablerService: overlay hint failed: ${e.message}")
        }
    }

    private fun overlayProgress(progress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate < 200) return
        lastProgressUpdate = now
        try {
            val intent = Intent(this, OverlayService::class.java)
            intent.putExtra("progress", progress)
            startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "AdbEnablerService: overlay progress failed: ${e.message}")
        }
    }

    private fun overlayStage(stage: String) {
        val text = when (stage) {
            "connecting" -> getString(R.string.overlay_connecting)
            "method1" -> getString(R.string.overlay_method1)
            "method2" -> getString(R.string.overlay_method2)
            "method3" -> getString(R.string.overlay_method3)
            "method4" -> getString(R.string.overlay_method4)
            "method5" -> getString(R.string.overlay_method5)
            "verifying" -> getString(R.string.overlay_verifying)
            else -> getString(R.string.overlay_preparing)
        }
        overlaySafe(text)
    }

    private fun overlayError(error: String) {
        val text = when (error) {
            "connect_failed" -> getString(R.string.overlay_connect_failed)
            "verify_method1_failed" -> getString(R.string.overlay_verify_method1_failed)
            "verify_method2_failed" -> getString(R.string.overlay_verify_method2_failed)
            "verify_method3_failed" -> getString(R.string.overlay_verify_method3_failed)
            "verify_method4_failed" -> getString(R.string.overlay_verify_method4_failed)
            "final_verification_failed" -> getString(R.string.overlay_final_verification_failed)
            else -> getString(R.string.overlay_failed)
        }
        overlaySafe(text)
    }
}