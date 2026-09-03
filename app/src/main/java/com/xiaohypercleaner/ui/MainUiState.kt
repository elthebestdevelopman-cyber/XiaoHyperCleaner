package com.xiaohypercleaner.ui

import com.xiaohypercleaner.data.OptimizationMode
import com.xiaohypercleaner.data.PermissionSubPhase
import com.xiaohypercleaner.data.ShizukuExecutor
import com.xiaohypercleaner.data.SimpleModePhase
import com.xiaohypercleaner.data.SimpleStepState

/**
 * Всё состояние главного экрана.
 * Вынесено из MainViewModel, чтобы VM оставался тонким фасадом над делегатами.
 *
 * Структура:
 * - Основные флаги (isOptimized, isWorking)
 * - Разрешения (accessibility, overlay)
 * - Диалоги (show*Dialog)
 * - Shizuku (wizard, sources, status)
 * - Simple Mode (phase, step, done)
 * - Pro Mode (final dialog, report)
 */
data class MainUiState(
    // ═══════════════════════════════════════════════════════════════
    // Основные флаги
    // ═══════════════════════════════════════════════════════════════

    /** Оптимизация уже выполнена */
    val isOptimized: Boolean = false,

    /**
     * Можно ли автоматически перезагрузить устройство (только root).
     * Без root кнопка «Перезагрузить» скрыта — показываем подсказку вручную.
     */
    val canAutoReboot: Boolean = false,

    /** Сейчас выполняется оптимизация (Simple или Pro) */
    val isWorking: Boolean = false,

    /** Прогресс выполнения (0.0 - 1.0) */
    val progress: Float = 0f,

    // ═══════════════════════════════════════════════════════════════
    // Разрешения
    // ═══════════════════════════════════════════════════════════════

    /** Accessibility Service включён */
    val isAccessibilityEnabled: Boolean = false,

    /** Overlay permission предоставлено */
    val isOverlayGranted: Boolean = false,

    /** Предыдущее состояние accessibility (для определения изменений) */
    val previousAccessibility: Boolean = false,

    /** Предыдущее состояние overlay (для определения изменений) */
    val previousOverlay: Boolean = false,

    // ═══════════════════════════════════════════════════════════════
    // Диалоги разрешений
    // ═══════════════════════════════════════════════════════════════

    /** Диалог запроса Accessibility */
    val showAccessibilityDialog: Boolean = false,

    /** Диалог запроса Overlay */
    val showOverlayDialog: Boolean = false,

    /** Диалог Restricted Settings (Android 13+) */
    val showRestrictedDialog: Boolean = false,

    /** Диалог App Info (для разблокировки restricted) */
    val showAppInfoDialog: Boolean = false,

    /** Диалог выбора местоположения кнопки restricted */
    val showLocationDialog: Boolean = false,

    /** Fallback диалог при зависании на фазе */
    val showPermissionFallbackDialog: Boolean = false,

    /** Застрявшая фаза (для retry) */
    val stuckPhase: PermissionSubPhase? = null,

    /** Экран Restricted Settings открыт */
    val showRestrictedSettingsScreen: Boolean = false,

    /** Был ли показан экран Restricted Settings */
    val restrictedSettingsShown: Boolean = false,

    /** Диалог Battery Optimization */
    val showBatteryDialog: Boolean = false,

    /** Диалог неудачного test click */
    val showTestClickFailedDialog: Boolean = false,

    // ═══════════════════════════════════════════════════════════════
    // Попытки запроса разрешений
    // ═══════════════════════════════════════════════════════════════

    /** Количество попыток включения Accessibility */
    val accessibilityAttempts: Int = 0,

    /** Количество попыток получения Overlay */
    val overlayAttempts: Int = 0,

    /** Количество попыток открытия App Info */
    val appInfoAttempts: Int = 0,

    // ═══════════════════════════════════════════════════════════════
    // Опции оптимизации
    // ═══════════════════════════════════════════════════════════════

    /** Диалог опций (DNS filter, aggressive mode) */
    val showOptionsDialog: Boolean = false,

    /** DNS filter включён */
    val dnsFilterEnabled: Boolean = false,

    /** Aggressive mode включён */
    val aggressiveMode: Boolean = false,

    /** Предупреждение о DNS filter */
    val showDnsWarningDialog: Boolean = false,

    // ═══════════════════════════════════════════════════════════════
    // Выбор уровня
    // ═══════════════════════════════════════════════════════════════

    /** Диалог выбора уровня (Simple/Pro) */
    val showLevelDialog: Boolean = false,

    /** Диалог подтверждения выбора уровня */
    val showLevelConfirm: Boolean = false,

    /** Выбранный уровень */
    val selectedLevel: OptimizationMode? = null,

    // ═══════════════════════════════════════════════════════════════
    // Shizuku (Pro Mode)
    // ═══════════════════════════════════════════════════════════════

    /** Диалог установки Shizuku */
    val showShizukuDialog: Boolean = false,

    /** Статус Shizuku */
    val shizukuStatus: ShizukuExecutor.Status = ShizukuExecutor.Status.NOT_INSTALLED,

    /** Диалог выбора источников установки Shizuku */
    val showShizukuSources: Boolean = false,

    /** Wizard настройки Shizuku */
    val showShizukuWizard: Boolean = false,

    /** Сообщение проверки Shizuku */
    val shizukuCheckMessage: String? = null,

    /** Диалог требования Dev Mode */
    val showDevModeDialog: Boolean = false,

    // ═══════════════════════════════════════════════════════════════
    // Simple Mode
    // ═══════════════════════════════════════════════════════════════

    /** Simple Mode активен */
    val simpleModeActive: Boolean = false,

    /** Текущая фаза Simple Mode */
    val simpleModePhase: SimpleModePhase = SimpleModePhase.INACTIVE,

    /** Подфаза запроса разрешений */
    val permissionSubPhase: PermissionSubPhase = PermissionSubPhase.INACTIVE,

    /** Текущий шаг Simple Mode */
    val simpleStep: SimpleStepState? = null,

    /** Финальный результат Simple Mode (completed, total) */
    val simpleDone: Pair<Int, Int>? = null,

    // ═══════════════════════════════════════════════════════════════
    // Pro Mode результаты
    // ═══════════════════════════════════════════════════════════════

    /** Финальный диалог результатов */
    val showFinalDialog: Boolean = false,

    /** Оптимизация успешна */
    val optimizationSuccess: Boolean = false,

    /** Текст финального отчёта */
    val finalReport: String = "",

    /** Диалог запроса перезагрузки */
    val showRebootDialog: Boolean = false,

    /** Перезагрузка не удалась */
    val rebootFailed: Boolean = false,

    /** Восстановление не удалось */
    val restoreFailed: Boolean = false
) {
    // ═══════════════════════════════════════════════════════════════
    // Convenience методы
    // ═══════════════════════════════════════════════════════════════

    /** Проверяет, выполняется ли Simple Mode */
    val isSimpleModeWorking: Boolean
        get() = simpleModeActive && isWorking

    /** Проверяет, выполняется ли Pro Mode */
    val isProModeWorking: Boolean
        get() = !simpleModeActive && isWorking

    /** Проверяет, открыт ли какой-либо диалог */
    val hasOpenDialog: Boolean
        get() = showAccessibilityDialog || showOverlayDialog || showRestrictedDialog ||
                showAppInfoDialog || showLocationDialog || showPermissionFallbackDialog ||
                showBatteryDialog || showOptionsDialog || showDnsWarningDialog ||
                showLevelDialog || showLevelConfirm || showShizukuDialog ||
                showShizukuSources || showShizukuWizard || showDevModeDialog ||
                showFinalDialog || showRebootDialog

    /** Возвращает количество успешных шагов Simple Mode */
    val simpleCompletedCount: Int
        get() = simpleStep?.completedCount ?: simpleDone?.first ?: 0
}