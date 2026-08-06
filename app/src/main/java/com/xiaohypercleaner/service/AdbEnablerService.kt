package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.AdbClient
import com.xiaohypercleaner.data.OptimizationEngine
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
    private val engine = OptimizationEngine(AdbClient())

    private var overlayHandled = false
    private var optimizationStarted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CHAIN) {
            scope.launch {
                delay(400)
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
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("settings", true)) return
        val root = rootInActiveWindow ?: return

        if (clickAllow(root)) return

        if (!overlayHandled && !Settings.canDrawOverlays(this)) {
            val sw = findOurOverlaySwitch(root)
            if (sw != null) {
                overlayHandled = true
                if (sw.isCheckable && !sw.isChecked) {
                    sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                scope.launch {
                    delay(1000)
                    clickAllow(rootInActiveWindow)
                    delay(500)
                    openDevSettings()
                }
                return
            }
        }

        if (!optimizationStarted) {
            val toggle = findCheckable(root, "Беспроводная отладка", "Wireless debugging")
            if (toggle != null) {
                if (toggle.isCheckable && !toggle.isChecked) {
                    toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                scope.launch {
                    delay(1500)
                    clickAllow(rootInActiveWindow)
                    delay(1000)
                    startOptimization()
                }
            }
        }
    }

    private fun clickAllow(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        for (text in arrayOf("Разрешить", "Allow")) {
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
            delay(8000)
            startOptimization()
        }
    }

    private fun startOptimization() {
        if (optimizationStarted) return
        optimizationStarted = true
        scope.launch {
            val result = engine.optimize { stage ->
                val text = when (stage) {
                    "connecting" -> getString(R.string.overlay_connecting)
                    "method1" -> getString(R.string.overlay_method1)
                    "method2" -> getString(R.string.overlay_method2)
                    "method3" -> getString(R.string.overlay_method3)
                    "verifying" -> getString(R.string.overlay_verifying)
                    else -> ""
                }
                if (text.isNotEmpty()) overlay(text)
            }

            val app = application as XiaoHyperApp
            if (result) {
                app.preferencesManager.setHiddenSettingsApplied(true)
                overlay(getString(R.string.overlay_done))
            } else {
                overlay(getString(R.string.overlay_failed))
            }
            delay(2500)
            stopOverlay()
            disableSelf()
        }
    }

    private fun overlay(text: String) {
        startService(Intent(this, OverlayService::class.java).putExtra("status", text))
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        stopOverlay()
        super.onDestroy()
    }
}