package com.xiaohypercleaner.service

import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog

/**
 * Статичный API для управления оверлеем из любого места.
 *
 * Listeners хранятся сильно — MainViewModel снимает их в onCleared().
 * WeakReference здесь ломал Cancel: GC собирал лямбду, simpleModeActive
 * оставался true, кнопка «Оптимизировать» игнорировалась.
 *
 * Состояние:
 *  - [isAttached] — актуальный флаг прикреплённости окна (обновляется из
 *    [OverlayService] через [markAttached]/[markDetached]).
 *  - Гейт раннера: перед шагом [SimpleRunner] ждёт [isAttached] до 2 с;
 *    иначе пауза с сообщением «recovering».
 *
 * Правило «один show/hide на фазу»:
 *  - STEPS: один [startAutomation] в начале фазы.
 *  - DONE: один [showResult] в конце фазы.
 *  - INACTIVE/reset: один [hide] при сбросе/отмене.
 *  - Промежуточные [hide] запрещены (убраны в коммите overlay continuity).
 */
object OverlayController {

    private const val TAG = "OverlayController"

    private var onCancel: (() -> Unit)? = null
    private var onResultClose: (() -> Unit)? = null

    /** Актуальный флаг прикреплённости окна оверлея. */
    @Volatile
    var isAttached: Boolean = false
        private set

    /**
     * Вызывается из [OverlayService] при прикреплении окна.
     * Не для внешнего использования.
     */
    fun markAttached() {
        isAttached = true
        AppLog.d(TAG, "markAttached")
    }

    /**
     * Вызывается из [OverlayService] при откреплении окна.
     * Не для внешнего использования.
     */
    fun markDetached() {
        isAttached = false
        AppLog.d(TAG, "markDetached")
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

    // ═══ Автоматизация (блокирующий оверлей с робокотом) ═══

    fun startAutomation(ctx: Context, total: Int) {
        AppLog.i(TAG, "startAutomation: total=$total caller=${callerName()}")
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
        AppLog.i(TAG, "hide caller=${callerName()}")
        ctx.startService(intent(ctx, OverlayService.ACTION_HIDE))
    }

    /** Временно разрешить/запретить касания сквозь оверлей (для жестов робота) */
    fun setBlocking(ctx: Context, blocking: Boolean) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_SET_BLOCKING)
                .putExtra(OverlayService.EXTRA_BLOCKING, blocking)
        )

    private fun callerName(): String {
        val trace = Throwable().stackTrace
        // 0=Throwable, 1=callerName, 2=hide/startAutomation/showResult, 3=реальный caller
        val frame = trace.getOrNull(3) ?: return "unknown"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, OverlayService::class.java).setAction(action)
}