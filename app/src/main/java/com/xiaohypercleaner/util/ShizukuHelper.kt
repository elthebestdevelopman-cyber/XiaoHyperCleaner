package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Помощник для引导 пользователя через установку и настройку Shizuku.
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Проверяет установлено ли приложение Shizuku */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            AppLog.i("ShizukuHelper", "Shizuku is installed")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.i("ShizukuHelper", "Shizuku NOT installed (NameNotFoundException)")
            false
        } catch (e: Exception) {
            AppLog.w(
                "ShizukuHelper",
                "Shizuku check failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    /**
     * Открывает магазин для установки Shizuku.
     * Порядок: RuStore → GetApps → Play Store → Web.
     * Подробно логирует каждую попытку для диагностики проблем.
     */
    fun openShizukuInStore(context: Context) {
        AppLog.i("ShizukuHelper", "=== openShizukuInStore START ===")

        // 1. RuStore
        try {
            AppLog.i("ShizukuHelper", "Trying RuStore...")
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("rustore://application/$SHIZUKU_PACKAGE"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "RuStore: SUCCESS")
            return
        } catch (e: android.content.ActivityNotFoundException) {
            AppLog.w("ShizukuHelper", "RuStore: ActivityNotFoundException (не установлен)")
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "RuStore: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 2. GetApps (Xiaomi)
        try {
            AppLog.i("ShizukuHelper", "Trying GetApps (mimarket)...")
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("mimarket://details?id=$SHIZUKU_PACKAGE"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "GetApps: SUCCESS")
            return
        } catch (e: android.content.ActivityNotFoundException) {
            AppLog.w("ShizukuHelper", "GetApps: ActivityNotFoundException (не установлен)")
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "GetApps: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 3. Play Store
        try {
            AppLog.i("ShizukuHelper", "Trying Play Store (market)...")
            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "Play Store: SUCCESS")
            return
        } catch (e: android.content.ActivityNotFoundException) {
            AppLog.w("ShizukuHelper", "Play Store: ActivityNotFoundException (не установлен)")
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "Play Store: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 4. Web fallback
        try {
            AppLog.i("ShizukuHelper", "Trying Web fallback...")
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "Web fallback: SUCCESS")
        } catch (e: Exception) {
            AppLog.e(
                "ShizukuHelper",
                "Web fallback FAILED: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

        AppLog.i("ShizukuHelper", "=== openShizukuInStore END ===")
    }

    /** Открывает приложение Shizuku для настройки/запуска */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLog.i("ShizukuHelper", "Shizuku app opened")
                true
            } else {
                AppLog.w("ShizukuHelper", "Shizuku launch intent is null")
                false
            }
        } catch (e: Exception) {
            AppLog.e(
                "ShizukuHelper",
                "openShizukuApp failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }
}