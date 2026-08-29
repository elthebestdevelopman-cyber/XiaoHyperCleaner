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
     * Открывает App Info с умной подсказкой о местоположении restricted settings.
     *
     * @param location Местоположение кнопки restricted settings (TOP_MENU, BOTTOM_LIST, UNKNOWN, ABSENT)
     */
    fun openAppInfoWithSmartPointer(location: RestrictedLocation) {
        AppLog.i(TAG, "openAppInfoWithSmartPointer: location=$location")
        openAppInfoSettings()

        if (!canShowOverlay()) {
            AppLog.w(TAG, "openAppInfoWithSmartPointer: no overlay permission, skipping hint")
            return
        }

        when (location) {
            RestrictedLocation.TOP_MENU -> showPointer(
                OverlayService.PointerMode.TOP_RIGHT,
                context.getString(R.string.pointer_restricted_top_hint)
            )

            RestrictedLocation.BOTTOM_LIST -> showPointer(
                OverlayService.PointerMode.BOTTOM_LIST,
                context.getString(R.string.pointer_restricted_bottom_hint)
            )

            RestrictedLocation.UNKNOWN -> showGenericCard(
                context.getString(R.string.pointer_restricted_generic)
            )

            RestrictedLocation.ABSENT -> {
                AppLog.i(TAG, "restricted location marked ABSENT — skipping hint")
            }
        }
    }

    /**
     * Открывает Accessibility settings с визуальной подсказкой (стрелка на сервис).
     */
    fun openAccessibilityWithPointer() {
        AppLog.i(TAG, "openAccessibilityWithPointer: showing visual hint")
        openAccessibilitySettings()

        if (!canShowOverlay()) {
            AppLog.w(TAG, "openAccessibilityWithPointer: no overlay permission, skipping pointer")
            return
        }

        OverlayController.showManualPointer(
            context,
            OverlayService.PointerMode.LIST_ITEM_CENTER.name,
            context.getString(R.string.pointer_hint_accessibility)
        )
    }

    /**
     * Открывает Overlay settings с визуальной подсказкой (стрелка внизу).
     */
    fun openOverlayWithPointer() {
        AppLog.i(TAG, "openOverlayWithPointer: showing visual hint")
        openOverlaySettings()

        if (!canShowOverlay()) {
            AppLog.w(TAG, "openOverlayWithPointer: no overlay permission, skipping pointer")
            return
        }

        OverlayController.showManualPointer(
            context,
            OverlayService.PointerMode.GENERIC_BOTTOM.name,
            context.getString(R.string.pointer_hint_overlay)
        )
    }

    /**
     * Открывает Accessibility settings с текстовой подсказкой (hint bubble).
     */
    fun openAccessibilityWithHint() {
        AppLog.i(TAG, "openAccessibilityWithHint: showing text hint")
        openAccessibilitySettings()

        if (!canShowOverlay()) {
            AppLog.w(TAG, "openAccessibilityWithHint: no overlay permission, skipping hint")
            return
        }

        showHint(context.getString(R.string.hint_accessibility))
    }

    /**
     * Открывает Battery optimization settings с текстовой подсказкой.
     */
    fun openBatteryOptimizationWithPointer() {
        AppLog.i(TAG, "openBatteryOptimizationWithPointer: showing hint")
        openBatteryOptimizationSettings()

        if (!canShowOverlay()) {
            AppLog.w(
                TAG,
                "openBatteryOptimizationWithPointer: no overlay permission, skipping hint"
            )
            return
        }

        showHint(context.getString(R.string.hint_battery_optimization))
    }

    /**
     * Показывает текстовую подсказку (hint bubble) через OverlayService.
     */
    fun showHint(text: String) {
        if (!canShowOverlay()) {
            AppLog.w(TAG, "showHint: no overlay permission, skipping")
            return
        }

        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HINT
                putExtra(OverlayService.EXTRA_HINT, text)
            }
            context.startService(intent)
            AppLog.i(TAG, "showHint: success")
        } catch (e: Exception) {
            AppLog.w(TAG, "showHint failed: ${e.message}")
        }
    }

    /**
     * Показывает универсальную карточку с текстом (GENERIC_BOTTOM mode).
     */
    fun showGenericCard(text: String) {
        if (!canShowOverlay()) {
            AppLog.w(TAG, "showGenericCard: no overlay permission, skipping")
            return
        }

        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_POINTER
                putExtra(
                    OverlayService.EXTRA_POINTER_MODE,
                    OverlayService.PointerMode.GENERIC_BOTTOM.name
                )
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
            }
            context.startService(intent)
            AppLog.i(TAG, "showGenericCard: success")
        } catch (e: Exception) {
            AppLog.w(TAG, "showGenericCard failed: ${e.message}")
        }
    }

    /**
     * Показывает стрелку-указатель в указанном режиме.
     */
    fun showPointer(mode: OverlayService.PointerMode, text: String) {
        if (!canShowOverlay()) {
            AppLog.w(TAG, "showPointer: no overlay permission, skipping")
            return
        }

        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_POINTER
                putExtra(OverlayService.EXTRA_POINTER_MODE, mode.name)
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
            }
            context.startService(intent)
            AppLog.i(TAG, "showPointer: mode=${mode.name}, success")
        } catch (e: Exception) {
            AppLog.w(TAG, "showPointer failed: ${e.message}")
        }
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