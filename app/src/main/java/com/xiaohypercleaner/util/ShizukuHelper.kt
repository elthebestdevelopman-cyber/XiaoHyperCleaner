package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Помощник установки Shizuku.
 *
 * Shizuku 100% есть только в:
 * - Google Play (market://)
 * - F-Droid (fdroid://)
 * - GitHub (официальный APK, открывается в браузере на любом устройстве)
 * - APKPure (зеркало, браузер)
 *
 * RuStore и GetApps НЕ содержат Shizuku — НЕ используются.
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
            AppLog.w("ShizukuHelper", "Shizuku check failed: ${e.javaClass.simpleName}")
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
     * Открывает страницу Shizuku там, где он точно есть.
     *
     * Приоритет:
     * 1. Google Play deep link — сразу на страницу приложения
     * 2. Google Play web — fallback
     * 3. F-Droid deep link — если стоит F-Droid
     * 4. GitHub releases — универсально, работает на любом устройстве
     * 5. APKPure — последнее зеркало
     */
    fun openShizukuInStore(context: Context) {
        AppLog.i("ShizukuHelper", "=== openShizukuInStore START (v3, no RuStore) ===")

        // 1+2. Google Play
        if (hasPackage(context, PLAY_PACKAGE)) {
            if (tryOpen(context, "market://details?id=$SHIZUKU_PACKAGE", "Play deep link")) return
            if (tryOpen(context, URL_PLAY_WEB, "Play web")) return
        } else {
            AppLog.i("ShizukuHelper", "Play Store not present on device")
        }

        // 3. F-Droid
        if (hasPackage(context, FDROID_PACKAGE)) {
            if (tryOpen(
                    context,
                    "fdroid://details?id=$SHIZUKU_PACKAGE",
                    "F-Droid deep link"
                )
            ) return
        } else {
            AppLog.i("ShizukuHelper", "F-Droid not present on device")
        }

        // 4. GitHub — официальный APK, работает всегда
        if (tryOpen(context, URL_GITHUB, "GitHub releases")) return

        // 5. APKPure — зеркало
        if (tryOpen(context, URL_APKPURE, "APKPure web")) return

        AppLog.e("ShizukuHelper", "ALL store attempts failed")
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
            AppLog.e("ShizukuHelper", "openShizukuApp failed: ${e.javaClass.simpleName}")
            false
        }
    }
}