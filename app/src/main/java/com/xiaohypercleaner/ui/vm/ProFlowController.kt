package com.xiaohypercleaner.ui.vm

import android.app.Application
import android.content.Intent
import com.xiaohypercleaner.AppDependencies
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.data.RootExecutor
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.ui.MainUiState
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Делегат PRO-цепочки (Shizuku/ADB): переходы разрешений, авто-редиректы,
 * запуск цепочки, откат, перезагрузка, диалоги dev-mode.
 *
 * Снимает с MainViewModel ~250 строк сложной логики управления состоянием.
 *
 * Архитектура:
 * 1. `proceedToChain()` — точка входа после выбора опций
 * 2. `handleRefresh()` — вызывается при возврате из настроек
 * 3. `advance()` — машина состояний: accessibility → overlay → start chain
 * 4. `startChain()` — запуск OptimizationEngine через AdbEnablerService
 * 5. Redirect-логика — автоматические редиректы при отказе в разрешениях
 *
 * Теги логов оставлены "MainVM", чтобы logcat-фильтры продолжали работать.
 *
 * УЛУЧШЕНИЯ:
 * 1. Явные типы для всех переменных
 * 2. Полный JavaDoc для класса, конструктора, полей и методов
 * 3. Комментарии для сложной redirect-логики
 * 4. Импортирован AppDependencies для явных типов
 *
 * @param app Application контекст
 * @param prefs PreferencesManager для работы с DataStore
 * @param getState Функция получения текущего MainUiState
 * @param update Функция обновления MainUiState (DSL-паттерн)
 * @param openAccessibilityWithHint Callback для открытия настроек Accessibility с подсказкой
 * @param openAppInfoWithHint Callback для открытия App Info с подсказкой
 * @param openAccessibilitySettings Callback для открытия настроек Accessibility
 * @param openOverlaySettings Callback для открытия настроек Overlay
 * @param scope CoroutineScope для асинхронных операций (обычно viewModelScope)
 */
