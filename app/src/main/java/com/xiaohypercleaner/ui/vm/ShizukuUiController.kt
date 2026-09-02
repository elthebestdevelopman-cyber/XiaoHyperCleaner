package com.xiaohypercleaner.ui.vm

import android.app.Application
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.ShizukuWizardManager
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog

/**
 * Делегат всего, что касается Shizuku / продвинутого режима до запуска цепочки:
 * диалоги установки, мастер, источники, запрос разрешения.
 *
 * Снимает с MainViewModel ~80 строк обёрток.
 *
 * Архитектура:
 * 1. Оборачивает `ShizukuWizardManager` для управления состоянием wizard-диалогов
 * 2. Синхронизирует состояние `ShizukuWizardManager` с `MainUiState` через callback
 * 3. Управляет флагом `pendingSourceSuggestion` для проверки источников после возврата
 *
 * Теги логов оставлены "MainVM", чтобы logcat-фильтры продолжали работать.
 *
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для всех переменных и методов
 * 2. Полный JavaDoc для класса, конструктора, полей и методов
 * 3. Логирование для ключевых действий
 * 4. `pendingSourceSuggestion` сделан `private var` для ясности
 *
 * @param app Application контекст
 * @param update Функция обновления MainUiState (DSL-паттерн)
 */
