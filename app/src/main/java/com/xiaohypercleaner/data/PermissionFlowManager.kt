package com.xiaohypercleaner.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.xiaohypercleaner.service.ArrowPosition
import com.xiaohypercleaner.service.InteractiveHint
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.util.AppLog

/**
 * Управляет потоком запроса разрешений: Accessibility, Overlay, Restricted Settings.
 * Вынесен из MainViewModel для уменьшения размера god-object.
 *
 * Положен в пакет `data` вместе с SimpleSteps и RootExecutor.
 */
class PermissionFlowManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "PermissionFlowManager"
    }

    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened accessibility settings")
            // Показываем интерактивную подсказку
            OverlayController.showInteractiveHint(
                InteractiveHint(
                    text = "Найдите «XiaoHyperCleaner» и включите его",
                    targetRect = null,  // Будет найдено автоматически
                    arrowPosition = ArrowPosition.BOTTOM
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open accessibility settings", e)
        }
    }

    /**
     * Открывает настройки accessibility с подсветкой нашего сервиса.
     * На MIUI deep link может не сработать — fallback на обычный экран.
     */
    fun openAccessibilityWithHint() {
        try {
            val component = "${context.packageName}/${context.packageName}.service.AdbEnablerService"
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", component)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened accessibility with hint")
            // Показываем интерактивную подсказку
            OverlayController.showInteractiveHint(
                InteractiveHint(
                    text = "Включите «XiaoHyperCleaner»",
                    targetRect = null,
                    arrowPosition = ArrowPosition.BOTTOM
                )
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Hint deep link failed, falling back: ${e.message}")
            openAccessibilitySettings()
        }
    }

    fun openOverlaySettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened overlay settings")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open overlay settings", e)
        }
    }

    fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened app info settings")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open app info settings", e)
        }
    }

    fun openDeveloperOptions() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened developer options")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open developer options", e)
        }
    }

    fun openDeviceInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened device info settings")
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open device info settings", e)
        }
    }
}