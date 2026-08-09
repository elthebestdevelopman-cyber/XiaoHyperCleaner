package com.xiaohypercleaner.util

import android.content.Context
import android.util.Log
import com.xiaohypercleaner.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE_NAME = "xhc.log"
    private const val MAX_SIZE = 512L * 1024
    private var file: File? = null
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        if (!BuildConfig.BETA) return
        file = File(context.filesDir, FILE_NAME).also { f ->
            if (f.exists() && f.length() > MAX_SIZE) f.delete()
        }
        i("AppLog", "========================================")
        i("AppLog", "beta logging started, v${BuildConfig.VERSION_NAME}")
        i("AppLog", "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        i(
            "AppLog",
            "android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})"
        )
        i("AppLog", "========================================")
    }

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    fun e(tag: String, msg: String, throwable: Throwable) {
        write('E', tag, "$msg: ${throwable.javaClass.simpleName}: ${throwable.message}")
        throwable.stackTrace.take(10).forEach { frame ->
            write('E', tag, "    at $frame")
        }
    }

    private fun write(level: Char, tag: String, msg: String) {
        val masked = LogMasker.mask(msg)
        when (level) {
            'E' -> Log.e(tag, masked)
            'W' -> Log.w(tag, masked)
            else -> Log.i(tag, masked)
        }
        val f = file ?: return
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$time $level/$tag: $masked\n")
        } catch (_: Exception) {
        }
    }

    fun readAll(): String {
        val f = file ?: return ""
        return if (f.exists()) f.readText() else ""
    }

    fun getLogFile(): File? = file

    fun clear() {
        file?.delete()
        i("AppLog", "log cleared")
    }
}