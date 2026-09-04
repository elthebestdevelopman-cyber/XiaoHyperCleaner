package com.xiaohypercleaner.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.OverlayController
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.util.AppLog

/**
 * Менеджер потока разрешений для Simple/Pro режимов.
 *
 * Отвечает за:
 * 1. Проверку статуса разрешений (overlay, accessibility, battery optimization)
 * 2. Открытие системных экранов настроек
 * 3. Показ оверлей-подсказок (стрелки, карточки, hints)
 *
 * УЛУЧШЕНИЯ:
 * 1. Проверка canShowOverlay() перед показом pointer/hint
 * 2. Константа для fragment name в accessibility deep link
 * 3. Улучшенное логирование для диагностики
 * 4. Защита от NullPointerException при отсутствии RestrictedLocation
 */
class PermissionFlowManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionFlow"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        // Fragment name для deep link в accessibility settings
        private const val ACCESSIBILITY_FRAGMENT =
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
    }

    /**
     * Проверяет, установлено ли приложение через sideload (не из Play Store).
     * Критично для Android 13+: sideloaded apps требуют restricted settings unlock.
     */
    fun isSideloadedOnAndroid13Plus(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        return try {
            val pm = context.packageManager
            val installer = pm.getInstallSourceInfo(context.packageName).installingPackageName
                ?: "unknown"
            val sideloaded = installer != PLAY_STORE_PACKAGE && installer != "preload"
            AppLog.i(TAG, "isSideloaded: installer=$installer, sideloaded=$sideloaded")
            sideloaded
        } catch (e: Exception) {
            AppLog.w(TAG, "isSideloaded check failed: ${e.message}")
            false
        }
    }

    /**
     * Проверяет, нужна ли разблокировка restricted settings.
     * Актуально только для Android 13+ и sideloaded apps.
     */
    fun needsRestrictedUnlock(): Boolean =
        Build.VERSION.SDK_INT >= 33 && isSideloadedOnAndroid13Plus()

    /**
     * Проверяет, исключено ли приложение из battery optimization.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            AppLog.i(TAG, "isIgnoringBatteryOptimizations=$ignoring")
            ignoring
        } catch (e: Exception) {
            AppLog.w(TAG, "isIgnoringBatteryOptimizations failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Открытие системных экранов
    // ═══════════════════════════════════════════════════════════════

    /**
     * Открывает экран настроек overlay permission.
     */
    fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i(TAG, "openOverlaySettings: success")
        } catch (e: Exception) {
            AppLog.w(TAG, "openOverlaySettings failed: ${e.message}")
        }
    }

    /**
     * Открывает экран настроек accessibility с deep link на конкретный сервис.
     * Использует fragment deep link для прямого перехода к AdbEnablerService.
     */
    fun openAccessibilitySettings() {
        val component = ComponentName(context, AdbEnablerService::class.java).flattenToString()

        // Попытка 1: deep link с fragment
        try {
            val deep = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val args = android.os.Bundle()
            args.putString("componentName", component)
            deep.putExtra(":settings:show_fragment", ACCESSIBILITY_FRAGMENT)
            deep.putExtra(":settings:show_fragment_args", args)
            deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(deep)
            AppLog.i(TAG, "openAccessibilitySettings: deep link success")
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openAccessibilitySettings: deep link failed: ${e.message}")
        }

        // Попытка 2: обычный экран accessibility settings
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            AppLog.i(TAG, "openAccessibilitySettings: fallback success")
        } catch (e2: Exception) {
            AppLog.w(TAG, "openAccessibilitySettings: fallback also failed: ${e2.message}")
        }
    }

    /**
     * Открывает экран информации о приложении.
     */
    fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "openAppInfoSettings: success")
        } catch (e: Exception) {
            AppLog.w(TAG, "openAppInfoSettings failed: ${e.message}")
        }
    }

    /**
     * Открывает экран настроек battery optimization.
     * Использует цепочку fallback: REQUEST_IGNORE → IGNORE_SETTINGS → MIUI intent → App Info.
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings() {
        val pkgUri = "package:${context.packageName}".toUri()

        // Попытка 1: прямой запрос на исключение (Android 6+)
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = pkgUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "openBatteryOptimizationSettings: REQUEST_IGNORE success")
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openBatteryOptimizationSettings: REQUEST_IGNORE failed: ${e.message}")
        }

        // Попытка 2: экран списка исключений
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "openBatteryOptimizationSettings: IGNORE_SETTINGS success")
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openBatteryOptimizationSettings: IGNORE_SETTINGS failed: ${e.message}")
        }

        // Попытка 3: MIUI-specific intent
        try {
            val intent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "openBatteryOptimizationSettings: MIUI intent success")
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openBatteryOptimizationSettings: MIUI intent failed: ${e.message}")
        }

        // Fallback: экран информации о приложении
        AppLog.i(TAG, "openBatteryOptimizationSettings: falling back to App Info")
        openAppInfoSettings()
    }

    // ═══════════════════════════════════════════════════════════════
    // Оверлей-подсказки
    // ═══════════════════════════════════════════════════════════════

    /**
     * Проверяет, есть ли разрешение на показ оверлеев.
     */
    private fun canShowOverlay(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Открывает App Info для разблокировки restricted settings (описание уже показано в диалоге).
     */
    fun openAppInfoWithSmartPointer(location: RestrictedLocation) {
        AppLog.i(TAG, "openAppInfoSettings: location=$location")
        openAppInfoSettings()
    }

    /**
     * Открывает Accessibility settings (описание уже показано в диалоге согласия).
     */
    fun openAccessibilityWithPointer() {
        AppLog.i(TAG, "openAccessibilitySettings")
        openAccessibilitySettings()
    }

    /**
     * Открывает Overlay settings (описание уже показано в диалоге).
     */
    fun openOverlayWithPointer() {
        AppLog.i(TAG, "openOverlaySettings")
        openOverlaySettings()
    }

    /**
     * Открывает Accessibility settings.
     */
    fun openAccessibilityWithHint() {
        AppLog.i(TAG, "openAccessibilitySettings")
        openAccessibilitySettings()
    }

    /**
     * Открывает Battery optimization settings (описание уже показано в диалоге).
     */
    fun openBatteryOptimizationWithPointer() {
        AppLog.i(TAG, "openBatteryOptimizationSettings")
        openBatteryOptimizationSettings()
    }

    /**
     * Подсказки-пузыри поверх сторонних экранов отключены (заменены предварительными диалогами).
     */
    fun showHint(text: String) {
        // No-op
    }

    /**
     * Карточки поверх сторонних экранов отключены (заменены предварительными диалогами).
     */
    fun showGenericCard(text: String) {
        // No-op
    }

    /**
     * Стрелки-указатели удалены по требованию пользователя (заменены предварительными диалогами).
     */
    fun showPointer(mode: OverlayService.PointerMode, text: String) {
        // No-op
    }

    /**
     * Скрывает оверлей через OverlayController (НЕ stopService!).
     *
     * ВАЖНО: Асинхронный stopService() добивал только что запущенный
     * automation-оверлей (гонка start/stop) — робот «пропадал».
     * OverlayController.hide() просто убирает окно, сервис остаётся жив.
     */
    fun hideOverlay() {
        AppLog.i(TAG, "hideOverlay: hiding via OverlayController")
        OverlayController.hide(context)
    }
}