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
    private const val MAX_SIZE = 256L * 1024
    private var file: File? = null

    fun init(context: Context) {
        if (!BuildConfig.BETA) return
        file = File(context.filesDir, FILE_NAME).also { f ->
            if (f.exists() && f.length() > MAX_SIZE) f.delete()
        }
        i("AppLog", "beta logging started")
    }

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    private fun write(level: Char, tag: String, msg: String) {
        val masked = LogMasker.mask(msg)
        when (level) {
            'E' -> Log.e(tag, masked)
            'W' -> Log.w(tag, masked)
            else -> Log.i(tag, masked)
        }
        val f = file ?: return
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$time $level/$tag: $masked\n")
        } catch (_: Exception) {
        }
    }

    fun readAll(context: Context): String {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) f.readText() else ""
    }
}