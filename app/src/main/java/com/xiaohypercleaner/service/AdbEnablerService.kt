package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.OptimizationNotifier
import com.xiaohypercleaner.data.OptimizationOptions
import com.xiaohypercleaner.data.OptimizationReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AdbEnablerService : AccessibilityService() {

    companion object {
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.action.START_CHAIN"
        private const val TAG = "XHC"
        private const val STEP_DELAY_MS = 1500L
        private const val OPTIMIZATION_DELAY_MS = 2000L
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

    // Явно указываем тип, чтобы избежать рекурсивной проверки типов
    private val cancelHandler: OverlayController.CancelHandler =
        object : OverlayController.CancelHandler {
            override fun cancelChain() {
                this@AdbEnablerService.cancelChain()
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayController.register(cancelHandler)
        AppLog.i(TAG, "AdbEnablerService: connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CHAIN) {
            startChain()
        }
        return START_NOT_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!chainActive || chainCancelled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val root = rootInActiveWindow ?: return
                try {
                    processEvent(root)
                } finally {
                    root.recycle()
                }
            }
        }
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
        currentStep = Step.OVERLAY_TOGGLE
        OptimizationNotifier.setRunning()
        startOverlay()
        AppLog.i(TAG, "AdbEnablerService: chain started")

        overlaySafe(getString(R.string.overlay_searching_overlay_switch))
        openOverlaySettings()
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
        }

        handler.postDelayed({
            if (!chainCancelled) {
                currentStep = Step.DEV_SETTINGS
                overlaySafe(getString(R.string.overlay_searching_toggle))
                openDevSettings()
            }
        }, STEP_DELAY_MS)
    }

    private fun handleDevSettings(root: AccessibilityNodeInfo) {
        val texts = resources.getStringArray(R.array.wireless_debugging_texts).toList()
        val node = findNodeByText(root, texts) ?: return

        val switch = findSwitchNode(node)
        if (switch != null && !switch.isChecked) {
            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i(TAG, "AdbEnablerService: wireless debug toggle clicked")
            currentStep = Step.ALLOW_DIALOG
            overlaySafe(getString(R.string.overlay_clicking_allow))
        }
    }

    private fun handleWirelessDebug(root: AccessibilityNodeInfo) {
        val texts = resources.getStringArray(R.array.wireless_debugging_texts).toList()
        val node = findNodeByText(root, texts) ?: return

        val switch = findSwitchNode(node)
        if (switch != null && !switch.isChecked) {
            switch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            AppLog.i(TAG, "AdbEnablerService: wireless debug enabled")
            currentStep = Step.ALLOW_DIALOG
            overlaySafe(getString(R.string.overlay_clicking_allow))
        }
    }

    private fun handleAllowDialog(root: AccessibilityNodeInfo) {
        val texts = listOf(
            getString(R.string.allow_button_ru),
            getString(R.string.allow_button_en),
            "Разрешить",
            "Allow",
            "ОК",
            "OK"
        )
        val node = findNodeByText(root, texts) ?: return

        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLog.i(TAG, "AdbEnablerService: allow button clicked")

        handler.postDelayed({
            if (!chainCancelled) {
                currentStep = Step.OPTIMIZATION
                overlaySafe(getString(R.string.overlay_connecting))
                runOptimization()
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

                // Читаем настройки DNS из DataStore
                val dnsEnabled = deps.preferencesManager.getDnsFilterEnabled()
                AppLog.i(TAG, "AdbEnablerService: dnsFilter=$dnsEnabled")

                val engine = deps.newEngine()
                val options = OptimizationOptions(dnsFilter = dnsEnabled)

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
            append(if (report.success) "✅ Все проверки пройдены" else "❌ Проверка не пройдена")
        }
    }

    private fun finishChain() {
        chainActive = false
        currentStep = Step.DONE
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
        OptimizationNotifier.reset()
        AppLog.i(TAG, "AdbEnablerService: chain cancelled")
        OverlayController.clear()
        stopOverlay()
        disableSelf()
        stopSelf()
    }

    override fun onDestroy() {
        OverlayController.clear()
        scope.cancel()
        super.onDestroy()
    }

    // ===== Helpers =====

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
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
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

    private fun overlayProgress(progress: Float) {
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