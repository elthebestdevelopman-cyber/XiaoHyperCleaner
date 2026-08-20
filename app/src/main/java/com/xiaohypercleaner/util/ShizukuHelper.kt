package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri

/**
 * Помощник установки Shizuku из всех источников, где он точно есть:
 * Google Play, Aurora Store, GetApps, F-Droid, GitHub, APKPure.
 * RuStore НЕ содержит Shizuku — не используется.
 */
object ShizukuHelper {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PLAY_PACKAGE = "com.android.vending"
    private const val FDROID_PACKAGE = "org.fdroid.fdroid"
    private const val AURORA_PACKAGE = "com.aurora.store"
    private const val GETAPPS_PACKAGE = "com.xiaomi.market"

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

    fun hasPlayStore(context: Context) = hasPackage(context, PLAY_PACKAGE)
    fun hasAurora(context: Context) = hasPackage(context, AURORA_PACKAGE)
    fun hasGetApps(context: Context) = hasPackage(context, GETAPPS_PACKAGE)

    private fun hasPackage(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Автоцепочка: Play → GitHub → APKPure */
    fun openShizukuInStore(context: Context) {
        AppLog.i("ShizukuHelper", "=== openShizukuInStore START (v5) ===")
        if (hasPlayStore(context) && openPlay(context)) return
        if (openGithub(context)) return
        openApkPure(context)
    }

    /**
     * Открывает СТРАНИЦУ Shizuku в Google Play.
     *
     * Порядок (market:// на многих устройствах открывает главную, поэтому он второй):
     * 1. https-ссылка + setPackage(com.android.vending) — точная страница внутри Play Store
     * 2. market://details?id=... — классический deep link
     * 3. https-ссылка без пакета — браузер откроет ту же страницу
     */
    fun openPlay(context: Context): Boolean {
        // 1. Принудительно в приложение Play Store через https-ссылку
        try {
            AppLog.i("ShizukuHelper", "Trying Play https + setPackage...")
            val intent = Intent(Intent.ACTION_VIEW, URL_PLAY_WEB.toUri())
            intent.setPackage(PLAY_PACKAGE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "Play https + setPackage: SUCCESS (exact page)")
            return true
        } catch (e: Exception) {
            AppLog.w(
                "ShizukuHelper",
                "Play https + setPackage failed: ${e.javaClass.simpleName}: ${e.message}"
            )
        }

        // 2. Классический market:// deep link
        if (tryOpen(context, "market://details?id=$SHIZUKU_PACKAGE", "Play market://")) return true

        // 3. Браузер на ту же страницу
        return tryOpen(context, URL_PLAY_WEB, "Play web (browser)")
    }

    fun openAurora(context: Context): Boolean {
        AppLog.i("ShizukuHelper", "Trying Aurora Store...")
        val intent = context.packageManager.getLaunchIntentForPackage(AURORA_PACKAGE)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "Aurora: SUCCESS (user searches Shizuku inside)")
            true
        } else {
            AppLog.w("ShizukuHelper", "Aurora: launch intent null")
            false
        }
    }

    fun openGetApps(context: Context): Boolean {
        AppLog.i("ShizukuHelper", "Trying GetApps...")
        val intent = context.packageManager.getLaunchIntentForPackage(GETAPPS_PACKAGE)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "GetApps: SUCCESS (user searches Shizuku inside)")
            true
        } else {
            AppLog.w("ShizukuHelper", "GetApps: launch intent null")
            false
        }
    }

    fun openGithub(context: Context): Boolean =
        tryOpen(context, URL_GITHUB, "GitHub releases")

    fun openApkPure(context: Context): Boolean =
        tryOpen(context, URL_APKPURE, "APKPure web")

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

    private fun tryOpen(context: Context, uri: String, name: String): Boolean {
        return try {
            AppLog.i("ShizukuHelper", "Trying $name: $uri")
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLog.i("ShizukuHelper", "$name: SUCCESS")
            true
        } catch (e: Exception) {
            AppLog.w("ShizukuHelper", "$name failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}