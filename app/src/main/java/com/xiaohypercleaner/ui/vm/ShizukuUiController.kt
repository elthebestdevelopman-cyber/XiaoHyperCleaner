package com.xiaohypercleaner.ui.vm

import android.app.Application
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.ShizukuWizardManager
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog

/**
 * Делегат всего, что касается Shizuku / продвинутого режима до запуска цепочки:
 * диалоги установки, мастер, источники, запрос разрешения.
 * Снимает с MainViewModel ~80 строк обёрток.
 */
class ShizukuUiController(
    private val app: Application,
    private val update: ((MainUiState) -> MainUiState) -> Unit
) {
    companion object {
        private const val TAG = "MainVM"
    }

    private val manager = ShizukuWizardManager(app) { s ->
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

    /** Флаг «после возврата посмотреть, появился ли Shizuku» */
    var pendingSourceSuggestion = false
        private set

    /** Вызывается из refreshStatuses: один раз проверяем источники после возврата */
    fun consumePendingSourceSuggestion() {
        if (!pendingSourceSuggestion) return
        pendingSourceSuggestion = false
        val st = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "refreshStatuses: pendingSourceSuggestion, shizuku=$st")
        if (st == ShizukuExecutor.Status.NOT_INSTALLED) {
            update { it.copy(showShizukuSources = true) }
        }
    }

    fun isAvailable(): Boolean =
        ShizukuExecutor.checkStatus(app) == ShizukuExecutor.Status.AVAILABLE

    fun showDialog(status: ShizukuExecutor.Status) = manager.showDialog(status)

    fun showOptionsDialog() = update { it.copy(showOptionsDialog = true) }

    fun dialogInstall() {
        pendingSourceSuggestion = true
        manager.onInstallClicked()
    }

    fun dialogOpenApp() = manager.onOpenAppClicked()

    fun wizardSkip() {
        manager.onWizardSkip()
        showOptionsDialog()
    }

    fun requestPermission(code: Int) = manager.requestPermission(code)

    fun onPermissionResult(granted: Boolean) {
        manager.onPermissionResult(granted)
        if (granted) showOptionsDialog()
    }

    fun wizardCheck() {
        manager.checkStatus()
        if (isAvailable()) showOptionsDialog()
    }

    fun openSources() = manager.onOpenSources()

    fun closeSources() = manager.closeSources()

    fun installFromSource(source: String) {
        pendingSourceSuggestion = true
        manager.installFromSource(source)
    }

    fun dialogLater() {
        manager.onLater()
        showOptionsDialog()
    }

    fun closeWizard() = manager.closeWizard()
}