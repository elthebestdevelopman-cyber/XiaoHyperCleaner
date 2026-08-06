package com.xiaohypercleaner

import android.app.Application

class XiaoHyperApp : Application() {

    lateinit var deps: AppDependencies
        private set

    val preferencesManager get() = deps.preferencesManager

    override fun onCreate() {
        super.onCreate()
        deps = AppDependencies(this)
    }
}