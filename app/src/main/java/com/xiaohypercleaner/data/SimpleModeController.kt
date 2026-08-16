package com.xiaohypercleaner.data

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.ui.SimpleModePhase
import com.xiaohypercleaner.data.SimpleStepState
import com.xiaohypercleaner.util.AppLog

/**
 * Контроллер простого режима оптимизации (пошаговый мастер).
 * Вынесен из MainViewModel для уменьшения размера god-object.
 *
 * Отвечает за:
 * - Проверку разрешений (restricted, accessibility, overlay)
 * - Управление шагами (переходы, завершение, пропуск)
 * - Открытие соответствующих системных настроек
 *
 * @param context Контекст приложения
 * @param permissionFlow Менеджер потока разрешений
 * @param onStateChanged Колбэк для обновления UI-состояния
 */
class SimpleModeController(
    private val context: android.content.Context,
    private val permissionFlow: PermissionFlowManager,
    private val onStateChanged: (SimpleModeState) -> Unit
) {
    companion object {
        private const val TAG = "SimpleModeController"
    }

    data class SimpleModeState(
        val active: Boolean = false,
        val         phase: SimpleModePhase =
            SimpleModePhase.INACTIVE,
        val currentStepIndex: Int = 0,
        val completedCount: Int = 0,
        val step: SimpleStepState? = null,
        val done: Pair<Int, Int>? = null,
        val showAccessibilityDialog: Boolean = false,
        val showOverlayDialog: Boolean = false,
        val showRestrictedDialog: Boolean = false,
        val restrictedSettingsShown: Boolean = false
    )

    private var state = SimpleModeState()
    private var isAccessibilityEnabled = false
    private var isOverlayGranted = false
    private var lastRedirect = Redirect.NONE
    private var restrictedFlowStarted = false

    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    fun setState(update: SimpleModeState.() -> SimpleModeState) {
        state = state.update()
        onStateChanged(state)
    }

    /** Обновить статусы разрешений (вызывается из ViewModel при onResume) */
    fun updatePermissionStatuses(accEnabled: Boolean, overlayGranted: Boolean) {
        val accJustEnabled = !isAccessibilityEnabled && accEnabled
        val overlayJustEnabled = !isOverlayGranted && overlayGranted

        isAccessibilityEnabled = accEnabled
        isOverlayGranted = overlayGranted

        if (!state.active) return

        // Простой режим — продвигаемся по шагам
        if (accJustEnabled || overlayJustEnabled) {
            AppLog.i(
                TAG,
                "Permission granted (acc=$accJustEnabled, overlay=$overlayJustEnabled) — advancing"
            )
            advance()
        }
    }

    /** Запустить простой режим */
    fun start() {
        AppLog.i(TAG, "Starting simple mode")
        state = SimpleModeState(
            active = true,
            phase = SimpleModePhase.PERMISSIONS
        )
        onStateChanged(state)
        advance()
    }

    /** Пользователь согласился с диалогом */
    fun onDialogAgreed() {
        AppLog.i(TAG, "Dialog agreed")

        if (state.showAccessibilityDialog) {
            setState {
                copy(showAccessibilityDialog = false, showOverlayDialog = false, showRestrictedDialog = false)
            }
            ChainFlags.waitingAccessibilityReturn = true
            lastRedirect = Redirect.ACCESSIBILITY
            permissionFlow.openAccessibilitySettings()
            return
        }

        if (state.showOverlayDialog) {
            setState {
                copy(showAccessibilityDialog = false, showOverlayDialog = false, showRestrictedDialog = false)
            }
            permissionFlow.openOverlaySettings()
            return
        }
    }

    /** Пользователь отменил диалог */
    fun onDialogCancelled() {
        AppLog.i(TAG, "Dialog cancelled — resetting simple mode")
        reset()
    }

    /** Пользователь согласился с предупреждением о restricted settings */
    fun onRestrictedDialogAgreed() {
        AppLog.i(TAG, "Restricted dialog agreed — opening app info")
        setState {
            copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }
        lastRedirect = Redirect.APP_INFO
        openAppInfoWithHint()
    }

    /** Пользователь отменил предупреждение о restricted settings */
    fun onRestrictedDialogCancelled() {
        AppLog.i(TAG, "Restricted dialog cancelled — resetting simple mode")
        reset()
    }

    /** Начать текущий шаг (запускает AdbEnablerService) */
    fun startCurrentStep() {
        val stepIndex = state.currentStepIndex
        AppLog.i(TAG, "Starting simple step: $stepIndex")

        setState {
            copy(step = step?.copy(status = SimpleStepState.Status.WORKING))
        }

        val intent = Intent(context, AdbEnablerService::class.java).apply {
            action = AdbEnablerService.ACTION_SIMPLE_STEP
            putExtra("step_index", stepIndex)
        }
        context.startService(intent)
    }

    /** Обработать результат текущего шага */
    fun onStepResult(success: Boolean) {
        AppLog.i(TAG, "Step result: $success")

        if (success) {
            val newCompleted = state.completedCount + 1
            setState {
                copy(
                    completedCount = newCompleted,
                    step = step?.copy(status = SimpleStepState.Status.SUCCESS)
                )
            }
        } else {
            setState {
                copy(step = step?.copy(status = SimpleStepState.Status.FAILED))
            }
        }
    }

    /** Перейти к следующему шагу */
    fun nextStep() {
        val nextIndex = state.currentStepIndex + 1
        AppLog.i(TAG, "Next step: $nextIndex")

        if (nextIndex >= SimpleSteps.ALL.size) {
            setState {
                copy(
                    step = null,
                    done = Pair(completedCount, SimpleSteps.ALL.size),
                    phase = SimpleModePhase.DONE
                )
            }
        } else {
            setState {
                copy(
                    currentStepIndex = nextIndex,
                    step = SimpleStepState(
                        stepIndex = nextIndex,
                        totalSteps = SimpleSteps.ALL.size,
                        step = SimpleSteps.ALL[nextIndex],
                        status = SimpleStepState.Status.READY,
                        completedCount = completedCount
                    ),
                    done = null
                )
            }
        }
    }

    /** Пропустить текущий шаг */
    fun skipStep() {
        AppLog.i(TAG, "Skipping step ${state.currentStepIndex}")
        nextStep()
    }

    /** Закрыть простой режим */
    fun close() {
        AppLog.i(TAG, "Closing simple mode")
        reset()
    }

    /** Сбросить всё состояние */
    private fun reset() {
        state = SimpleModeState()
        restrictedFlowStarted = false
        lastRedirect = Redirect.NONE
        onStateChanged(state)
    }

    // ═══════════════════════════════════════════════════════════════
    // ВНУТРЕННЯЯ ЛОГИКА ПРОДВИЖЕНИЯ ПО ШАГАМ
    // ═══════════════════════════════════════════════════════════════

    /** Проверяет разрешения и продвигается по шагам */
    private fun advance() {
        if (!state.active) return

        AppLog.i(
            TAG,
            "advance: restricted=${state.restrictedSettingsShown}, acc=$isAccessibilityEnabled, overlay=$isOverlayGranted"
        )

        // Фаза 1: Проверка restricted/forbidden settings (для Android 13+ sideload)
        val isAndroid13Plus = Build.VERSION.SDK_INT >= 33
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
        val isFromKnownStore = installer in listOf(
            "com.android.vending",
            "com.xiaomi.market",
            "ru.vk.store"
        )

        if (isAndroid13Plus && !isFromKnownStore && !state.restrictedSettingsShown) {
            AppLog.i(TAG, "Showing restricted dialog")
            setState { copy(showRestrictedDialog = true) }
            return
        }

        // Фаза 2: Проверка Accessibility
        if (!isAccessibilityEnabled) {
            AppLog.i(TAG, "Showing accessibility dialog")
            setState { copy(showAccessibilityDialog = true) }
            return
        }

        // Фаза 3: Проверка Overlay
        if (!isOverlayGranted) {
            AppLog.i(TAG, "Showing overlay dialog")
            setState { copy(showOverlayDialog = true) }
            return
        }

        // Все разрешения получены — переходим к шагам
        AppLog.i(TAG, "All permissions granted — starting steps")
        val totalSteps = SimpleSteps.ALL.size
        setState {
            copy(
                active = false,
                phase = SimpleModePhase.STEPS,
                step = if (totalSteps > 0) {
                    SimpleStepState(
                        stepIndex = 0,
                        totalSteps = totalSteps,
                        step = SimpleSteps.ALL[0],
                        status = SimpleStepState.Status.READY,
                        completedCount = 0
                    )
                } else null
            )
        }
    }

    private fun openAppInfoWithHint() {
        AppLog.i(TAG, "Opening app info with hint")
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            showHint(context.getString(R.string.hint_restricted))
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to open app info: ${e.message}")
            setState { copy(showRestrictedDialog = true) }
        }
    }

    private fun showHint(text: String) {
        try {
            val intent = Intent(context, com.xiaohypercleaner.service.OverlayService::class.java)
            intent.putExtra("hint", text)
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to show hint: ${e.message}")
        }
    }
}
