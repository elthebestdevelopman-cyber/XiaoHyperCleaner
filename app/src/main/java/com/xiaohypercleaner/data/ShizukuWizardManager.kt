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
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для всех переменных
 * 2. Полная документация для ShizukuWizardState
 * 3. Защита от повторных вызовов
 * 4. Convenience метод isWizardOpen()
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

    /**
     * Состояние wizard-диалогов Shizuku.
     * Используется в ViewModel для управления UI.
     */
    data class ShizukuWizardState(
        /** Показывать диалог с предложением установить Shizuku */
        val showShizukuDialog: Boolean = false,

        /** Показывать wizard с инструкцией по настройке Shizuku */
        val showShizukuWizard: Boolean = false,

        /** Показывать диалог выбора источников установки */
        val showShizukuSources: Boolean = false,

        /** Текущий статус Shizuku (NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED, AVAILABLE) */
        val shizukuStatus: ShizukuExecutor.Status = ShizukuExecutor.Status.NOT_INSTALLED,

        /** Сообщение для отображения в wizard (например, "Shizuku не готов") */
        val shizukuCheckMessage: String? = null
    )

    private var state: ShizukuWizardState = ShizukuWizardState()

    /**
     * Устанавливает состояние и уведомляет ViewModel.
     * Использует DSL-паттерн для удобного обновления.
     *
     * Пример:
     * ```
     * setState { copy(showShizukuDialog = true) }
     * ```
     */
    fun setState(update: ShizukuWizardState.() -> ShizukuWizardState) {
        state = state.update()
        onStateChanged(state)
    }

    /**
     * Показать диалог Shizuku с указанным статусом.
     * Вызывается из ViewModel при старте продвинутого потока.
     *
     * @param status Текущий статус Shizuku
     */
    fun showDialog(status: ShizukuExecutor.Status) {
        if (state.showShizukuDialog) {
            AppLog.w(TAG, "showDialog: диалог уже открыт, пропускаем")
            return
        }

        AppLog.i(TAG, "showDialog: status=$status")
        setState {
            copy(
                showShizukuDialog = true,
                shizukuStatus = status
            )
        }
    }

    /**
     * Пользователь нажал "Установить" в диалоге Shizuku.
     * Открывает магазин с Shizuku (автовыбор лучшего источника).
     */
    fun onInstallClicked() {
        AppLog.i(TAG, "onInstallClicked: установка Shizuku")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuSources = false
            )
        }
        ShizukuHelper.openShizukuInStore(context)
    }

    /**
     * Пользователь нажал "Открыть Shizuku".
     * Переходит к wizard с инструкцией по настройке.
     */
    fun onOpenAppClicked() {
        AppLog.i(TAG, "onOpenAppClicked: открытие Shizuku")
        setState { copy(showShizukuDialog = false) }
        openWizard()
    }

    /**
     * Открыть wizard с инструкцией по настройке.
     * Показывает пошаговую инструкцию для пользователя.
     */
    fun openWizard() {
        if (state.showShizukuWizard) {
            AppLog.w(TAG, "openWizard: wizard уже открыт, пропускаем")
            return
        }

        AppLog.i(TAG, "openWizard: открытие wizard")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuWizard = true,
                shizukuCheckMessage = null
            )
        }
    }

    /**
     * Закрыть wizard.
     */
    fun closeWizard() {
        AppLog.i(TAG, "closeWizard: закрытие wizard")
        setState { copy(showShizukuWizard = false) }
    }

    /**
     * Пользователь нажал "Пропустить" в wizard.
     * Переходит к опциям Pro-режима без настройки Shizuku.
     */
    fun onWizardSkip() {
        AppLog.i(TAG, "onWizardSkip: пропуск wizard")
        setState {
            copy(
                showShizukuWizard = false,
                shizukuCheckMessage = null
            )
        }
    }

    /**
     * Запросить разрешение Shizuku.
     *
     * @param requestCode Код запроса для обработки в onRequestPermissionResult
     */
    fun requestPermission(requestCode: Int) {
        AppLog.i(TAG, "requestPermission: requestCode=$requestCode")
        ShizukuExecutor.requestPermission(requestCode)
    }

    /**
     * Обработать результат запроса разрешения.
     *
     * @param granted true, если разрешение предоставлено
     */
    fun onPermissionResult(granted: Boolean) {
        AppLog.i(TAG, "onPermissionResult: granted=$granted")
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

    /**
     * Проверить статус Shizuku из wizard.
     * Обновляет shizukuCheckMessage в зависимости от статуса.
     */
    fun checkStatus() {
        val status: ShizukuExecutor.Status = ShizukuExecutor.checkStatus(context)
        AppLog.i(TAG, "checkStatus: status=$status")

        when (status) {
            ShizukuExecutor.Status.AVAILABLE -> {
                AppLog.i(TAG, "checkStatus: Shizuku доступен — продолжаем")
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

    /**
     * Пользователь открыл диалог выбора источников.
     * Показывает список доступных магазинов для установки Shizuku.
     */
    fun onOpenSources() {
        AppLog.i(TAG, "onOpenSources: открытие диалога источников")
        setState {
            copy(
                showShizukuDialog = false,
                showShizukuSources = true
            )
        }
    }

    /**
     * Закрыть диалог выбора источников.
     */
    fun closeSources() {
        AppLog.i(TAG, "closeSources: закрытие диалога источников")
        setState { copy(showShizukuSources = false) }
    }

    /**
     * Установка Shizuku из выбранного источника.
     *
     * @param source Идентификатор источника: "play", "aurora", "getapps", "github", "apkpure"
     */
    fun installFromSource(source: String) {
        AppLog.i(TAG, "installFromSource: source=$source")
        setState { copy(showShizukuSources = false) }

        when (source) {
            "play" -> ShizukuHelper.openPlay(context)
            "aurora" -> ShizukuHelper.openAurora(context)
            "getapps" -> ShizukuHelper.openGetApps(context)
            "github" -> ShizukuHelper.openGithub(context)
            "apkpure" -> ShizukuHelper.openApkPure(context)
            else -> AppLog.w(TAG, "installFromSource: неизвестный источник '$source'")
        }
    }

    /**
     * Пользователь нажал "Позже".
     * Закрывает диалог без установки Shizuku.
     */
    fun onLater() {
        AppLog.i(TAG, "onLater: отложено")
        setState { copy(showShizukuDialog = false) }
    }

    /**
     * Сбросить состояние при отмене.
     * Возвращает все диалоги в начальное состояние.
     */
    fun reset() {
        AppLog.i(TAG, "reset: сброс состояния")
        state = ShizukuWizardState()
        onStateChanged(state)
    }

    // ═══════════════════════════════════════════════════════════════
    // Convenience методы
    // ═══════════════════════════════════════════════════════════════

    /**
     * Проверяет, открыт ли какой-либо wizard-диалог.
     * Используется для блокировки других действий во время настройки Shizuku.
     */
    fun isWizardOpen(): Boolean {
        return state.showShizukuDialog || state.showShizukuWizard || state.showShizukuSources
    }

    /**
     * Возвращает текущее состояние (для диагностики).
     */
    fun getCurrentState(): ShizukuWizardState = state
}