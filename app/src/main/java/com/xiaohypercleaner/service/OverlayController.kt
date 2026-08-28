package com.xiaohypercleaner.service

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog
import java.lang.ref.WeakReference

/**
 * Статичный API для управления оверлеем из любого места.
 * onCancel / onResultClose — через WeakReference (защита от утечек).
 */
object OverlayController {

    private const val TAG = "OverlayController"

    private var onCancel: WeakReference<() -> Unit>? = null
    private var onResultClose: WeakReference<() -> Unit>? = null

    fun setOnCancel(listener: () -> Unit) {
        onCancel = WeakReference(listener)
    }

    fun triggerCancel() {
        onCancel?.get()?.invoke()
    }

    fun setOnResultClose(listener: () -> Unit) {
        onResultClose = WeakReference(listener)
    }

    fun triggerResultClose() {
        onResultClose?.get()?.invoke()
    }

    // ═══ Подсказки / стрелки (не блокируют) ═══

    fun hint(ctx: Context, text: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_HINT).putExtra(OverlayService.EXTRA_HINT, text)
        )

    fun showManualPointer(ctx: Context, mode: String, text: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_POINTER)
                .putExtra(OverlayService.EXTRA_POINTER_MODE, mode)
                .putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
        )

    // ═══ Автоматизация (блокирующий оверлей с робокотом) ═══

    fun startAutomation(ctx: Context, total: Int) {
        AppLog.i(TAG, "startAutomation: total=$total")
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_START).putExtra(
                OverlayService.EXTRA_TOTAL,
                total
            )
        )
    }

    fun updateAutomation(ctx: Context, step: Int, total: Int, title: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_UPDATE)
                .putExtra(OverlayService.EXTRA_STEP, step)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_TITLE, title)
        )

    fun updateStatus(ctx: Context, status: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_STATUS)
                .putExtra(OverlayService.EXTRA_STATUS, status)
        )

    fun showResult(ctx: Context, completed: Int, total: Int, failed: Int, skipped: Int) {
        AppLog.i(TAG, "showResult: $completed/$total, failed=$failed, skipped=$skipped")
        ctx.startService(
            intent(ctx, OverlayService.ACTION_RESULT)
                .putExtra(OverlayService.EXTRA_COMPLETED, completed)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_FAILED, failed)
                .putExtra(OverlayService.EXTRA_SKIPPED, skipped)
        )
    }

    fun hide(ctx: Context) =
        ctx.startService(intent(ctx, OverlayService.ACTION_HIDE))

    /** Временно разрешить/запретить касания сквозь оверлей (для жестов робота) */
    fun setBlocking(ctx: Context, blocking: Boolean) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_SET_BLOCKING)
                .putExtra(OverlayService.EXTRA_BLOCKING, blocking)
        )

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, OverlayService::class.java).setAction(action)
}