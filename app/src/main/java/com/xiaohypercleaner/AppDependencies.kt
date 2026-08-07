package com.xiaohypercleaner

import android.content.Context
import com.xiaohypercleaner.data.AdbClient
import com.xiaohypercleaner.data.AdbPortResolver
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDependencies(private val context: Context) {
    val preferencesManager: PreferencesManager by lazy { PreferencesManager(context) }
    val portResolver: AdbPortResolver by lazy { AdbPortResolver(context) }

    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        OptimizationEngine(AdbClient(ports = portResolver.resolve()))
    }
}