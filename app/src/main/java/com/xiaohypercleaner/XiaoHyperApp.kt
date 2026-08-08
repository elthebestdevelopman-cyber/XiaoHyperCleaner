package com.xiaohypercleaner

import android.app.Application
import com.xiaohypercleaner.util.AppLog

class XiaoHyperApp : Application() {

    lateinit var deps: AppDependencies
        private set

    val preferencesManager get() = deps.preferencesManager

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        deps = AppDependencies(this)
    }
}