class ProFlowController(
    private val app: Application,
    private val prefs: PreferencesManager,
    private val getState: () -> MainUiState,
    private val update: ((MainUiState) -> MainUiState) -> Unit,
    private val openAccessibilityWithHint: () -> Unit,
    private val openAppInfoWithHint: () -> Unit,
    private val openAccessibilitySettings: () -> Unit,
    private val openOverlaySettings: () -> Unit,
    private val scope: CoroutineScope
) {
    companion object {
        /** TAG для логирования (оставлен "MainVM" для совместимости с logcat-фильтрами) */
        private const val TAG = "MainVM"
    }

    /**
     * Тип последнего редиректа для предотвращения бесконечных циклов.
     *
     * Используется в handleRefresh() для определения, куда редиректить пользователя
     * при отказе в разрешениях:
     * - NONE — редирект не активен
     * - ACCESSIBILITY — последний редирект был в настройки Accessibility
     * - APP_INFO — последний редирект был в App Info (для разблокировки restricted)
     */
    private enum class Redirect { NONE, ACCESSIBILITY, APP_INFO }

    /** Активна ли PRO-цепочка (запущена через proceedToChain) */
    private var flowActive: Boolean = false

    /** Тип последнего редиректа (для предотвращения циклов) */
    private var lastRedirect: Redirect = Redirect.NONE

    /** Был ли уже запущен restricted flow (для loop breaker) */
    private var restrictedFlowStarted: Boolean = false

    /**
     * Точка входа в PRO-цепочку.
     * Вызывается после подтверждения опций (DNS filter, aggressive mode).
     */
    fun proceedToChain() {
        AppLog.i(TAG, "proceedToChain")
        flowActive = true
        advance()
    }

    /**
     * PRO-ветка refreshStatuses: авто-продвижение цепочки и редиректы
     * при отказе в accessibility/overlay.
     *
     * Логика redirect (предотвращение бесконечных циклов):
     * 1. Пользователь отказал в accessibility → редирект в APP_INFO
     * 2. Пользователь вернулся из APP_INFO без результата → редирект в ACCESSIBILITY
     * 3. Пользователь снова отказал → показ restricted dialog (loop breaker)
     *
     * Возвращает управление вызывающему — Simple Mode обрабатывается ДО этого вызова.
     *
     * @param acc Текущий статус Accessibility Service
     * @param overlay Текущий статус Overlay permission
     * @param prevState Предыдущее состояние UI (для определения изменений)
     */
    fun handleRefresh(acc: Boolean, overlay: Boolean, prevState: MainUiState) {
        if (!flowActive) return

        val accessibilityJustChanged: Boolean = !prevState.previousAccessibility && acc
        val overlayJustChanged: Boolean = !prevState.previousOverlay && overlay

        when {
            // Accessibility только что включён → продолжаем цепочку
            accessibilityJustChanged -> {
                AppLog.i(TAG, "handleRefresh: accessibility just enabled, continuing chain")
                resetRedirectFlow()
                update { it.copy(accessibilityAttempts = 0) }
                advance()
            }

            // Overlay только что включён → продолжаем цепочку
            overlayJustChanged -> {
                AppLog.i(TAG, "handleRefresh: overlay just enabled, continuing chain")
                update { it.copy(overlayAttempts = 0) }
                advance()
            }

            // Пользователь отказал в accessibility после попытки → redirect логика
            !acc && prevState.accessibilityAttempts > 0 -> {
                AppLog.i(TAG, "handleRefresh: accessibility not enabled after attempt")
                when {
                    // Первый отказ → редирект в APP_INFO (для разблокировки restricted)
                    lastRedirect == Redirect.ACCESSIBILITY && !restrictedFlowStarted -> {
                        AppLog.i(TAG, "handleRefresh: denied — auto-redirect to app info")
                        restrictedFlowStarted = true
                        lastRedirect = Redirect.APP_INFO
                        openAppInfoWithHint()
                    }

                    // Вернулся из APP_INFO без результата → редирект обратно в ACCESSIBILITY
                    lastRedirect == Redirect.APP_INFO -> {
                        AppLog.i(
                            TAG,
                            "handleRefresh: back from app info — auto-redirect to accessibility"
                        )
                        lastRedirect = Redirect.ACCESSIBILITY
                        openAccessibilityWithHint()
                    }

                    // Loop breaker — пользователь уже пробовал оба пути, показываем диалог
                    else -> {
                        AppLog.i(TAG, "handleRefresh: loop breaker — showing restricted dialog")
                        update {
                            it.copy(
                                showRestrictedDialog = true,
                                showAccessibilityDialog = false,
                                showOverlayDialog = false,
                                restrictedSettingsShown = true
                            )
                        }
                    }
                }
            }

            // Пользователь отказал в overlay после попытки → показываем диалог снова
            !overlay && prevState.overlayAttempts > 0 -> {
                AppLog.i(
                    TAG,
                    "handleRefresh: overlay not enabled after attempt, showing dialog again"
                )
                update {
                    it.copy(
                        showOverlayDialog = true,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            // Нет изменений → продолжаем цепочку
            else -> advance()
        }
    }

    /**
     * Обрабатывает подтверждение диалога (accessibility или overlay).
     * Увеличивает счётчик попыток и открывает соответствующие настройки.
     */
    fun dialogAgreed() {
        val s: MainUiState = getState()

        if (s.showAccessibilityDialog) {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    accessibilityAttempts = it.accessibilityAttempts + 1
                )
            }
            ChainFlags.waitingAccessibilityReturn = true
            lastRedirect = Redirect.ACCESSIBILITY
            openAccessibilitySettings()
        } else if (s.showOverlayDialog) {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false,
                    overlayAttempts = it.overlayAttempts + 1
                )
            }
            openOverlaySettings()
        } else {
            update {
                it.copy(
                    showAccessibilityDialog = false,
                    showOverlayDialog = false,
                    showRestrictedDialog = false
                )
            }
        }
    }

    /**
     * Обрабатывает отмену диалога.
     * Сбрасывает flowActive и все счётчики попыток.
     */
    fun dialogCancelled() {
        flowActive = false
        resetRedirectFlow()
        update {
            it.copy(
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                accessibilityAttempts = 0,
                overlayAttempts = 0,
                restrictedSettingsShown = false
            )
        }
    }

    /**
     * Пользователь согласился с restricted dialog.
     * Редирект в App Info для разблокировки restricted settings.
     */
    fun restrictedDialogAgreed() {
        update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                restrictedSettingsShown = true
            )
        }
        lastRedirect = Redirect.APP_INFO
        openAppInfoWithHint()
    }

    /**
     * Пользователь отменил restricted dialog.
     * Сбрасывает flowActive и все счётчики.
     */
    fun restrictedDialogCancelled() {
        flowActive = false
        resetRedirectFlow()
        update {
            it.copy(
                showRestrictedDialog = false,
                showAccessibilityDialog = false,
                accessibilityAttempts = 0,
                restrictedSettingsShown = false
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Запуск цепочки
    // ═══════════════════════════════════════════════════════════════

    /**
     * Машина состояний: проверяет разрешения и показывает нужный диалог.
     *
     * Порядок проверки:
     * 1. Accessibility → если нет, показываем диалог
     * 2. Overlay → если нет, показываем диалог
     * 3. Всё есть → запускаем цепочку
     */
    private fun advance() {
        val s: MainUiState = getState()
        AppLog.i(TAG, "advance: acc=${s.isAccessibilityEnabled}, overlay=${s.isOverlayGranted}")

        when {
            !s.isAccessibilityEnabled -> {
                AppLog.i(TAG, "advance: showing accessibility dialog")
                update {
                    it.copy(
                        showAccessibilityDialog = true,
                        showOverlayDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            !s.isOverlayGranted -> {
                AppLog.i(TAG, "advance: showing overlay dialog")
                update {
                    it.copy(
                        showOverlayDialog = true,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false
                    )
                }
            }

            else -> {
                AppLog.i(TAG, "advance: all permissions granted, starting chain")
                flowActive = false
                resetRedirectFlow()
                update {
                    it.copy(
                        showOverlayDialog = false,
                        showAccessibilityDialog = false,
                        showRestrictedDialog = false,
                        accessibilityAttempts = 0,
                        overlayAttempts = 0,
                        restrictedSettingsShown = false
                    )
                }
                startChain()
            }
        }
    }

    /**
     * Запускает цепочку оптимизации через AdbEnablerService.
     *
     * Сохраняет pending flag в DataStore, затем:
     * - Если accessibility уже включён → запускает сервис напрямую
     * - Иначе → открывает настройки accessibility с флагом waitingAccessibilityReturn
     */
    private fun startChain() {
        val currentState: MainUiState = getState()
        AppLog.i(
            TAG,
            "startChain: setting pending flag, dnsFilter=${currentState.dnsFilterEnabled}, " +
                    "aggressive=${currentState.aggressiveMode}"
        )

        scope.launch {
            prefs.setPendingOptimization(true)
            prefs.setDnsFilterEnabled(currentState.dnsFilterEnabled)
            prefs.setAggressiveMode(currentState.aggressiveMode)
            AppLog.i(TAG, "startChain: pending flag set")

            // Повторная проверка состояния (защита от race condition)
            val freshState: MainUiState = getState()
            if (freshState.isAccessibilityEnabled) {
                AppLog.i(
                    TAG,
                    "startChain: accessibility already enabled, starting service directly"
                )
                val intent: Intent = Intent(app, AdbEnablerService::class.java).apply {
                    action = AdbEnablerService.ACTION_START_CHAIN
                }
                app.startService(intent)
            } else {
                AppLog.i(TAG, "startChain: opening accessibility settings")
                ChainFlags.waitingAccessibilityReturn = true
                update { it.copy(showAccessibilityDialog = true) }
            }
        }
    }

    /**
     * Сбрасывает состояние redirect-логики.
     * Вызывается при успешном завершении или отмене цепочки.
     */
    private fun resetRedirectFlow() {
        lastRedirect = Redirect.NONE
        restrictedFlowStarted = false
    }

    // ═══════════════════════════════════════════════════════════════
    // Откат / перезагрузка / dev-mode
    // ═══════════════════════════════════════════════════════════════

    /**
     * Откатывает все изменения оптимизации.
     * Вызывается из диалога подтверждения.
     */
    fun restoreOptimization() {
        if (getState().isWorking) return

        scope.launch {
            try {
                update { it.copy(isWorking = true, progress = 0f) }
                val deps: AppDependencies = XiaoHyperApp.testDeps ?: (app as XiaoHyperApp).deps

                // 1) ADB/Shizuku restore (Pro) — best effort
                val adbOk = runCatching {
                    deps.newEngine().restore(
                        OptimizationEngine.Callbacks(
                            onProgress = { p: Float -> update { it.copy(progress = p * 0.4f) } }
                        )
                    )
                }.getOrDefault(false)

                // 2) Simple Mode: реальный откат тумблеров (targetChecked инвертирован)
                val toggled = prefs.getSimpleToggledSteps()
                var simpleOk = toggled.isEmpty()
                if (toggled.isNotEmpty()) {
                    val svc = AdbEnablerService.instance
                    if (svc != null) {
                        AppLog.i(TAG, "restoreOptimization: reversing ${toggled.size} simple toggles")
                        simpleOk = svc.reverseSimpleToggles(toggled) { p ->
                            update { it.copy(progress = 0.4f + p * 0.6f) }
                        }
                    } else {
                        AppLog.w(TAG, "restoreOptimization: accessibility offline, skip simple reverse")
                        simpleOk = false
                    }
                }

                prefs.clearSimpleToggledSteps()
                prefs.setHiddenSettingsApplied(false)
                update {
                    it.copy(
                        isWorking = false,
                        isOptimized = false,
                        // Успех если хоть один канал отработал, или откатывать было нечего
                        restoreFailed = !adbOk && !simpleOk
                    )
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "restore failed: ${e.message}", e)
                update { it.copy(isWorking = false, restoreFailed = true) }
            }
        }
    }

    /**
     * Подтверждает перезагрузку устройства.
     * Вызывается из диалога подтверждения.
     */
    fun confirmReboot() {
        update { it.copy(showRebootDialog = false, isWorking = true) }

        scope.launch {
            try {
                // Автоперезагрузка только через root — без ADB/Shizuku fallback
                val root = RootExecutor()
                if (!root.isAvailable()) {
                    AppLog.w(TAG, "confirmReboot: root unavailable — abort")
                    update { it.copy(isWorking = false, rebootFailed = true) }
                    return@launch
                }
                val engine = OptimizationEngine(root)
                val ok: Boolean = engine.reboot()
                update { it.copy(isWorking = false, rebootFailed = !ok) }
            } catch (e: Exception) {
                AppLog.e(TAG, "reboot failed: ${e.message}", e)
                update { it.copy(isWorking = false, rebootFailed = true) }
            }
        }
    }

    /** Показывает диалог подтверждения перезагрузки */
    fun requestReboot() = update { it.copy(showRebootDialog = true) }

    /** Скрывает диалог подтверждения перезагрузки */
    fun dismissRebootDialog() = update { it.copy(showRebootDialog = false) }

    /** Скрывает диалог ошибки перезагрузки */
    fun dismissRebootFailed() = update { it.copy(rebootFailed = false) }

    /** Скрывает диалог ошибки отката */
    fun dismissRestoreFailed() = update { it.copy(restoreFailed = false) }

    /**
     * Повторяет попытку после диалога "нужен режим разработчика".
     * Перезапускает AdbEnablerService с ACTION_RETRY_DEV.
     */
    fun devModeDialogRetry() {
        AppLog.i(TAG, "devModeDialog: retry — resuming chain (service will restart overlay)")
        update { it.copy(showDevModeDialog = false) }
        val intent: Intent = Intent(app, AdbEnablerService::class.java).apply {
            action = AdbEnablerService.ACTION_RETRY_DEV
        }
        app.startService(intent)
    }

    /**
     * Отменяет цепочку после диалога "нужен режим разработчика".
     * Вызывает OverlayController.triggerCancel() для остановки оверлея.
     */
    fun devModeDialogCancel() {
        AppLog.i(TAG, "devModeDialog: cancel — stopping chain")
        update { it.copy(showDevModeDialog = false) }
        OverlayController.triggerCancel()
    }
}