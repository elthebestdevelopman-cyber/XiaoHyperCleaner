package com.xiaohypercleaner

import android.content.Context
import com.xiaohypercleaner.data.AdbClient
import com.xiaohypercleaner.data.AdbPortResolver
import com.xiaohypercleaner.data.OptimizationEngine
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDependencies(private val context: Context) {

    companion object {
        private const val TAG = "AppDeps"
    }

    val preferencesManager: PreferencesManager by lazy {
        AppLog.i(TAG, "creating PreferencesManager")
        PreferencesManager(context)
    }

    val portResolver: AdbPortResolver by lazy {
        AppLog.i(TAG, "creating AdbPortResolver")
        AdbPortResolver(context)
    }

    suspend fun newEngine(): OptimizationEngine = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "newEngine: resolving ports")
        val ports = try {
            portResolver.resolve()
        } catch (e: Exception) {
            AppLog.e(TAG, "newEngine: port resolution failed", e)
            listOf(AppConstants.ADB_DEFAULT_PORT)
        }
        AppLog.i(TAG, "newEngine: ports=$ports")
        OptimizationEngine(AdbClient(ports = ports))
    }
}