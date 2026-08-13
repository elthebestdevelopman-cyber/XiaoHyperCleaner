package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Помощник для引导 пользователя через установку Shizuku.
 *
 * Shizuku 100% есть в:
 * - Google Play (market://)
 * - F-Droid (fdroid://)
 * - GitHub (прямой APK, работает всегда)
 * - APKPure (web-зеркало)
 *
 * RuStore и GetApps НЕ содержат Shizuku — не используем.
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PLAY_PACKAGE = "com.android.vending"
    private const val FDROID_PACKAGE = "org.fdroid.fdroid"

    private const val URL_GITHUB = "https://github.com/RikkaApps/Shizuku/releases/latest"
    private const val URL_PLAY_WEB =
        "https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE"
    private const val URL_APKPURE = "https://apkpure.com/shizuku/$SHIZUKU_PACKAGE"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            AppLog.i("ShizukuHelper", "Shizuku is installed")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.i("ShizukuHelper", "Shizuku NOT installed")
            false
        } catch (e: Exception) {
            AppLog.w(
                "ShizukuHelper",
                "Shizuku check failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    private fun hasPackage(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Открывает страницу Shizuku в магазине, где он 100% есть.
     *
     * Приоритет:
     * 1. Google Play (deep link на страницу приложения)
     * 2. F-Droid (deep link на страницу приложения)
     * 3. GitHub releases (APK напрямую — работает на любом устройстве)
     * 4. APKPure web (fallback-зеркало)
     */
    fun openShizukuInStore(context: Context) {
        AppLog.i("ShizukuHelper", "=== openShizukuInStore START ===")

        // 1. Google Play
        if (hasPackage(context, PLAY_PACKAGE)) {
            if (tryOpen(
                    context,
                    "market://details?id=$SHIZUKU_PACKAGE",
                    "Play Store deep link"
                )
            ) return
            if (tryOpen(context, URL_PLAY_WEB, "Play Store web")) return
        } else {
            AppLog.i("ShizukuHelper", "Play Store not present")
        }

        // 2. F-Droid
        if (hasPackage(context, FDROID_PACKAGE)) {
            if (tryOpen(
                    context,
                    "fdroid://details?id=$SHIZUKU_PACKAGE",
                    "F-Droid deep link"
                )
            ) return
            if (tryOpen(
                    context,
                    "https://f-droid.org/packages/$SHIZUKU_PACKAGE/",
                    "F-Droid web"
                )
            ) return
        } else {
            AppLog.i("ShizukuHelper", "F-Droid not present")
        }

        // 3. GitHub — универсальный путь (APK напрямую)
        if (tryOpen(context, URL_GITHUB, "GitHub releases")) return

        // 4. APKPure — последнее зеркало
        if (tryOpen(context, URL_APKPURE, "APKPure web")) return

        AppLog.e("ShizukuHelper", "All store attempts failed")
        AppLog.i("ShizukuHelper", "=== openShizukuInStore END ===")
    }

    private fun tryOpen(context: Context, uri: String, name: String): Boolean {
        return try {
            AppLog.i("ShizukuHelper", "Trying $name: $uri")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "$name: SUCCESS")
            true
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "$name failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

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