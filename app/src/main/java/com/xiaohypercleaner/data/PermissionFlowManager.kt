package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.AdbEnablerService
import com.xiaohypercleaner.service.OverlayService
import com.xiaohypercleaner.util.AppLog

class PermissionFlowManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionFlow"
    }

    fun isSideloadedOnAndroid13Plus(): Boolean {
        // Android 13+ (API 33) ввёл блокировку sideload-приложений.
        // На более старых версиях такой блокировки нет — возвращаем false.
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val pm = context.packageManager
            // Здесь SDK_INT гарантированно >= 33, поэтому getInstallSourceInfo (API 30+)
            // доступен без проверок и deprecated-ветка не нужна
            val installer = pm.getInstallSourceInfo(context.packageName).installingPackageName
                ?: "unknown"

            val trustedInstallers = listOf(
                "com.android.vending",
                "com.google.android.packageinstaller",
                "com.android.packageinstaller"
            )
            val sideloaded = installer !in trustedInstallers && installer != "preload"
            AppLog.i(TAG, "isSideloaded: installer=$installer, sideloaded=$sideloaded")
            sideloaded
        } catch (e: Exception) {
            AppLog.w(TAG, "isSideloaded check failed: ${e.message}")
            false
        }
    }

    /**
     * Открывает App Info и показывает подсказку в зависимости от известной позиции пункта.
     * Если позиция UNKNOWN — показываем общую карточку без стрелки.
     * Overlay-подсказки показываем ТОЛЬКО если разрешение уже выдано —
     * иначе OverlayService крашнется с BadTokenException / ViewTreeLifecycleOwner not found.
     */
    fun openAppInfoWithSmartPointer(location: RestrictedLocation) {
        openAppInfoSettings()

        // Overlay-подсказки показываем только если overlay-разрешение выдано.
        // Без этой проверки OverlayService запускается и падает на addView.
        if (!Settings.canDrawOverlays(context)) {
            AppLog.w(TAG, "openAppInfoWithSmartPointer: overlay not granted, skipping pointer")
            return
        }

        when (location) {
            RestrictedLocation.TOP_MENU -> {
                showPointer(
                    mode = OverlayService.PointerMode.TOP_RIGHT,
                    text = context.getString(R.string.pointer_restricted_top_hint)
                )
            }

            RestrictedLocation.BOTTOM_LIST -> {
                showPointer(
                    mode = OverlayService.PointerMode.BOTTOM_LIST,
                    text = context.getString(R.string.pointer_restricted_bottom_hint)
                )
            }

            RestrictedLocation.UNKNOWN -> {
                showGenericCard(context.getString(R.string.pointer_restricted_generic))
            }

            RestrictedLocation.ABSENT -> {
                AppLog.i(TAG, "restricted location marked ABSENT — skipping hint")
            }
        }
    }

    fun openAccessibilityWithPointer() {
        openAccessibilitySettings()

        // Overlay-подсказку показываем только при выданном разрешении
        if (!Settings.canDrawOverlays(context)) {
            AppLog.w(TAG, "openAccessibilityWithPointer: overlay not granted, skipping pointer")
            return
        }

        showPointer(
            mode = OverlayService.PointerMode.LIST_ITEM_CENTER,
            text = context.getString(R.string.pointer_accessibility_item)
        )
    }

    fun openOverlayWithPointer() {
        openOverlaySettings()

        // Здесь overlay ещё точно не выдан (мы только что отправили пользователя в настройки),
        // поэтому pointer показываем ТОЛЬКО после возврата, когда разрешение уже получено.
        // Этот метод вызывается из SimpleModeController перед отправкой в настройки —
        // поэтому pointer всегда пропускается здесь и показывается при возврате
        // через onResumeAfterPermissionReturn() → refresh() → advance() → showPointer.
        // Но для безопасности оставляем проверку:
        if (!Settings.canDrawOverlays(context)) {
            AppLog.w(TAG, "openOverlayWithPointer: overlay not granted yet, skipping pointer")
            return
        }

        showPointer(
            mode = OverlayService.PointerMode.SWITCH_RIGHT,
            text = context.getString(R.string.pointer_overlay_switch)
        )
    }

    fun openAccessibilityWithHint() {
        openAccessibilitySettings()

        // Hint-карточки тоже требуют overlay-разрешения
        if (!Settings.canDrawOverlays(context)) {
            AppLog.w(TAG, "openAccessibilityWithHint: overlay not granted, skipping hint")
            return
        }

        showHint(context.getString(R.string.hint_accessibility))
    }

    fun openOverlaySettings() {
        // minSdk=28, проверка на Build.VERSION_CODES.M (API 23) избыточна — всегда >= M
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
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "showGenericCard failed: ${e.message}")
        }
    }

    fun showPointer(mode: OverlayService.PointerMode, text: String) {
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_POINTER_MODE, mode.name)
                putExtra(OverlayService.EXTRA_POINTER_TEXT, text)
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