package com.xiaohypercleaner.util

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.service.AdbEnablerService

/**
 * Структурированная диагностика шагов Simple Mode.
 *
 * Одна строка на событие в формате, удобном для grep/поддержки:
 * `DIAG step=msa event=FAIL reason=no_root_window pkg=… windows=[…] screen=[…]`
 */
object StepDiagnostics {
    private const val TAG = "StepDiag"
    private const val SCREEN_CAP = 280
    private const val WINDOWS_CAP = 12

    @Volatile
    private var runId: String = "none"

    fun beginRun(): String {
        runId = "r${System.currentTimeMillis().toString(36)}"
        AppLog.i(TAG, "DIAG run=$runId event=BEGIN sdk=${Build.VERSION.SDK_INT}")
        return runId
    }

    fun endRun(completed: Int, failed: Int, skipped: Int) {
        AppLog.i(
            TAG,
            "DIAG run=$runId event=END completed=$completed failed=$failed skipped=$skipped"
        )
    }

    fun stepStart(
        stepId: String,
        index: Int,
        total: Int,
        resolvedPkg: String?,
        profile: RomProfile
    ) {
        AppLog.i(
            TAG,
            "DIAG run=$runId step=$stepId event=START idx=${index + 1}/$total " +
                "pkg=${resolvedPkg ?: "-"} region=${profile.region} " +
                "miui=${profile.miuiVersion} hyper=${profile.hyperOsHint} " +
                "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}"
        )
    }

    fun stepResult(
        stepId: String,
        success: Boolean,
        reason: String,
        elapsedMs: Long,
        root: AccessibilityNodeInfo?,
        service: AdbEnablerService?
    ) {
        val event = if (success) "OK" else "FAIL"
        val pkg = root?.packageName?.toString() ?: "-"
        val screen = root?.let { collectText(it).take(SCREEN_CAP) } ?: ""
        val windows = service?.let { dumpWindows(it) } ?: "[]"
        AppLog.i(
            TAG,
            "DIAG run=$runId step=$stepId event=$event reason=$reason " +
                "elapsed=${elapsedMs}ms pkg=$pkg windows=$windows screen=[$screen]"
        )
    }

    fun note(stepId: String, event: String, detail: String) {
        AppLog.i(TAG, "DIAG run=$runId step=$stepId event=$event $detail")
    }

    private fun dumpWindows(service: AdbEnablerService): String {
        return try {
            service.windows
                .asSequence()
                .take(WINDOWS_CAP)
                .mapNotNull { w ->
                    val r = w.root ?: return@mapNotNull null
                    val pkg = r.packageName?.toString() ?: "?"
                    val type = when (w.type) {
                        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                        AccessibilityWindowInfo.TYPE_SYSTEM -> "sys"
                        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
                        else -> "t${w.type}"
                    }
                    val focus = if (w.isFocused) "*" else ""
                    val len = collectText(r).length
                    "$pkg/$type$focus($len)"
                }
                .joinToString(prefix = "[", postfix = "]", separator = ",")
        } catch (e: Exception) {
            "[err:${e.message}]"
        }
    }

    private fun collectText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 20 || sb.length > SCREEN_CAP) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }
}
