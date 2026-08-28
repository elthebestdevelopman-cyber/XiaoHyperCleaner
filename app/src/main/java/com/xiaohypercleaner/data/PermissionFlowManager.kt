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

class PermissionFlowManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionFlow"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

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

    // ═══ Открытие системных экранов ═══

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

    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings() {
        val pkgUri = "package:${context.packageName}".toUri()
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
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openBatteryOptimizationSettings: IGNORE_SETTINGS failed: ${e.message}")
        }
        try {
            val intent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            AppLog.w(TAG, "openBatteryOptimizationSettings: MIUI intent failed: ${e.message}")
        }
        openAppInfoSettings()
    }

    // ═══ Оверлей-подсказки ═══

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
        AppLog.i(TAG, "openAccessibilityWithPointer: showing visual hint")
        openAccessibilitySettings()
        if (!canShowOverlay()) return
        OverlayController.showManualPointer(
            context,
            OverlayService.PointerMode.LIST_ITEM_CENTER.name,
            context.getString(R.string.pointer_hint_accessibility)
        )
    }

    fun openOverlayWithPointer() {
        AppLog.i(TAG, "openOverlayWithPointer: showing visual hint")
        openOverlaySettings()
        if (!canShowOverlay()) return
        OverlayController.showManualPointer(
            context,
            OverlayService.PointerMode.GENERIC_BOTTOM.name,
            context.getString(R.string.pointer_hint_overlay)
        )
    }

    fun openAccessibilityWithHint() {
        openAccessibilitySettings()
        if (!canShowOverlay()) return
        showHint(context.getString(R.string.hint_accessibility))
    }

    fun openBatteryOptimizationWithPointer() {
        openBatteryOptimizationSettings()
        if (!canShowOverlay()) return
        showHint(context.getString(R.string.hint_battery_optimization))
    }

    fun showHint(text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HINT
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
                action = OverlayService.ACTION_POINTER
                putExtra(
                    OverlayService.EXTRA_POINTER_MODE,
                    OverlayService.PointerMode.GENERIC_BOTTOM.name
                )
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showGenericCard failed: ${e.message}")
        }
    }

    fun showPointer(mode: OverlayService.PointerMode, text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_POINTER
                putExtra(OverlayService.EXTRA_POINTER_MODE, mode.name)
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showPointer failed: ${e.message}")
        }
    }

    /**
     * ИСПРАВЛЕНО: НЕ stopService()! Асинхронный stopService добивал только что
     * запущенный automation-оверлей (гонка start/stop) — робот «пропадал».
     * Просто убираем окно, сервис остаётся жив.
     */
    fun hideOverlay() {
        OverlayController.hide(context)
    }
}