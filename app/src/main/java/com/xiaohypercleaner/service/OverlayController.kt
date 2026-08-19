package com.xiaohypercleaner.service

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog

object OverlayController {
    private const val TAG = "OverlayCtrl"
    var onCancel: (() -> Unit)? = null

    fun triggerCancel() {
        onCancel?.invoke()
        onCancel = null
    }

    fun showHint(context: Context, text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_HINT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showHint failed: ${e.message}")
        }
    }

    fun showPointer(context: Context, mode: OverlayService.PointerMode, text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_POINTER_MODE, mode.name)
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showPointer failed: ${e.message}")
        }
    }

    fun hide(context: Context) {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (e: Exception) {
            AppLog.w(TAG, "hide failed: ${e.message}")
        }
    }
}