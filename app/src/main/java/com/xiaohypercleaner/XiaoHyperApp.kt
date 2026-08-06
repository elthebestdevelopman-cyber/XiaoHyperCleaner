package com.xiaohypercleaner

import android.app.Application
import com.xiaohypercleaner.data.PreferencesManager

class XiaoHyperApp : Application() {
    lateinit var preferencesManager: PreferencesManager
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
    }
}