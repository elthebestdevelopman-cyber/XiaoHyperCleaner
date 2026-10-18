package com.xiaohypercleaner.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.OptimizationScope
import com.xiaohypercleaner.data.PermissionFlowManager
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.RestrictedLocation
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.ScreenCrawler
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.SimpleModeController
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.ChainFlags
import com.xiaohypercleaner.service.InstallerWatcherState
import com.xiaohypercleaner.service.InstallerWatcherStatus
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.SimpleRunner
import com.xiaohypercleaner.service.SimpleStepBridge
import com.xiaohypercleaner.ui.vm.ProFlowController
import com.xiaohypercleaner.ui.vm.ShizukuUiController
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.DiagnosticSnapshotManager
import com.xiaohypercleaner.util.OptimizationNotifier
import com.xiaohypercleaner.util.ShizukuHelper
import com.xiaohypercleaner.util.SelfApkExporter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Главный ViewModel приложения.
 *
 * Архитектура:
 * - Тонкий фасад над делегатами: SimpleModeController, ProFlowController, ShizukuUiController
 * - SimpleStepBridge — мост между AdbEnablerService и ViewModel
 * - OptimizationNotifier — реактивное получение результатов Pro-режима
 *
 * ИЗМЕНЕНИЕ (оптимизация скорости):
 * Убраны ретраи в onSimpleStepResult — при неудаче шага сразу переходим
 * к следующему, вместо повторной попытки. Это экономит ~16с на каждый
 * неудачный шаг (ранее: 2 попытки × 16с = 32с, теперь: 1 × 16с = 16с).
 */
