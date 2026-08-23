package com.xiaohypercleaner.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.xiaohypercleaner.util.AppLog

/** Открытие URL в браузере */
fun openUrl(context: Context, url: String) {
    AppLog.i("OpenUrl", "opening url: $url")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
        AppLog.e("OpenUrl", "failed to open url: ${e.message}")
    }
}

/** WebView для донатов; при ошибке — fallback в браузер */
fun openWebView(context: Context, url: String, title: String) {
    AppLog.i("WebView", "opening webView: $url")
    try {
        context.startActivity(
            Intent(context, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        AppLog.w("WebView", "WebView failed, fallback to browser: ${e.message}")
        openUrl(context, url)
    }
}

/** Шаринг файла лога через FileProvider */
fun shareLog(context: Context) {
    AppLog.i("ShareLog", "shareLog requested")
    try {
        val logFile = AppLog.getLogFile()
        if (logFile == null || !logFile.exists() || logFile.length() == 0L) {
            AppLog.w("ShareLog", "log file is null/empty/missing")
            return
        }
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "XiaoHyperCleaner log ${System.currentTimeMillis()}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share log"
            )
        )
        AppLog.i("ShareLog", "share intent sent successfully")
    } catch (e: Exception) {
        AppLog.e("ShareLog", "shareLog failed", e)
    }
}

/** Экран «О телефоне» (для включения режима разработчика) */
fun openDeviceInfoSettings(context: Context) {
    AppLog.i("OpenSettings", "opening device info settings")
    try {
        context.startActivity(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "device info failed: ${e.message}")
        openSettings(context)
    }
}

/** Настройки разработчика */
fun openDevOptionsSettings(context: Context) {
    AppLog.i("OpenSettings", "opening developer options")
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        AppLog.w("OpenSettings", "dev options failed: ${e.message}")
        openSettings(context)
    }
}

/** Общие настройки — fallback для всех open*Settings */
private fun openSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Оценка приложения: RuStore → Mi Market → Play Market → web */
fun openRateApp(context: Context) {
    AppLog.i("OpenRate", "opening rate app")
    val pkg = context.packageName
    val schemes = listOf(
        "rustore://application/$pkg",
        "mimarket://details?id=$pkg",
        "market://details?id=$pkg"
    )
    for (s in schemes) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, s.toUri()))
            AppLog.i("OpenRate", "opened via scheme: $s")
            return
        } catch (_: Exception) {
        }
    }
    openUrl(context, "https://play.google.com/store/apps/details?id=$pkg")
}