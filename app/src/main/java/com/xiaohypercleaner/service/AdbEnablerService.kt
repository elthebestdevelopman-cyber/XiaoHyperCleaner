package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.util.waitFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AdbEnablerService : AccessibilityService() {

    companion object {
        const val ACTION_START_CHAIN = "com.xiaohypercleaner.START_CHAIN"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val app get() = application as XiaoHyperApp
    private val toggleTexts by lazy {
        resources.getStringArray(R.array.wireless_debugging_texts)
    }
    private var overlayHandled = false
    private var optimizationStarted = false
    private var lastOverlayKey = ""
    private var lastPercent = -1

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CHAIN) {
            OverlayController.onCancel = { cancelChain() }
            scope.launch {
                if (!Settings.canDrawOverlays(this@AdbEnablerService)) {
                    openOverlaySettings()
                } else {
                    openDevSettings()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || optimizationStarted) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("settings", true)) return
        val root = rootInActiveWindow ?: return

        if (clickAllow(root)) return

        if (!overlayHandled && !Settings.canDrawOverlays(this)) {
            overlaySafe(
                getString(R.string.status_working),
                getString(R.string.overlay_searching_overlay_switch)
            )
            val sw = findOurOverlaySwitch(root)
            if (sw != null) {
                overlayHandled = true
                if (sw.isCheckable && !sw.isChecked) {
                    sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                scope.launch {
                    val granted = waitFor(AppConstants.OVERLAY_WAIT_TIMEOUT_MS) {
                        Settings.canDrawOverlays(this@AdbEnablerService)
                    }
                    if (granted) {
                        overlaySafe(
                            getString(R.string.status_working),
                            getString(R.string.overlay_clicking_allow)
                        )
                        clickAllow(rootInActiveWindow)
                        openDevSettings()
                    }
                }
                return
            }
        }

        if (!optimizationStarted) {
            val toggle = findCheckable(root, *toggleTexts)
            if (toggle != null) {
                overlaySafe(
                    getString(R.string.status_working),
                    getString(R.string.overlay_searching_toggle)
                )
                if (toggle.isCheckable && !toggle.isChecked) {
                    toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                scope.launch {
                    waitFor(AppConstants.UI_WAIT_TIMEOUT_MS) {
                        val r = rootInActiveWindow ?: return@waitFor false
                        val t = findCheckable(r, *toggleTexts)
                        t != null && t.isChecked
                    }
                    overlaySafe(
                        getString(R.string.status_working),
                        getString(R.string.overlay_clicking_allow)
                    )
                    clickAllow(rootInActiveWindow)
                    startOptimization()
                }
            }
        }
    }

    private fun cancelChain() {
        optimizationStarted = true
        OverlayController.onCancel = null
        stopOverlay()
        disableSelf()
        stopSelf()
    }

    private fun clickAllow(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        for (text in arrayOf(
            getString(R.string.allow_button_ru),
            getString(R.string.allow_button_en)
        )) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (n in nodes) {
                val cls = n.className?.toString() ?: continue
                if (n.isClickable && cls.contains("Button")) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return false
    }

    private fun findOurOverlaySwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = applicationInfo.loadLabel(packageManager).toString()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val text = node.text?.toString().orEmpty()
            if (node.isCheckable && text.contains(label, true)) return node
            for (i in 0 until node.childCount) {
                val r = walk(node.getChild(i))
                if (r != null) return r
            }
            return null
        }
        return walk(root)
    }

    private fun findCheckable(
        root: AccessibilityNodeInfo,
        vararg texts: String
    ): AccessibilityNodeInfo? {
        for (t in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(t) ?: continue
            for (n in nodes) if (n.isCheckable) return n
        }
        return null
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openDevSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        scope.launch {
            waitFor(AppConstants.DEV_SETTINGS_FALLBACK_MS) { optimizationStarted }
            if (!optimizationStarted) startOptimization()
        }
    }

    private fun startOptimization() {
        if (optimizationStarted) return
        optimizationStarted = true
        scope.launch {
            val engine = app.deps.newEngine()
            val result = engine.optimize(
                OptimizationEngine.Callbacks(
                    onStage = { stage ->
                        val text = when (stage) {
                            "connecting" -> getString(R.string.overlay_connecting)
                            "method1" -> getString(R.string.overlay_method1)
                            "method2" -> getString(R.string.overlay_method2)
                            "method3" -> getString(R.string.overlay_method3)
                            "verifying" -> getString(R.string.overlay_verifying)
                            else -> ""
                        }
                        if (text.isNotEmpty()) overlaySafe(text, "")
                    },
                    onProgress = { p -> overlayProgress(p) },
                    onError = { overlaySafe(getString(R.string.overlay_connect_failed), "") }
                )
            )
            if (result) {
                app.preferencesManager.setHiddenSettingsApplied(true)
                overlaySafe(getString(R.string.overlay_done), "")
            } else {
                overlaySafe(getString(R.string.overlay_failed), "")
            }
            delay(2500)
            OverlayController.onCancel = null
            stopOverlay()
            disableSelf()
            stopSelf()
        }
    }

    private fun overlaySafe(status: String, detail: String) {
        val key = "$status|$detail"
        if (key == lastOverlayKey) return
        lastOverlayKey = key
        startService(
            Intent(this, OverlayService::class.java)
                .putExtra("status", status)
                .putExtra("detail", detail)
        )
    }

    private fun overlayProgress(p: Float) {
        val pct = (p * 100).toInt()
        if (pct == lastPercent) return
        lastPercent = pct
        startService(Intent(this, OverlayService::class.java).putExtra("progress", p))
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        OverlayController.onCancel = null
        scope.cancel()
        stopOverlay()
        super.onDestroy()
    }
}