package com.xiaohypercleaner.data

import android.content.Context
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.ShizukuHelper

/**
 * Управляет Shizuku wizard-диалогами и связанными источниками установки.
 * Вынесен из MainViewModel для уменьшения размера god-object.
 *
 * Логика:
 * - Установка / открытие Shizuku из разных источников
 * - Проверка статуса Shizuku
 * - Управление состоянием wizard-диалогов
 *
 * @param context Контекст приложения
 * @param onStateChanged Колбэк для обновления UI-состояния
 */
class ShizukuWizardManager(
    private val context: Context,
    private val onStateChanged: (ShizukuWizardState) -> Unit
) {
    companion object {
        private const val TAG = "ShizukuWizardManager"
    }

    data class ShizukuWizardState(
        val showShizukuDialog: Boolean = false,
        val showShizukuWizard: Boolean = false,
        val showShizukuSources: Boolean = false,
        val shizukuStatus: ShizukuExecutor.Status = ShizukuExecutor.Status.NOT_INSTALLED,
        val shizukuCheckMessage: String? = null
    )

    private var state = ShizukuWizardState()

    /** Устанавливает состояние и уведомляет ViewModel */
    fun setState(update: ShizukuWizardState.() -> ShizukuWizardState) {
        state = state.update()
        onStateChanged(state)
    }

    /**
     * Показать диалог Shizuku с указанным статусом.
     * Вызывается из ViewModel при старте продвинутого потока.
     */
    fun showDialog(status: ShizukuExecutor.Status) {
        AppLog.i(TAG, "showDialog called with status: $status")
        setState {
            copy(
                showShizukuDialog = true,
                shizukuStatus = status
            )
        }
    }

    /** Пользователь нажал "Установить" в диалоге Shizuku */
    fun onInstallClicked() {
        AppLog.i(TAG, "Install Shizuku clicked")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuSources = false
            )
        }
        ShizukuHelper.openShizukuInStore(context)
    }

    /** Пользователь нажал "Открыть Shizuku" */
    fun onOpenAppClicked() {
        AppLog.i(TAG, "Open Shizuku app clicked")
        setState { copy(showShizukuDialog = false) }
        openWizard()
    }

    /** Открыть wizard с инструкцией по настройке */
    fun openWizard() {
        AppLog.i(TAG, "Opening Shizuku wizard")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuWizard = true,
                shizukuCheckMessage = null
            )
        }
    }

    /** Закрыть wizard */
    fun closeWizard() {
        AppLog.i(TAG, "Closing Shizuku wizard")
        setState { copy(showShizukuWizard = false) }
    }

    /** Пользователь нажал "Пропустить" в wizard */
    fun onWizardSkip() {
        AppLog.i(TAG, "Wizard skipped — proceeding to options")
        setState {
            copy(
                showShizukuWizard = false,
                shizukuCheckMessage = null
            )
        }
    }

    /** Запросить разрешение Shizuku */
    fun requestPermission(requestCode: Int) {
        AppLog.i(TAG, "Requesting Shizuku permission")
        ShizukuExecutor.requestPermission(requestCode)
    }

    /** Обработать результат запроса разрешения */
    fun onPermissionResult(granted: Boolean) {
        AppLog.i(TAG, "Shizuku permission result: $granted")
        if (granted) {
            setState {
                copy(
                    showShizukuWizard = false,
                    shizukuCheckMessage = null
                )
            }
        } else {
            setState {
                copy(
                    shizukuCheckMessage = context.getString(R.string.shizuku_wizard_permission_denied)
                )
            }
        }
    }

    /** Проверить статус Shizuku из wizard */
    fun checkStatus() {
        val status = ShizukuExecutor.checkStatus(context)
        AppLog.i(TAG, "Wizard check status: $status")

        when (status) {
            ShizukuExecutor.Status.AVAILABLE -> {
                AppLog.i(TAG, "Shizuku available — proceeding")
                setState {
                    copy(
                        showShizukuWizard = false,
                        shizukuCheckMessage = null
                    )
                }
            }

            ShizukuExecutor.Status.PERMISSION_REQUIRED -> {
                setState {
                    copy(shizukuCheckMessage = context.getString(R.string.shizuku_wizard_step6))
                }
            }

            else -> {
                setState {
                    copy(shizukuCheckMessage = context.getString(R.string.shizuku_wizard_not_ready))
                }
            }
        }
    }

    /** Пользователь открыл диалог выбора источников */
    fun onOpenSources() {
        AppLog.i(TAG, "Opening Shizuku sources")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuSources = true
            )
        }
    }

    /** Закрыть диалог выбора источников */
    fun closeSources() {
        AppLog.i(TAG, "Closing Shizuku sources")
        setState { copy(showShizukuSources = false) }
    }

    /** Установка Shizuku из выбранного источника */
    fun installFromSource(source: String) {
        AppLog.i(TAG, "Installing Shizuku from: $source")
        setState { copy(showShizukuSources = false) }

        when (source) {
            "play" -> ShizukuHelper.openPlay(context)
            "aurora" -> ShizukuHelper.openAurora(context)
            "getapps" -> ShizukuHelper.openGetApps(context)
            "github" -> ShizukuHelper.openGithub(context)
            "apkpure" -> ShizukuHelper.openApkPure(context)
        }
    }

    /** Пользователь нажал "Позже" */
    fun onLater() {
        AppLog.i(TAG, "Shizuku setup postponed")
        setState { copy(showShizukuDialog = false) }
    }

    /** Сбросить состояние при отмене */
    fun reset() {
        state = ShizukuWizardState()
        onStateChanged(state)
    }
}