@Suppress("SpellCheckingInspection")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val SHIZUKU_PERMISSION_CODE: Int = 9001

        /** Скрытый вход в FULL-диагностику: тапов по версии в меню. */
        private const val VERSION_TAPS_FOR_FULL = 7
        private const val VERSION_TAP_WINDOW_MS = 7000L

        /**
         * Потолок визитов на экран «Установка неизвестных приложений» в предшаге: показ и одна
         * повторная попытка. Отказ выдать право иначе запирал бы пользователя в цикле настроек
         * (ожидание живо, pending не выставлен → каждый возврат снова открывал настройки).
         */
        private const val MAX_INSTALLER_PERMISSION_ATTEMPTS = 2

        /** Скан не выполнялся: accessibility-сервис недоступен. */
        private const val REASON_SCAN_NO_SERVICE = "no_service"
    }

    private val app: XiaoHyperApp = application as XiaoHyperApp
    private val prefs = app.preferencesManager
    private val permissionFlow: PermissionFlowManager = PermissionFlowManager(app)

    private val _state: MutableStateFlow<MainUiState> = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private fun update(transform: (MainUiState) -> MainUiState) = _state.update(transform)

    val simpleController: SimpleModeController =
        SimpleModeController(app, permissionFlow) { mergeSimpleState(it) }

    private val shizuku: ShizukuUiController = ShizukuUiController(app, ::update)

    private val proFlow: ProFlowController = ProFlowController(
        app = app,
        prefs = prefs,
        getState = { _state.value },
        update = ::update,
        openAccessibilityWithHint = ::openAccessibilityWithHint,
        openAppInfoWithHint = ::openAppInfoWithHint,
        openAccessibilitySettings = ::openAccessibilitySettings,
        openOverlaySettings = ::openOverlaySettings,
        scope = viewModelScope
    )

    private var stepAttempt: Int = 1
    private var autoFlowJob: Job? = null
    private val failedSimpleStepIds: MutableList<String> = mutableListOf()

    /** Счётчик тапов по версии в меню (скрытый вход в FULL-диагностику). */
    private var versionTapCount: Int = 0
    private var versionTapFirstMs: Long = 0L

    /** Визиты на экран «Установка неизвестных приложений» в текущем предшаге (см. потолок). */
    private var installerPermissionAttempts: Int = 0

    init {
        AppLog.i(TAG, "init started")

        SimpleStepBridge.onResult = { success, reason ->
            AppLog.i(TAG, "SimpleStepBridge result: $success reason=$reason")
            // P0 (прогон rmuu7xcch): разрешение «Поверх других окон» пропало и не
            // восстановилось. Продолжать шаги нельзя — каждый следующий упал бы
            // overlay_not_attached (17 шагов каскадом). Прогон останавливается одним
            // итогом, и итог показывается БЕЗ оверлея: он мёртв, поэтому экран
            // результатов поднимает MainActivity.
            if (reason == SimpleStepBridge.REASON_OVERLAY_PERMISSION_LOST) {
                simpleController.onOverlayPermissionLost()
                showResultWithoutOverlay()
            } else {
                handleSimpleStepResult(success, reason)
            }
        }

        SimpleStepBridge.onSkipped = { stepId, kind ->
            // Skipped = «нет на устройстве» (пакет не установлен): текст лога не должен
            // выдавать одно за другое, kind приходит из раннера транспортом.
            AppLog.i(TAG, "SimpleStepBridge skipped: $stepId kind=$kind")
            stepAttempt = 1
            simpleController.onStepSkipped(stepId, kind)
        }

        SimpleStepBridge.onPartialCancel = {
            // S4: отмена подтверждена в диалоге оверлея — прогон частичный.
            AppLog.i(TAG, "automation cancel confirmed by user (partial=true)")
            simpleController.markPartialRun()
        }

        OverlayController.setOnCancel {
            AppLog.i(TAG, "automation cancelled by user via overlay")
            AdbEnablerService.instance?.cancelRunner()
            OverlayController.endPhase()
            OverlayController.hide(app)
            closeSimpleMode()
        }

        OverlayController.setOnResultClose {
            AppLog.i(TAG, "result overlay closed by user")
            OverlayController.hide(app)
            closeSimpleMode()
        }

        viewModelScope.launch {
            prefs.isHiddenSettingsApplied.collect { applied ->
                AppLog.i(TAG, "isHiddenSettingsApplied changed to $applied")
                update { it.copy(isOptimized = applied) }
            }
        }

        viewModelScope.launch {
            val rootOk = try {
                com.xiaohypercleaner.data.RootExecutor().isAvailable()
            } catch (_: Exception) {
                false
            }
            AppLog.i(TAG, "canAutoReboot (root)=$rootOk")
            update { it.copy(canAutoReboot = rootOk) }
        }

        viewModelScope.launch {
            prefs.dnsFilterEnabled.collect { enabled ->
                AppLog.i(TAG, "dnsFilterEnabled changed to $enabled")
                update { it.copy(dnsFilterEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            prefs.notifTransparency.collect { enabled ->
                AppLog.i(TAG, "notifTransparency changed to $enabled")
                update { it.copy(notifTransparency = enabled) }
                simpleController.setNotifTransparency(enabled)
            }
        }

        // Ловец установщика: статус последнего срабатывания в UI. Флаг в меню снят —
        // решение владельца: ловец не должен спорить с пользователем (3.6).
        viewModelScope.launch {
            InstallerWatcherState.status.collect { status ->
                update { it.copy(installerWatcher = status) }
            }
        }

        // Уровень диагностики: release-дефолт задаёт приложение, override — 7 тапов.
        viewModelScope.launch {
            prefs.diagLevelOverride.collect { raw ->
                val parsed = DiagnosticSnapshotManager.parseLevel(raw)
                AppLog.i(TAG, "diagLevelOverride=$raw parsed=${parsed?.name}")
                if (parsed != null) {
                    DiagnosticSnapshotManager.setLevel(parsed, "override")
                }
                update { it.copy(diagLevelFull = parsed == DiagnosticSnapshotManager.DiagnosticLevel.FULL) }
            }
        }

        viewModelScope.launch {
            OptimizationNotifier.result.collect { result ->
                AppLog.i(TAG, "notifier result: $result")
                when (result) {
                    is OptimizationNotifier.Result.Success -> {
                        update {
                            it.copy(
                                isWorking = false,
                                isOptimized = true,
                                showFinalDialog = true,
                                optimizationSuccess = true,
                                finalReport = result.details
                            )
                        }
                        OptimizationNotifier.reset()
                    }

                    is OptimizationNotifier.Result.Failure -> {
                        update {
                            it.copy(
                                isWorking = false,
                                isOptimized = false,
                                showFinalDialog = true,
                                optimizationSuccess = false,
                                finalReport = result.details
                            )
                        }
                        OptimizationNotifier.reset()
                    }

                    is OptimizationNotifier.Result.Running -> {
                        update { it.copy(isWorking = true) }
                    }

                    is OptimizationNotifier.Result.DevModeRequired -> {
                        AppLog.i(TAG, "notifier: dev mode required, hiding overlay, showing dialog")
                        OverlayController.hide(app)
                        update { it.copy(showDevModeDialog = true) }
                    }

                    is OptimizationNotifier.Result.Idle -> {}
                }
            }
        }

        AppLog.i(TAG, "init completed")
    }

    override fun onCleared() {
        OverlayController.setOnCancel(null)
        OverlayController.setOnResultClose(null)
        OverlayController.hide(app)
        simpleController.destroy()
        autoFlowJob?.cancel()
    }

    private fun mergeSimpleState(s: SimpleModeController.SimpleModeState) {
        val running: Boolean =
            s.active || s.step != null || s.done != null || s.phase != SimpleModePhase.INACTIVE

        if (s.phase == SimpleModePhase.DONE || s.phase == SimpleModePhase.INACTIVE) {
            viewModelScope.launch { prefs.setPendingSimpleMode(false) }
        }

        update { current ->
            current.copy(
                simpleModePhase = s.phase,
                permissionSubPhase = s.permissionSubPhase,
                simpleStep = s.step,
                simpleDone = s.done,
                simpleModeActive = running,
                showAccessibilityDialog = if (running) s.showAccessibilityDialog else false,
                showOverlayDialog = if (running) s.showOverlayDialog else false,
                showRestrictedDialog = if (running) s.showRestrictedDialog else false,
                showAppInfoDialog = if (running) s.showAppInfoDialog else false,
                showLocationDialog = if (running) s.showLocationDialog else false,
                showPermissionFallbackDialog = if (running) s.showPermissionFallbackDialog else false,
                stuckPhase = if (running) s.stuckPhase else null,
                showRestrictedSettingsScreen = if (running) s.showRestrictedSettingsScreen else false,
                showBatteryDialog = if (running) s.showBatteryDialog else false,
                appInfoAttempts = if (running) s.appInfoAttempts else 0,
                restrictedSettingsShown = if (running) s.restrictedSettingsShown else false
            )
        }
    }

    fun refreshStatuses() {
        AppLog.i(TAG, "refreshStatuses called")
        val component: String = ComponentName(app, AdbEnablerService::class.java).flattenToString()
        val acc: Boolean = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(component) == true
        val overlay: Boolean = Settings.canDrawOverlays(app)
        val prevState: MainUiState = _state.value

        AppLog.i(
            TAG,
            "refreshStatuses: accessibility=$acc (was ${prevState.previousAccessibility}), " +
                    "overlay=$overlay (was ${prevState.previousOverlay}), simpleModeActive=${_state.value.simpleModeActive}"
        )

        update {
            it.copy(
                isAccessibilityEnabled = acc,
                isOverlayGranted = overlay,
                previousAccessibility = acc,
                previousOverlay = overlay
            )
        }

        shizuku.consumePendingSourceSuggestion()
        simpleController.updatePermissionStatuses(acc, overlay)

        if (_state.value.simpleModeActive) {
            if (!ChainFlags.waitingAccessibilityReturn) {
                simpleController.onResumeAfterPermissionReturn()
            } else {
                simpleController.refresh()
            }
            return
        }

        proFlow.handleRefresh(acc, overlay, prevState)
    }

    /**
     * R2.1: скан окон с переключателями — read-only обход БЕЗ оверлея.
     *
     * Гейт: скан не идёт во время фазы прогона/отката (у фазы защита есть, у скана нет).
     * Проверяется перед стартом и в цикле обхода ([ScreenCrawler.crawl]); молчаливого
     * выхода нет — статус со причиной уходит в [MainUiState.windowScan]. Отчёт сохраняется
     * в DataStore и локальным файлом в diag-каталоге: в pack shareLog он не входит
     * (в пак идёт только файл лога — .clinerules, «Краул окон»).
     */
    fun startWindowScan() {
        if (_state.value.windowScan.running) {
            AppLog.i(TAG, "window scan: already running")
            return
        }
        val phaseRunning = { OverlayController.phaseRunning || SimpleRunner.isRunning }
        if (phaseRunning()) {
            AppLog.w(TAG, "window scan: skipped, phase in progress")
            update {
                it.copy(
                    windowScan = WindowScanStatus(skippedReason = ScreenCrawler.REASON_PHASE_RUNNING)
                )
            }
            return
        }
        val service = AdbEnablerService.instance
        if (service == null) {
            AppLog.w(TAG, "window scan: skipped, accessibility service offline")
            update { it.copy(windowScan = WindowScanStatus(skippedReason = REASON_SCAN_NO_SERVICE)) }
            return
        }
        AppLog.i(TAG, "window scan: started")
        update { it.copy(windowScan = WindowScanStatus(running = true)) }
        viewModelScope.launch {
            val entries = runCatching { ScreenCrawler.entryPoints(app) }
                .onFailure { AppLog.w(TAG, "window scan: entry points failed: ${it.message}") }
                .getOrDefault(emptyList())
            when (val result = ScreenCrawler.crawl(service, entries, isPhaseRunning = phaseRunning)) {
                is ScreenCrawler.CrawlResult.Done -> {
                    val json = result.report.toJson()
                    runCatching { prefs.saveCrawlReport(json) }
                        .onFailure { AppLog.w(TAG, "window scan: datastore save failed: ${it.message}") }
                    writeCrawlReportFile(json)
                    AppLog.i(
                        TAG,
                        "window scan: done screens=${result.report.screens.size} " +
                            "candidates=${result.report.candidateCount} " +
                            "lexicon_unmatched=${result.report.unmatchedLexicon.size}"
                    )
                    update {
                        it.copy(
                            windowScan = WindowScanStatus(
                                done = true,
                                screens = result.report.screens.size,
                                candidates = result.report.candidateCount
                            )
                        )
                    }
                }

                is ScreenCrawler.CrawlResult.Skipped -> {
                    AppLog.w(
                        TAG,
                        "window scan: skipped reason=${result.reason} screens=${result.screensDone}"
                    )
                    update {
                        it.copy(
                            windowScan = WindowScanStatus(
                                skippedReason = result.reason,
                                aborted = result.reason == ScreenCrawler.REASON_PHASE_STARTED
                            )
                        )
                    }
                }
            }
        }
    }

    /** Локальный файл отчёта (diag-каталог приложения): в pack shareLog не входит. */
    private fun writeCrawlReportFile(json: String) {
        runCatching {
            val dir = app.getExternalFilesDir("diag") ?: File(app.filesDir, "diag")
            dir.mkdirs()
            val name = "crawl_report_${System.currentTimeMillis()}.json"
            File(dir, name).writeText(json)
            AppLog.i(TAG, "window scan: report saved locally ($name)")
        }.onFailure { AppLog.w(TAG, "window scan: local save failed: ${it.message}") }
    }

    fun checkRestrictedSettingsOnResume() {
        AppLog.i(TAG, "checkRestrictedSettingsOnResume called (no-op, handled by refreshStatuses)")
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        permissionFlow.isIgnoringBatteryOptimizations()

    fun onBatteryOptimizationReturn() {
        AppLog.i(TAG, "onBatteryOptimizationReturn")
        viewModelScope.launch {
            var ignoring: Boolean = permissionFlow.isIgnoringBatteryOptimizations()
            if (!ignoring) {
                delay(600.milliseconds)
                ignoring = permissionFlow.isIgnoringBatteryOptimizations()
            }
            AppLog.i(TAG, "onBatteryOptimizationReturn: isIgnoring=$ignoring")
            if (ignoring) {
                update { it.copy(showBatteryDialog = false, showLevelConfirm = true) }
                simpleController.onBatteryReturn(true)
            } else {
                simpleController.reshowBatteryDialog()
            }
            refreshStatuses()
        }
    }

    private var eeaNoticeAcknowledged: Boolean = false

    fun startFlow() {
        AppLog.i(TAG, "startFlow called, isWorking=${_state.value.isWorking}")
        if (_state.value.isWorking) return
        if (_state.value.showLevelDialog || _state.value.showLevelConfirm) return

        if (_state.value.simpleModeActive && _state.value.simpleModePhase == SimpleModePhase.DONE) {
            AppLog.i(TAG, "startFlow: previous run finished — resetting")
            closeSimpleMode()
        }

        if (_state.value.simpleModeActive &&
            _state.value.simpleModePhase == SimpleModePhase.STEPS &&
            !_state.value.isWorking
        ) {
            val runnerBusy = AdbEnablerService.instance?.let {
                com.xiaohypercleaner.service.SimpleRunner.isRunning
            } == true
            if (!runnerBusy) {
                AppLog.w(TAG, "startFlow: stale simpleModeActive without runner — resetting")
                closeSimpleMode()
            }
        }

        if (_state.value.simpleModeActive) {
            AppLog.i(TAG, "startFlow: simple mode already active, ignoring")
            return
        }

        val profile = RomProfile.detect(app)
        if (profile.optimizationScope == OptimizationScope.PRE_OPTIMIZED_EEA && !eeaNoticeAcknowledged) {
            update { it.copy(showEeaNoticeDialog = true, eeaRegionName = profile.regionCode) }
            return
        }

        update { it.copy(showLevelDialog = true) }
    }

    fun onEeaNoticeAgreed() {
        eeaNoticeAcknowledged = true
        update { it.copy(showEeaNoticeDialog = false, showLevelDialog = true) }
    }

    fun onEeaNoticeCancelled() {
        update { it.copy(showEeaNoticeDialog = false) }
    }

    fun onLevelChosen(level: OptimizationMode) {
        AppLog.i(TAG, "onLevelChosen: $level")
        update {
            it.copy(showLevelDialog = false, showLevelConfirm = true, selectedLevel = level)
        }
    }

    fun confirmLevelStart(level: OptimizationMode) {
        AppLog.i(TAG, "confirmLevelStart: $level")
        update { it.copy(showLevelConfirm = false) }
        when (level) {
            OptimizationMode.SIMPLE -> {
                if (_state.value.simpleModeActive) {
                    simpleController.continueToSteps()
                } else {
                    // Предшаг «одной кнопки»: окно установщика живёт только во время
                    // установки, поэтому спрашиваем сейчас, а не после начала оптимизации.
                    update { it.copy(showInstallerPreStep = true) }
                }
            }

            OptimizationMode.PRO -> startAdvancedFlow()
        }
    }

    fun cancelLevelConfirm() {
        AppLog.i(TAG, "cancelLevelConfirm")
        update { it.copy(showLevelConfirm = false, selectedLevel = null) }
    }

    @VisibleForTesting
    internal fun startSimpleMode() {
        AppLog.i(TAG, "startSimpleMode")
        prepareSimpleModePending()
        simpleController.start()
    }

    /**
     * Готовит Простой режим, не запуская шаги: pending-флаг переживает переустановку и
     * срабатывает в [tryResumePendingSimpleMode]. Нужен предшагу установщика: он сам
     * «стартует» оптимизацию уже после установки.
     *
     * Здесь же снимается момент открытия установщика: этой точкой закрываются «Пропустить»
     * и отмена ожидания (оба идут через [startSimpleMode]) — ожидание установки снято, и
     * восстанавливать pending по установке больше не нужно.
     */
    private fun prepareSimpleModePending() {
        failedSimpleStepIds.clear()
        stepAttempt = 1
        viewModelScope.launch {
            // Штатный старт не ждёт установки: момент открытия установщика снимается, иначе
            // stale `openedAt` примешивался бы к следующему восстановлению pending.
            prefs.setSelfApkOpenedAt(0L)
            prefs.setPendingSimpleMode(true)
        }
    }

    /**
     * Предшаг «одной кнопки»: пользователь согласился снять рекомендации установщика.
     * Порядок обязателен: право на установку из неизвестных источников → копия сборки →
     * окно установщика → pending. pending ставится ТОЛЬКО после удачного открытия окна:
     * иначе Простой режим стартовал бы сам, хотя установки не было. Неудача на любом шаге —
     * обычный старт ([startSimpleMode]); без права ожидание сохраняется, и проход
     * повторяется по возврату из настроек ([tryResumePendingSimpleMode]) — но не больше
     * [MAX_INSTALLER_PERMISSION_ATTEMPTS] визитов, иначе отказ выдать право запирал бы
     * пользователя в цикле настроек.
     */
    fun onInstallerPreStepDisableNow() {
        AppLog.i(TAG, "installer pre-step: disable now")
        installerPermissionAttempts = 0
        update { it.copy(showInstallerPreStep = false, installerPreStepWaiting = true) }
        viewModelScope.launch { runInstallerPreStep() }
    }

    /** Один проход предшага: право → копия → окно установщика → pending. */
    private suspend fun runInstallerPreStep() {
        if (!canInstallUnknownApps()) {
            installerPermissionAttempts++
            if (installerPermissionAttempts > MAX_INSTALLER_PERMISSION_ATTEMPTS) {
                // Право так и не выдали: держать пользователя в цикле настроек нельзя.
                // Копии ещё нет, pending не выставлен — идём обычным стартом.
                AppLog.w(TAG, "installer pre-step: unknown sources not granted — обычный старт")
                update { it.copy(installerPreStepWaiting = false) }
                startSimpleMode()
                return
            }
            AppLog.i(
                TAG,
                "installer pre-step: unknown sources permission required " +
                    "attempt=$installerPermissionAttempts/$MAX_INSTALLER_PERMISSION_ATTEMPTS"
            )
            openUnknownSourcesSettings()
            return
        }
        val apk = withContext(Dispatchers.IO) { SelfApkExporter.copyToCache(app) }
        if (apk == null) {
            AppLog.w(TAG, "installer pre-step: apk copy failed — обычный старт")
            update { it.copy(installerPreStepWaiting = false) }
            startSimpleMode()
            return
        }
        // Момент открытия установщика и pending пишутся ДО старта окна: по ним на возврате
        // проверяется, состоялась ли установка (`lastUpdateTime` пакета — сигнал гейта).
        // Оба значения awaited: установщик не должен открываться раньше, чем они на диске.
        val openedAt = System.currentTimeMillis()
        prefs.setSelfApkOpenedAt(openedAt)
        prefs.setPendingSimpleMode(true)
        val opened = withContext(Dispatchers.IO) { SelfApkExporter.openInstaller(app, apk) }
        if (!opened) {
            AppLog.w(TAG, "installer pre-step: installer not opened — обычный старт")
            // Установщик не открылся — ни ожидания, ни копии быть не должно.
            clearInstallerPreStepState()
            withContext(Dispatchers.IO) { SelfApkExporter.purge(app) }
            update { it.copy(installerPreStepWaiting = false) }
            startSimpleMode()
            return
        }
        installerPermissionAttempts = 0
        viewModelScope.launch { prefs.setOptimizationMode(OptimizationMode.SIMPLE) }
    }

    /**
     * Снимает состояние ожидания установки: pending и момент открытия установщика. Оба
     * снимаются вместе — иначе `openedAt` без pending (или наоборот) давал бы ложное
     * восстановление Простого режима после решения пользователя.
     */
    private suspend fun clearInstallerPreStepState() {
        prefs.setPendingSimpleMode(false)
        prefs.setSelfApkOpenedAt(0L)
    }

    /** Разрешение ставить APK из неизвестных источников (Android 8+). */
    private fun canInstallUnknownApps(): Boolean = runCatching {
        app.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** Экран «Установка неизвестных приложений» для нашего пакета: выдать право и вернуться. */
    private fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = runCatching {
            app.packageManager.queryIntentActivities(intent, 0).size
        }.getOrDefault(-1)
        val started = runCatching {
            app.startActivity(intent)
            true
        }.getOrDefault(false)
        AppLog.i(
            TAG,
            "installer pre-step: unknown sources settings resolved=$resolved started=$started"
        )
        if (!started) AppLog.w(TAG, "installer pre-step: unknown sources settings not opened")
    }

    /** Предшаг отклонён: обычный старт Простого режима, персист не трогаем. */
    fun onInstallerPreStepSkip() {
        AppLog.i(TAG, "installer pre-step: skipped")
        update { it.copy(showInstallerPreStep = false) }
        startSimpleMode()
    }

    /** Возврат из установщика без переустановки: ждать больше нечего. */
    fun onInstallerPreStepWaitingSkip() {
        AppLog.i(TAG, "installer pre-step: waiting cancelled")
        update { it.copy(installerPreStepWaiting = false) }
        startSimpleMode()
    }

    private fun startAdvancedFlow() {
        val status: ShizukuExecutor.Status = ShizukuExecutor.checkStatus(app)
        AppLog.i(TAG, "startAdvancedFlow: shizuku status=$status")
        when (status) {
            ShizukuExecutor.Status.AVAILABLE -> shizuku.showOptionsDialog()
            ShizukuExecutor.Status.PERMISSION_REQUIRED -> {
                shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
            }

            ShizukuExecutor.Status.NOT_RUNNING -> {
                shizuku.showDialog(status)
                ShizukuHelper.openWirelessDebuggingSettings(app)
            }

            ShizukuExecutor.Status.NOT_INSTALLED -> shizuku.showDialog(status)
        }
    }

    /**
     * Обработка результата шага Simple Mode.
     *
     * ИЗМЕНЕНИЕ (оптимизация скорости):
     * Убраны ретраи. Ранее при неудаче шаг повторялся до maxAttempts раз,
     * что удваивало время (2 × 16с = 32с на шаг). Теперь при неудаче
     * сразу фиксируем финальный фейл и переходим к следующему шагу.
     *
     * Экономия: ~16с на каждый неудачный шаг.
     * При 7 фейлах из логов: 7 × 16с = ~112с (~2 минуты).
     */
    fun onSimpleStepResult(success: Boolean) {
        AppLog.i(TAG, "onSimpleStepResult: success=$success, attempt=$stepAttempt")
        val step = _state.value.simpleStep ?: return
        val attempt = stepAttempt
        stepAttempt = 1  // Сбрасываем сразу для следующего шага

        if (success) {
            simpleController.onStepResult(true, attempt = attempt)
            return
        }

        // Без ретрая: сразу финальный фейл и переход к следующему шагу
        AppLog.e(TAG, "onSimpleStepResult: step '${step.step.id}' failed, moving to next")
        failedSimpleStepIds.add(step.step.id)
        simpleController.onStepResult(
            success = false,
            attempt = attempt,
            finalFailure = true
        )
    }

    /**
     * Обычный результат шага: учёт notif-тумблеров, «уже выключено» и переход дальше.
     * Отделено от bridge-лямбды, потому что причина `overlay_permission_lost` обрывает
     * прогон целиком, а не переводит его к следующему шагу (см. init).
     */
    private fun handleSimpleStepResult(success: Boolean, reason: String) {
        // already_off/already_done — тумблер не трогали, откат эти шаги не касается.
        if (success && (reason == "toggled" || reason == "confirmed" || reason == "tapped_fallback")) {
            val stepId = _state.value.simpleStep?.step?.id
            if (stepId != null) {
                // Экран результатов: что именно отключено по уведомлениям.
                simpleController.noteNotifToggled(stepId)
                viewModelScope.launch {
                    prefs.addSimpleToggledStep(stepId)
                    prefs.setHiddenSettingsApplied(true)
                }
            }
        }
        // Уже выключено: состояние проверено, менять нечего — показываем отдельным
        // разделом на экране результатов, чтобы пользователь знал фактическую картину.
        if (success && (reason == "already_off" || reason == "already_done")) {
            _state.value.simpleStep?.step?.id?.let { simpleController.noteAlreadyOff(it) }
        }
        onSimpleStepResult(success)
    }

    /**
     * Альтернативный показ итога, когда оверлея нет (потеря разрешения «Поверх других
     * окон»): окно результата нарисовать нельзя, поэтому активность приложения
     * поднимается на передний план — состояние DONE уже рендерит экран результатов.
     */
    private fun showResultWithoutOverlay() {
        AppLog.w(TAG, "overlay dead — bringing MainActivity to show the run result")
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure { AppLog.w(TAG, "cannot bring MainActivity: ${it.message}") }
    }

    @VisibleForTesting
    internal fun advanceToNextStep(autoStart: Boolean) {
        simpleController.nextStep()
        if (autoStart && _state.value.simpleStep != null) {
            stepAttempt = 1
            simpleController.startCurrentStep()
        }
    }

    fun closeSimpleMode() {
        AppLog.i(TAG, "closeSimpleMode")
        autoFlowJob?.cancel()
        AdbEnablerService.instance?.cancelRunner()
        simpleController.cancelAndReset()
        ChainFlags.reset()
        viewModelScope.launch { prefs.setPendingSimpleMode(false) }
        update {
            it.copy(
                simpleStep = null,
                simpleDone = null,
                simpleModePhase = SimpleModePhase.INACTIVE,
                permissionSubPhase = PermissionSubPhase.INACTIVE,
                simpleModeActive = false,
                showLevelDialog = false,
                showLevelConfirm = false,
                showAccessibilityDialog = false,
                showOverlayDialog = false,
                showRestrictedDialog = false,
                showAppInfoDialog = false,
                showLocationDialog = false,
                showPermissionFallbackDialog = false,
                stuckPhase = null,
                showRestrictedSettingsScreen = false,
                showBatteryDialog = false
            )
        }
    }

    /**
     * Возобновление Простого режима после переустановки (pending из DataStore).
     *
     * [wasStopped] — активность реально уходила из приложения (был `onStop`). Ложный
     * resume (`wasStopped = false`: системный диалог MIUI поверх либо наш собственный
     * `startActivity` в установщик) ожидание предшага НЕ снимает — иначе оптимизация
     * стартует до переустановки (прогон rmvb6atul: 0 onPause/onStop за прогон, resume
     * через 76 мс после `self apk installer opened`).
     */
    fun tryResumePendingSimpleMode(wasStopped: Boolean = false) {
        viewModelScope.launch {
            val waiting = _state.value.installerPreStepWaiting
            if (waiting && !wasStopped) {
                AppLog.i(TAG, "installer pre-step: waiting kept on false resume")
                return@launch
            }
            if (!prefs.getPendingSimpleMode()) {
                // Ожидание живо, а pending нет: право на установку выдавали в настройках —
                // предшаг не доведён. Повторяем проход по реальному возврату.
                if (waiting) {
                    AppLog.i(TAG, "installer pre-step: retry after return without pending")
                    runInstallerPreStep()
                }
                return@launch
            }
            // Гейт установки: pending от предшага восстанавливается только когда установка
            // ФАКТИЧЕСКИ состоялась (lastUpdateTime пакета новее момента открытия установщика).
            // openedAt == 0 — штатный старт: он возобновляется как раньше, иначе рестарт
            // посреди прогона перестал бы восстанавливаться.
            val openedAt = prefs.getSelfApkOpenedAt()
            if (openedAt > 0L) {
                val lastUpdate = SelfApkExporter.lastUpdateTimeMs(app)
                val installed = SelfApkExporter.installHappenedAfterOpened(lastUpdate, openedAt)
                // Дельту пишем в СЕКУНДАХ: 13-значные timestamp уезжали в лог как «***-***-**-**»
                // (LogMasker.phoneRegex), и гейт установки нельзя было сверить по логу.
                AppLog.i(
                    TAG,
                    "installer pre-step: resume gate installed=$installed " +
                        "lastUpdateAfterOpenSec=${(lastUpdate - openedAt) / 1000}"
                )
                if (!installed) {
                    // Установки не было — предшаг не подтверждён: ожидание снимается, старта нет.
                    update { it.copy(installerPreStepWaiting = false) }
                    clearInstallerPreStepState()
                    return@launch
                }
            }
            if (_state.value.simpleModeActive) {
                // Режим уже идёт: ожидание установщика больше не актуально. Лог — под гейтом
                // состояния: ветка срабатывает на КАЖДОМ возврате в приложение во время прогона.
                if (_state.value.installerPreStepWaiting) {
                    AppLog.i(TAG, "installer pre-step: waiting cancelled on resume")
                }
                update { it.copy(installerPreStepWaiting = false) }
                return@launch
            }
            val component = ComponentName(app, AdbEnablerService::class.java).flattenToString()
            val acc = Settings.Secure.getString(
                app.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(component) == true
            val overlay = Settings.canDrawOverlays(app)
            if (!acc || !overlay) {
                AppLog.i(TAG, "tryResumePendingSimpleMode: permissions incomplete, keep pending")
                return@launch
            }
            AppLog.i(TAG, "tryResumePendingSimpleMode: restoring Simple Mode after process restart")
            // Возврат после отменённой установки: ожидание снимается по resume, и это
            // обязано быть видно — «снялось» и «не пыталось» иначе неотличимы.
            if (_state.value.installerPreStepWaiting) {
                AppLog.i(TAG, "installer pre-step: waiting cancelled on resume")
            }
            update { it.copy(installerPreStepWaiting = false) }
            // Решение принято (установка подтверждена и режим стартует): момент открытия
            // установщика снимается — следующий рестарт не должен проходить гейт установки.
            clearInstallerPreStepState()
            prefs.setPendingSimpleMode(true)
            failedSimpleStepIds.clear()
            stepAttempt = 1
            simpleController.start()
        }
    }

    @VisibleForTesting
    fun getFailedStepIds(): List<String> = failedSimpleStepIds.toList()

    @VisibleForTesting
    fun startCurrentSimpleStep() = simpleController.startCurrentStep()

    @VisibleForTesting
    fun nextSimpleStep() = onSimpleStepResult(true)

    @VisibleForTesting
    fun skipSimpleStep() {
        val stepId: String = _state.value.simpleStep?.step?.id ?: "unknown"
        if (!failedSimpleStepIds.contains(stepId)) failedSimpleStepIds.add(stepId)
        stepAttempt = 1
        simpleController.onStepSkipped(stepId)
    }

    @VisibleForTesting
    fun retrySimpleStep() = simpleController.retryStep()

    fun appInfoDialogAgreed() = simpleController.onAppInfoDialogAgreed()
    fun appInfoDialogCancelled() = simpleController.onAppInfoDialogCancelled()
    fun onLocationChosen(location: RestrictedLocation) =
        simpleController.onLocationChosen(location)

    fun onLocationDialogCancelled() = simpleController.onLocationDialogCancelled()
    fun onRestrictedScreenOpenSettings() = simpleController.onRestrictedScreenOpenSettings()
    fun onRestrictedScreenDone() = simpleController.onRestrictedScreenDone()
    fun onRestrictedScreenCancelled() = simpleController.onRestrictedScreenCancelled()
    fun onBatteryDialogAgreed() = simpleController.onBatteryDialogAgreed()
    fun onBatteryDialogSkipped() = simpleController.onBatteryDialogSkipped()
    fun onPermissionFallbackRetry() = simpleController.onFallbackRetry()
    fun onPermissionFallbackOpenSettings() = simpleController.onFallbackOpenSettings()

    fun onPermissionFallbackCancelled() {
        simpleController.onFallbackCancelled()
    }

    fun dialogAgreed() {
        AppLog.i(TAG, "dialogAgreed, simpleModeActive=${_state.value.simpleModeActive}")
        if (_state.value.simpleModeActive) {
            val currentState: MainUiState = _state.value
            if (currentState.showAccessibilityDialog) {
                update { it.copy(accessibilityAttempts = it.accessibilityAttempts + 1) }
            } else if (currentState.showOverlayDialog) {
                update { it.copy(overlayAttempts = it.overlayAttempts + 1) }
            }
            simpleController.onDialogAgreed()
            return
        }
        proFlow.dialogAgreed()
    }

    fun dialogCancelled() {
        AppLog.i(TAG, "dialogCancelled, simpleModeActive=${_state.value.simpleModeActive}")
        if (_state.value.simpleModeActive) {
            simpleController.onDialogCancelled()
            return
        }
        proFlow.dialogCancelled()
    }

    fun restrictedDialogAgreed() {
        AppLog.i(TAG, "restrictedDialogAgreed")
        if (_state.value.simpleModeActive) {
            simpleController.onRestrictedDialogAgreed()
            return
        }
        proFlow.restrictedDialogAgreed()
    }

    fun restrictedDialogCancelled() {
        AppLog.i(TAG, "restrictedDialogCancelled")
        if (_state.value.simpleModeActive) {
            simpleController.onRestrictedDialogCancelled()
            return
        }
        proFlow.restrictedDialogCancelled()
    }

    fun optionsDialogConfirmed() {
        AppLog.i(
            TAG,
            "optionsDialogConfirmed, dnsFilter=${_state.value.dnsFilterEnabled}"
        )
        update { it.copy(showOptionsDialog = false) }
        if (_state.value.dnsFilterEnabled) {
            viewModelScope.launch {
                val seen: Boolean = prefs.hasSeenDnsWarning.first()
                if (!seen) {
                    AppLog.i(TAG, "showing DNS warning dialog")
                    update { it.copy(showDnsWarningDialog = true) }
                    return@launch
                }
                proFlow.proceedToChain()
            }
        } else {
            proFlow.proceedToChain()
        }
    }

    fun dnsWarningAccepted() {
        AppLog.i(TAG, "dnsWarningAccepted")
        update { it.copy(showDnsWarningDialog = false) }
        viewModelScope.launch { prefs.setHasSeenDnsWarning(true) }
        proFlow.proceedToChain()
    }

    fun dnsWarningDeclined() {
        AppLog.i(TAG, "dnsWarningDeclined — disabling DNS")
        update { it.copy(showDnsWarningDialog = false, dnsFilterEnabled = false) }
        viewModelScope.launch { prefs.setDnsFilterEnabled(false) }
        proFlow.proceedToChain()
    }

    fun optionsDialogCancelled() {
        AppLog.i(TAG, "optionsDialogCancelled")
        update { it.copy(showOptionsDialog = false) }
    }

    fun toggleDnsFilter(enabled: Boolean) {
        AppLog.i(TAG, "toggleDnsFilter: $enabled")
        update { it.copy(dnsFilterEnabled = enabled) }
        viewModelScope.launch { prefs.setDnsFilterEnabled(enabled) }
    }

    /**
     * Прозрачность notif_*: OFF исключает шаги отключения уведомлений из плана
     * (PlanBuilder читает настройку при построении плана).
     */
    fun toggleNotifTransparency(enabled: Boolean) {
        AppLog.i(TAG, "toggleNotifTransparency: $enabled")
        update { it.copy(notifTransparency = enabled) }
        simpleController.setNotifTransparency(enabled)
        viewModelScope.launch { prefs.setNotifTransparency(enabled) }
    }

    /**
     * Скрытый вход в FULL-диагностику release-сборки: 7 тапов по версии в меню
     * подряд (окно [VERSION_TAP_WINDOW_MS]). Пишет diagLevelOverride=FULL.
     */
    fun onVersionTapped() {
        val now = System.currentTimeMillis()
        if (versionTapCount == 0 || now - versionTapFirstMs > VERSION_TAP_WINDOW_MS) {
            versionTapCount = 0
            versionTapFirstMs = now
        }
        versionTapCount++
        AppLog.i(TAG, "version tapped: $versionTapCount/$VERSION_TAPS_FOR_FULL")
        if (versionTapCount < VERSION_TAPS_FOR_FULL) return
        versionTapCount = 0
        viewModelScope.launch {
            prefs.setDiagLevelOverride(DiagnosticSnapshotManager.DiagnosticLevel.FULL.name)
            DiagnosticSnapshotManager.setLevel(DiagnosticSnapshotManager.DiagnosticLevel.FULL, "override")
            update { it.copy(diagLevelFull = true) }
            AppLog.i(TAG, "diag unlocked: level=FULL source=override")
        }
    }

    fun shizukuDialogInstall() = shizuku.dialogInstall()
    fun shizukuDialogOpenApp() = shizuku.dialogOpenApp()
    fun wizardSkip() = shizuku.wizardSkip()
    fun requestShizukuPermission() = shizuku.requestPermission(SHIZUKU_PERMISSION_CODE)
    fun onShizukuPermissionResult(granted: Boolean) = shizuku.onPermissionResult(granted)
    fun wizardCheck() = shizuku.wizardCheck()
    fun openShizukuSources() = shizuku.openSources()
    fun closeShizukuSources() = shizuku.closeSources()
    fun installFromSource(source: String) = shizuku.installFromSource(source)
    fun shizukuDialogLater() = shizuku.dialogLater()
    fun closeShizukuWizard() = shizuku.closeWizard()

    fun restoreOptimization() = proFlow.restoreOptimization()
    fun confirmReboot() = proFlow.confirmReboot()
    fun requestReboot() = proFlow.requestReboot()
    fun dismissRebootDialog() = proFlow.dismissRebootDialog()
    fun dismissRebootFailed() = proFlow.dismissRebootFailed()
    fun dismissRestoreFailed() = proFlow.dismissRestoreFailed()

    @VisibleForTesting
    fun devModeDialogOpenAbout() = AppLog.i(TAG, "devModeDialog: open about phone")

    fun devModeDialogRetry() = proFlow.devModeDialogRetry()
    fun devModeDialogCancel() = proFlow.devModeDialogCancel()
    fun dismissFinalDialog() = update { it.copy(showFinalDialog = false) }

    fun openAccessibilitySettings() {
        AppLog.i(TAG, "openAccessibilitySettings: delegating to PermissionFlowManager")
        permissionFlow.openAccessibilityWithHint()
    }

    fun openOverlaySettings() {
        AppLog.i(TAG, "openOverlaySettings: delegating to PermissionFlowManager")
        permissionFlow.openOverlayWithPointer()
    }

    private fun openAppInfoWithHint() {
        AppLog.i(TAG, "auto-redirect: opening app info with hint card")
        permissionFlow.openAppInfoWithSmartPointer(RestrictedLocation.UNKNOWN)
    }

    private fun openAccessibilityWithHint() {
        AppLog.i(TAG, "auto-redirect: opening accessibility services with hint card")
        permissionFlow.openAccessibilityWithHint()
    }
}
