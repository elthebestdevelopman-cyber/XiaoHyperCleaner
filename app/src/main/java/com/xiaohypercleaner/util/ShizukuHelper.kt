package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShizukuHelper {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Проверяет установлено ли приложение Shizuku на устройстве.
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            AppLog.i("ShizukuHelper", "Shizuku is installed")
            true
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "Shizuku not installed: ${e.message}")
            false
        }
    }

    /**
     * Открывает приложение Shizuku для настройки и запуска.
     */
    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLog.i("ShizukuHelper", "Shizuku app opened")
            } else {
                AppLog.w("ShizukuHelper", "Shizuku launch intent not found")
            }
        } catch (e: Exception) {
            AppLog.e("ShizukuHelper", "Failed to open Shizuku app: ${e.message}")
        }
    }

    /**
     * Открывает магазин для установки Shizuku (Play Store / RuStore / GetApps).
     */
    fun openShizukuInStore(context: Context) {
        try {
            // Пробуем RuStore
            val rustoreIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("rustore://application/$SHIZUKU_PACKAGE"))
            rustoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(rustoreIntent)
            AppLog.i("ShizukuHelper", "Opened RuStore for Shizuku")
            return
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "RuStore not available: ${e.message}")
        }

        try {
            // Пробуем GetApps (Xiaomi)
            val getappsIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("mimarket://details?id=$SHIZUKU_PACKAGE"))
            getappsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(getappsIntent)
            AppLog.i("ShizukuHelper", "Opened GetApps for Shizuku")
            return
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "GetApps not available: ${e.message}")
        }

        try {
            // Пробуем Play Store
            val playIntent =
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(playIntent)
            AppLog.i("ShizukuHelper", "Opened Play Store for Shizuku")
            return
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "Play Store not available: ${e.message}")
        }

        // Fallback: веб-версия Play Store
        try {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
            )
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(webIntent)
            AppLog.i("ShizukuHelper", "Opened web store for Shizuku")
        } catch (e: Exception) {
            AppLog.e("ShizukuHelper", "Failed to open any store: ${e.message}")
        }
    }
}