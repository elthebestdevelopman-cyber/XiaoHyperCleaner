package com.xiaohypercleaner.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.service.SystemAutomationService
import com.xiaohypercleaner.util.AppLog

class PermissionFlowManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionFlow"

        /**
         * ВАЖНО: Play Store = com.android.vending.
         * com.google.android.packageinstaller = AOSP-установщик (Telegram, браузер, проводник).
         * Это частая ошибка — их путают.
         */
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

    /**
     * ИСПРАВЛЕНО: sideload = ВСЁ, что не Play Store.
     * Ранее коммиттер AOSP (com.google.android.packageinstaller) ошибочно считался
     * "trusted", из-за чего Android 13+ не разблокировал Restricted Settings.
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

    fun needsRestrictedUnlock(): Boolean =
        Build.VERSION.SDK_INT >= 33 && isSideloadedOnAndroid13Plus()

    /**
     * Проверяет, снято ли ограничение Battery Optimization для нашего приложения.
     * HyperOS агрессивно убивает Accessibility Service, поэтому это критично.
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

    fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "openOverlaySettings failed: ${e.message}")
        }
    }

    fun openAccessibilitySettings() {
        val component = ComponentName(context, AdbEnablerService::class.java).flattenToString()
        val deep = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val args = android.os.Bundle()
        args.putString("componentName", component)
        deep.putExtra(
            ":settings:show_fragment",
            "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
        )
        deep.putExtra(":settings:show_fragment_args", args)

        try {
            deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(deep)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                AppLog.w(TAG, "openAccessibilitySettings failed: ${e2.message}")
            }
        }
    }

    fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "openAppInfoSettings failed: ${e.message}")
        }
    }

    /**
     * Открывает экран Battery Optimization для нашего приложения.
     * Используем несколько intent'ов с fallback:
     * 1. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (стандартный, требует пермишен)
     * 2. ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (общий экран)
     * 3. MIUI-specific: miui.intent.action.POWER_HIDE_MODE_APP_LIST
     * 4. Fallback: App Info (пользователь найдет "Экономия заряда" вручную)
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings() {
        val pkgUri = "package:${context.packageName}".toUri()

        // Попытка 1: стандартный intent для конкретного приложения
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

        // Попытка 2: общий экран Battery Optimization
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

        // Попытка 3: MIUI-specific
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

        // Fallback: App Info
        openAppInfoSettings()
    }

    // ═══════════════════════════════════════════════════════════════
    // Overlay-подсказки
    // ═══════════════════════════════════════════════════════════════

    private fun canShowOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun openAppInfoWithSmartPointer(location: RestrictedLocation) {
        openAppInfoSettings()
        if (!canShowOverlay()) return

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

    fun openAccessibilityWithPointer() {
        openAccessibilitySettings()
        if (!canShowOverlay()) return
        showPointer(
            OverlayService.PointerMode.LIST_ITEM_CENTER,
            context.getString(R.string.pointer_accessibility_item)
        )
    }

    fun openOverlayWithPointer() {
        openOverlaySettings()
        if (!canShowOverlay()) return
        showPointer(
            OverlayService.PointerMode.SWITCH_RIGHT,
            context.getString(R.string.pointer_overlay_switch)
        )
    }

    fun openAccessibilityWithHint() {
        openAccessibilitySettings()
        if (!canShowOverlay()) return
        showHint(context.getString(R.string.hint_accessibility))
    }

    /**
     * НОВОЕ: подсказка для BATTERY_OPTIMIZATION.
     * Показывается после открытия экрана Battery Optimization.
     */
    fun openBatteryOptimizationWithPointer() {
        openBatteryOptimizationSettings()
        if (!canShowOverlay()) return
        showHint(context.getString(R.string.hint_battery_optimization))
    }

    fun showHint(text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_HINT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showHint failed: ${e.message}")
        }
    }

    fun showGenericCard(text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(
                    OverlayService.EXTRA_POINTER_MODE,
                    OverlayService.PointerMode.GENERIC_BOTTOM.name
                )
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
                putExtra(OverlayService.EXTRA_POINTER_HINT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showGenericCard failed: ${e.message}")
        }
    }

    fun showPointer(mode: OverlayService.PointerMode, text: String, hint: String? = null) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_POINTER_MODE, mode.name)
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
                if (hint != null) putExtra(OverlayService.EXTRA_POINTER_HINT, hint)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showPointer failed: ${e.message}")
        }
    }

    fun hideOverlay() {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (e: Exception) {
            AppLog.w(TAG, "hideOverlay failed: ${e.message}")
        }
    }
}