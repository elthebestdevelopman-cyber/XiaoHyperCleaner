package com.xiaohypercleaner.ui.extensions

import android.content.Context
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.OptimizationMode

/**
 * UI-расширения для [OptimizationMode].
 *
 * Резолвинг локализованных строк вынесен из data-слоя в UI-слой
 * для соблюдения принципов чистой архитектуры:
 * - Data-слой (OptimizationMode) остаётся чистым и тестируемым
 * - UI-слой отвечает за отображение и локализацию
 *
 * ИСПОЛЬЗУЕМЫЕ СТРОКИ (из strings.xml):
 * - R.string.level_simple_title — "🟢 Простая"
 * - R.string.level_pro_title — "🔵 Продвинутая"
 * - R.string.level_simple_desc — описание простого режима
 * - R.string.level_pro_desc — описание продвинутого режима
 */

/**
 * Локализованное название режима для отображения в UI.
 *
 * @param context Android Context для доступа к строковым ресурсам
 * @return локализованное название режима
 */
fun OptimizationMode.displayName(context: Context): String = when (this) {
    OptimizationMode.SIMPLE -> context.getString(R.string.level_simple_title)
    OptimizationMode.PRO -> context.getString(R.string.level_pro_title)
}

/**
 * Локализованное описание режима для отображения в UI.
 *
 * @param context Android Context для доступа к строковым ресурсам
 * @return локализованное описание режима
 */
fun OptimizationMode.description(context: Context): String = when (this) {
    OptimizationMode.SIMPLE -> context.getString(R.string.level_simple_desc)
    OptimizationMode.PRO -> context.getString(R.string.level_pro_desc)
}