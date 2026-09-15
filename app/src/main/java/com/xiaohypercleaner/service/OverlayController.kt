package com.xiaohypercleaner.service

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog

/**
 * Статичный API для управления оверлеем.
 *
 * Правило «один show/hide на фазу»:
 *  - STEPS: один startAutomation в начале фазы.
 *  - DONE: один showResult в конце фазы.
 *  - INACTIVE/reset: один hide при сбросе/отмене.
 *  - Промежуточные hide запрещены (illegal hide → AppLog.e + игнор).
 */
object OverlayController {

    private const val TAG = "OverlayController"

    private var onCancel: (() -> Unit)? = null
    private var onResultClose: (() -> Unit)? = null

    /** Флаг прикреплённости окна. Обновляется из OverlayService. */
    @Volatile
    var isAttached: Boolean = false
        private set

    /** Флаг защищённой фазы: hide() между startAutomation и showResult → illegal. */
    @Volatile
    var phaseRunning: Boolean = false
        private set

    /** Включить защищённую фазу — hide() отвергается. */
    fun beginPhase() {
        phaseRunning = true
        AppLog.i(TAG, "beginPhase: phaseRunning=true caller=${callerName()}")
    }

    /** Завершить защищённую фазу — hide() разрешён. */
    fun endPhase() {
        phaseRunning = false
        AppLog.i(TAG, "endPhase: phaseRunning=false caller=${callerName()}")
    }

    /** Оверлей на месте: прикреплён + видим. */
    fun isOverlaySolid(): Boolean = isAttached

    /** Вызывается OverlayService при добавлении окна. */
    fun markAttached() {
        isAttached = true
        AppLog.i(TAG, "overlay: attached ts=${System.currentTimeMillis()}")
    }

    /** Вызывается OverlayService при удалении окна. */
    fun markDetached() {
        isAttached = false
        AppLog.i(TAG, "overlay: detached ts=${System.currentTimeMillis()}")
    }

    fun setOnCancel(listener: (() -> Unit)?) {
        onCancel = listener
    }

    fun triggerCancel() {
        val listener = onCancel
        if (listener == null) {
            AppLog.w(TAG, "triggerCancel: no listener registered")
            return
        }
        listener.invoke()
    }

    fun setOnResultClose(listener: (() -> Unit)?) {
        onResultClose = listener
    }

    fun triggerResultClose() {
        onResultClose?.invoke()
    }

    fun startAutomation(ctx: Context, total: Int) {
        beginPhase()
        AppLog.i(TAG, "startAutomation: total=$total caller=${callerName()}")
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_START).putExtra(
                OverlayService.EXTRA_TOTAL,
                total
            )
        )
    }

    fun updateAutomation(ctx: Context, step: Int, total: Int, title: String) {
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_UPDATE)
                .putExtra(OverlayService.EXTRA_STEP, step)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_TITLE, title)
        )
    }

    fun updateStatus(ctx: Context, status: String) {
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_STATUS)
                .putExtra(OverlayService.EXTRA_STATUS, status)
        )
    }

    fun showResult(ctx: Context, completed: Int, total: Int, failed: Int, skipped: Int) {
        endPhase()
        AppLog.i(
            TAG,
            "showResult: $completed/$total, failed=$failed, skipped=$skipped caller=${callerName()}"
        )
        ctx.startService(
            intent(ctx, OverlayService.ACTION_RESULT)
                .putExtra(OverlayService.EXTRA_COMPLETED, completed)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_FAILED, failed)
                .putExtra(OverlayService.EXTRA_SKIPPED, skipped)
        )
    }

    fun hide(ctx: Context) {
        val caller = callerName()
        if (phaseRunning) {
            AppLog.e(TAG, "overlay: illegal hide from $caller during phase — ignored")
            return
        }
        AppLog.i(TAG, "hide caller=$caller")
        ctx.startService(intent(ctx, OverlayService.ACTION_HIDE))
    }

    fun setBlocking(ctx: Context, blocking: Boolean) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_SET_BLOCKING)
                .putExtra(OverlayService.EXTRA_BLOCKING, blocking)
        )

    private fun callerName(): String {
        val trace = Throwable().stackTrace
        val frame = trace.getOrNull(3) ?: return "unknown"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, OverlayService::class.java).setAction(action)
}
