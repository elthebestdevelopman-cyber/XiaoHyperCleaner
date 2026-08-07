package com.xiaohypercleaner.util

import android.content.Context
import android.util.Log
import com.xiaohypercleaner.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var file: File? = null
    private const val MAX_SIZE = 512L * 1024

    fun init(context: Context) {
        if (!BuildConfig.BETA) return
        val f = File(context.filesDir, "beta.log")
        if (f.exists() && f.length() > MAX_SIZE) {
            val old = File(context.filesDir, "beta.old.log")
            if (old.exists()) old.delete()
            f.renameTo(old)
        }
        file = f
        i("AppLog", "beta logging started, version=${BuildConfig.VERSION_NAME}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.i(tag, msg)
        }
        val f = file ?: return
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$time $level/$tag: $msg\n")
        } catch (_: Exception) {
        }
    }

    fun readAll(context: Context): String {
        val f = File(context.filesDir, "beta.log")
        return if (f.exists()) f.readText() else ""
    }
}