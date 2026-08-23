package com.xiaohypercleaner.service

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * Статичный API для управления оверлеем из любого места.
 *
 * - onCancel / onResultClose — через WeakReference (защита от утечек Activity/VM)
 * - showManualPointer — пульсирующая стрелка для РУЧНЫХ фаз
 *   (RESTRICTED / OVERLAY / ACCESSIBILITY): оверлей ПРОПУСКАЕТ касания,
 *   пользователь сам нажимает на подсвеченный элемент
 * - startAutomation / updateAutomation / updateStatus / showResult —
 *   режим автоматизации с робокотом (оверлей ПОГЛОЩАЕТ касания)
 */
object OverlayController {

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

    // ═══════════════════════════════════════════════════════════════
    // Подсказки / стрелки (пропускают касания — пользователь кликает сам)
    // ═══════════════════════════════════════════════════════════════

    /** Карточка-подсказка внизу экрана */
    fun hint(ctx: Context, text: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_HINT)
                .putExtra(OverlayService.EXTRA_HINT, text)
        )

    /**
     * НОВОЕ: пульсирующая стрелка + пузырь-подпись для ручных фаз.
     * mode — имя OverlayService.PointerMode (TOP_RIGHT, BOTTOM_LIST,
     * LIST_ITEM_CENTER, GENERIC_BOTTOM...)
     */
    fun showManualPointer(ctx: Context, mode: String, text: String) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_POINTER)
                .putExtra(OverlayService.EXTRA_POINTER_MODE, mode)
                .putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
        )

    /** Алиас для совместимости со старыми вызовами */
    fun pointer(ctx: Context, mode: String, text: String) =
        showManualPointer(ctx, mode, text)

    // ═══════════════════════════════════════════════════════════════
    // Автоматизация (робокот «умывается», оверлей поглощает касания)
    // ═══════════════════════════════════════════════════════════════

    fun startAutomation(ctx: Context, total: Int) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_START)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
        )

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

    // ═══════════════════════════════════════════════════════════════
    // Финал (довольный кот + мягкая просьба)
    // ═══════════════════════════════════════════════════════════════

    fun showResult(ctx: Context, completed: Int, total: Int, failed: Int, skipped: Int) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_RESULT)
                .putExtra(OverlayService.EXTRA_COMPLETED, completed)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_FAILED, failed)
                .putExtra(OverlayService.EXTRA_SKIPPED, skipped)
        )

    fun hide(ctx: Context) =
        ctx.startService(intent(ctx, OverlayService.ACTION_HIDE))

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, OverlayService::class.java).setAction(action)
}