class ShizukuUiController(
    private val app: Application,
    private val update: ((MainUiState) -> MainUiState) -> Unit
) {
    companion object {
        /** TAG для логирования (оставлен "MainVM" для совместимости с logcat-фильтрами) */
        private const val TAG = "MainVM"
    }

    /**
     * Менеджер wizard-диалогов Shizuku.
     *
     * Управляет состоянием диалогов установки/настройки Shizuku.
     * При каждом изменении состояния вызывает callback для синхронизации с MainUiState.
     */
    private val manager: ShizukuWizardManager = ShizukuWizardManager(app) { s ->
        update {
            it.copy(
                showShizukuDialog = s.showShizukuDialog,
                showShizukuWizard = s.showShizukuWizard,
                showShizukuSources = s.showShizukuSources,
                shizukuStatus = s.shizukuStatus,
                shizukuCheckMessage = s.shizukuCheckMessage
            )
        }
    }

    /**
     * Флаг «после возврата из магазина посмотреть, появился ли Shizuku».
     *
     * Устанавливается в true при:
     * - `dialogInstall()` — пользователь нажал "Установить"
     * - `installFromSource()` — пользователь выбрал источник установки
     *
     * Проверяется в `consumePendingSourceSuggestion()` (вызывается из refreshStatuses):
     * - Если Shizuku всё ещё NOT_INSTALLED → показываем диалог выбора источников
     * - Иначе → ничего не делаем (Shizuku установлен или запущен)
     */
    private var pendingSourceSuggestion: Boolean = false

    /**
     * Вызывается из refreshStatuses: один раз проверяем источники после возврата из магазина.
     *
     * Логика:
     * 1. Если `pendingSourceSuggestion == false` → ничего не делаем
     * 2. Сбрасываем флаг в false
     * 3. Проверяем статус Shizuku
     * 4. Если NOT_INSTALLED → показываем диалог выбора источников снова
     */
    fun consumePendingSourceSuggestion() {
        if (!pendingSourceSuggestion) return

        pendingSourceSuggestion = false
        val status: ShizukuExecutor.Status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "consumePendingSourceSuggestion: shizuku=$status")

        if (status == ShizukuExecutor.Status.NOT_INSTALLED) {
            AppLog.i(TAG, "consumePendingSourceSuggestion: Shizuku not installed, showing sources")
            update { it.copy(showShizukuSources = true) }
        }
    }

    /**
     * Проверяет, доступен ли Shizuku для использования.
     *
     * @return true, если Shizuku установлен, запущен и имеет разрешение
     */
    fun isAvailable(): Boolean =
        ShizukuExecutor.checkStatus(app) == ShizukuExecutor.Status.AVAILABLE

    /**
     * Показывает диалог Shizuku с указанным статусом.
     * Делегирует `ShizukuWizardManager.showDialog()`.
     *
     * @param status Текущий статус Shizuku (NOT_INSTALLED, NOT_RUNNING, PERMISSION_REQUIRED)
     */
    fun showDialog(status: ShizukuExecutor.Status) {
        AppLog.i(TAG, "showDialog: status=$status")
        manager.showDialog(status)
    }

    /**
     * Показывает диалог опций (DNS filter, aggressive mode).
     * Вызывается когда Shizuku готов или после пропуска wizard.
     */
    fun showOptionsDialog() {
        AppLog.i(TAG, "showOptionsDialog")
        update { it.copy(showOptionsDialog = true) }
    }

    /**
     * Пользователь нажал "Установить" в диалоге Shizuku.
     * Устанавливает `pendingSourceSuggestion` и делегирует `manager.onInstallClicked()`.
     */
    fun dialogInstall() {
        AppLog.i(TAG, "dialogInstall: setting pendingSourceSuggestion=true")
        pendingSourceSuggestion = true
        manager.onInstallClicked()
    }

    /**
     * Пользователь нажал "Открыть Shizuku" в диалоге.
     * Делегирует `manager.onOpenAppClicked()`.
     */
    fun dialogOpenApp() {
        AppLog.i(TAG, "dialogOpenApp")
        manager.onOpenAppClicked()
    }

    /**
     * Пользователь нажал "Пропустить" в wizard.
     * Закрывает wizard и показывает диалог опций.
     */
    fun wizardSkip() {
        AppLog.i(TAG, "wizardSkip: skipping wizard, showing options")
        manager.onWizardSkip()
        showOptionsDialog()
    }

    /**
     * Запрашивает разрешение Shizuku для нашего приложения.
     * Делегирует `manager.requestPermission()`.
     *
     * @param code Код запроса для обработки в onRequestPermissionResult
     */
    fun requestPermission(code: Int) {
        AppLog.i(TAG, "requestPermission: code=$code")
        manager.requestPermission(code)
    }

    /**
     * Обрабатывает результат запроса разрешения Shizuku.
     * Если разрешение предоставлено → показывает диалог опций.
     *
     * @param granted true, если разрешение предоставлено
     */
    fun onPermissionResult(granted: Boolean) {
        AppLog.i(TAG, "onPermissionResult: granted=$granted")
        manager.onPermissionResult(granted)
        if (granted) showOptionsDialog()
    }

    /**
     * Пользователь нажал "Проверить статус" в wizard.
     * Если Shizuku доступен → показывает диалог опций.
     */
    fun wizardCheck() {
        AppLog.i(TAG, "wizardCheck")
        manager.checkStatus()
        if (isAvailable()) {
            AppLog.i(TAG, "wizardCheck: Shizuku available, showing options")
            showOptionsDialog()
        }
    }

    /**
     * Открывает диалог выбора источников установки Shizuku.
     * Делегирует `manager.onOpenSources()`.
     */
    fun openSources() {
        AppLog.i(TAG, "openSources")
        manager.onOpenSources()
    }

    /**
     * Закрывает диалог выбора источников.
     * Делегирует `manager.closeSources()`.
     */
    fun closeSources() {
        AppLog.i(TAG, "closeSources")
        manager.closeSources()
    }

    /**
     * Устанавливает Shizuku из выбранного источника.
     * Устанавливает `pendingSourceSuggestion` и делегирует `manager.installFromSource()`.
     *
     * @param source Идентификатор источника ("play", "aurora", "getapps", "github", "apkpure")
     */
    fun installFromSource(source: String) {
        AppLog.i(TAG, "installFromSource: source=$source, setting pendingSourceSuggestion=true")
        pendingSourceSuggestion = true
        manager.installFromSource(source)
    }

    /**
     * Пользователь нажал "Позже" в диалоге Shizuku.
     * Закрывает диалог и показывает диалог опций.
     */
    fun dialogLater() {
        AppLog.i(TAG, "dialogLater: showing options")
        manager.onLater()
        showOptionsDialog()
    }

    /**
     * Закрывает wizard без дальнейших действий.
     * Делегирует `manager.closeWizard()`.
     */
    fun closeWizard() {
        AppLog.i(TAG, "closeWizard")
        manager.closeWizard()
    }
}