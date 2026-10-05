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

    /** Флаг видимости окна (attached != visible: окно может быть скрыто/перекрыто). */
    @Volatile
    var isVisible: Boolean = false
        private set

    /**
     * Подтверждённое окно пропуска касаний: сервис получил запрос passthrough.
     * Нужно раннеру, чтобы не инжектить жест раньше, чем окно реально пропускает
     * касания (запрос идёт через `startService` — асинхронно, прогон rmumuqr53).
     */
    @Volatile
    var passthroughUntilMs: Long = 0L
        private set

    /** Окно passthrough уже действует. */
    fun isPassthroughActive(): Boolean = System.currentTimeMillis() < passthroughUntilMs

    /** Сервис подтверждает запрос passthrough (зовётся из OverlayService.setPassthrough). */
    fun markPassthrough(ms: Long) {
        passthroughUntilMs = System.currentTimeMillis() + ms.coerceIn(100L, 3000L)
    }

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
    fun isOverlaySolid(): Boolean = isAttached && isVisible

    /**
     * Разрешение «Поверх других окон» пропало во время прогона и не восстановилось:
     * окно пересоздать нельзя, шаги продолжать нельзя. Ставится сервисом/гейтом,
     * читается контроллером для честной остановки прогона (прогон rmuu7xcch: 17 шагов
     * упали `overlay_not_attached` каскадом). Снимается на старте новой фазы.
     */
    @Volatile
    var permissionLost: Boolean = false
        private set

    fun markPermissionLost() {
        if (!permissionLost) {
            AppLog.w(TAG, "permissionLost=true caller=${callerName()}")
        }
        permissionLost = true
    }

    fun clearPermissionLost() {
        if (permissionLost) AppLog.i(TAG, "permissionLost=false caller=${callerName()}")
        permissionLost = false
    }

    /**
     * Контракт восстановления окна: фаза идёт, окно потеряно, разрешение на окно есть —
     * окно обязано быть пересоздано. При отсутствии разрешения пересоздание невозможно
     * (MIUI мог снять «Поверх других окон»/фоновый pop-up), и это честный предел.
     */
    fun needsAutomationRecovery(canDrawOverlays: Boolean): Boolean =
        phaseRunning && !isAttached && canDrawOverlays

    /** Вызывается OverlayService при добавлении окна. */
    fun markAttached() {
        isAttached = true
        AppLog.i(TAG, "overlay: attached ts=${System.currentTimeMillis()}")
    }

    /** Вызывается OverlayService при удалении окна. */
    fun markDetached() {
        isAttached = false
        isVisible = false
        AppLog.i(TAG, "overlay: detached ts=${System.currentTimeMillis()}")
    }

    /** Вызывается OverlayService при каждой смене видимости (лог только на переходе). */
    fun markVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        AppLog.i(TAG, "overlay: visibility=$visible ts=${System.currentTimeMillis()}")
    }

    fun setOnCancel(listener: (() -> Unit)?) {
        onCancel = listener
    }

    /** Запас сторожевого окна вокруг инъекции жеста: ±500 мс (S4). */
    private const val GESTURE_GUARD_MARGIN_MS = 500L

    @Volatile
    private var gestureGuardFromMs = 0L

    @Volatile
    private var gestureGuardUntilMs = 0L

    /**
     * Сторожевой замок вокруг [android.accessibilityservice.AccessibilityService.dispatchGesture]:
     * ±[GESTURE_GUARD_MARGIN_MS] мс. Пока замок активен, оверлей НЕ принимает нажатия
     * своих кнопок — жест мог ещё не дойти до приложения, и случайный тап по «Отменить
     * оптимизацию» останавливал прогон целиком (прогон rmuh2vb1r: `automation cancelled
     * by user` на шаге 27/28 без действий владельца).
     */
    fun armGestureGuard(gestureMs: Long) {
        val now = System.currentTimeMillis()
        gestureGuardFromMs = now - GESTURE_GUARD_MARGIN_MS
        gestureGuardUntilMs = now + gestureMs.coerceAtLeast(50L) + GESTURE_GUARD_MARGIN_MS
        AppLog.i(
            TAG,
            "gesture guard armed: ${gestureGuardFromMs}..${gestureGuardUntilMs} " +
                "(gesture=${gestureMs}ms, margin=${GESTURE_GUARD_MARGIN_MS}ms)"
        )
    }

    /** Замок активен: нажатия кнопок оверлея игнорируются. */
    fun isGestureGuardActive(): Boolean = System.currentTimeMillis() <= gestureGuardUntilMs

    /** Только для тестов: сброс замка. */
    internal fun resetGestureGuardForTest() {
        gestureGuardFromMs = 0L
        gestureGuardUntilMs = 0L
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
        clearPermissionLost()
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

    /**
     * Форсирует пересоздание окна автоматизации, когда оно потеряно: сервис мог быть убит
     * системой и пересоздан startService'ом, а updateStatus/updateAutomation пишут в null и
     * окно НЕ возвращают. Раннер зовёт это из гейта целостности, когда окно не solid, —
     * ждать вслепую бессмысленно, окно нужно пересоздать (прогон rmuu1hsq6).
     */
    fun ensureAutomation(ctx: Context, step: Int, total: Int, title: String) {
        ctx.startService(
            intent(ctx, OverlayService.ACTION_AUTO_START)
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

    fun showResult(
        ctx: Context,
        completed: Int,
        total: Int,
        failed: Int,
        skipped: Int,
        notifSteps: List<String> = emptyList(),
        /** Шаги, где тумблер уже был выключен: ничего не меняли, но состояние проверено. */
        alreadyOffSteps: List<String> = emptyList(),
        /** Шаги, которых нет в лаунчере: «настройка отсутствует в лаунчере». */
        launcherSkipped: Int = 0,
        /** Шаги, которые робот не нашёл сам (не провал и не «нет на устройстве»). */
        unresolved: Int = 0
    ) {
        endPhase()
        AppLog.i(
            TAG,
            "showResult: $completed/$total, failed=$failed, skipped=$skipped " +
                "launcher=$launcherSkipped unresolved=$unresolved " +
                "notif=${notifSteps.size} alreadyOff=${alreadyOffSteps.size} caller=${callerName()}"
        )
        ctx.startService(
            intent(ctx, OverlayService.ACTION_RESULT)
                .putExtra(OverlayService.EXTRA_COMPLETED, completed)
                .putExtra(OverlayService.EXTRA_TOTAL, total)
                .putExtra(OverlayService.EXTRA_FAILED, failed)
                .putExtra(OverlayService.EXTRA_SKIPPED, skipped)
                .putExtra(OverlayService.EXTRA_NOTIF_STEPS, notifSteps.joinToString("\n"))
                .putExtra(OverlayService.EXTRA_ALREADY_OFF_STEPS, alreadyOffSteps.joinToString("\n"))
                .putExtra(OverlayService.EXTRA_LAUNCHER_SKIPPED, launcherSkipped)
                .putExtra(OverlayService.EXTRA_UNRESOLVED, unresolved)
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

    /**
     * Окно пропускает касания [ms] — вокруг инъекции наших жестов. Смена флага окна
     * не используется: на MIUI она схлопывает ACCESSIBILITY_OVERLAY в 0x0.
     */
    fun setPassthrough(ctx: Context, ms: Long) =
        ctx.startService(
            intent(ctx, OverlayService.ACTION_SET_PASSTHROUGH)
                .putExtra(OverlayService.EXTRA_PASSTHROUGH_MS, ms)
        )

    private fun callerName(): String {
        // Первый кадр вне OverlayController: index 0 — callerName, 1 — публичный метод.
        val trace = Throwable().stackTrace
        for (frame in trace) {
            if (frame.className != OverlayController::class.java.name) {
                return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
        }
        return "unknown"
    }

    private fun intent(ctx: Context, action: String) =
        Intent(ctx, OverlayService::class.java).setAction(action)
}
