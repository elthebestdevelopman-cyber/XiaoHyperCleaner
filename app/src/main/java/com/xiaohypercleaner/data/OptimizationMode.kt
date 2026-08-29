package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Режим оптимизации
 */
enum class OptimizationMode {
    SIMPLE,
    PRO;

    /** Проверяет, простой ли это режим */
    fun isSimple(): Boolean = this == SIMPLE

    /** Проверяет, продвинутый ли это режим */
    fun isPro(): Boolean = this == PRO

    /** Локализованное название для UI */
    fun displayName(context: Context): String = when (this) {
        SIMPLE -> context.getString(R.string.mode_simple_title)
        PRO -> context.getString(R.string.mode_pro_title)
    }

    /** Локализованное описание для UI */
    fun description(context: Context): String = when (this) {
        SIMPLE -> context.getString(R.string.mode_simple_desc)
        PRO -> context.getString(R.string.mode_pro_desc)
    }

    fun toPreferenceKey() = stringPreferencesKey("optimization_mode")

    companion object {
        fun fromString(value: String?): OptimizationMode {
            return try {
                valueOf(value ?: "SIMPLE")
            } catch (e: IllegalArgumentException) {
                SIMPLE
            }
        }
    }
}
