package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Помощник, который ведёт пользователя через установку Shizuku одной кнопкой.
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Открывает магазин для установки Shizuku (RuStore / GetApps / Play / веб) */
    fun openStoreForInstall(context: Context): Boolean {
        val schemes = listOf(
            "rustore://application/$SHIZUKU_PACKAGE",
            "mimarket://details?id=$SHIZUKU_PACKAGE",
            "market://details?id=$SHIZUKU_PACKAGE"
        )
        for (scheme in schemes) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                AppLog.i("ShizukuHelper", "opened via $scheme")
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            AppLog.e("ShizukuHelper", "all store attempts failed: ${e.message}")
            false
        }
    }

    /** Открывает приложение Shizuku для запуска/выдачи разрешения */
    fun openShizukuApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            AppLog.e("ShizukuHelper", "openShizukuApp failed: ${e.message}")
            false
        }
    }
}