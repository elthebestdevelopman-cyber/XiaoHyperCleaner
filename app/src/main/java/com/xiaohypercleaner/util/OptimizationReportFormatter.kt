package com.xiaohypercleaner.util

import com.xiaohypercleaner.data.OptimizationReport as DataOptimizationReport
import com.xiaohypercleaner.engine.OptimizationReport as EngineOptimizationReport
import com.xiaohypercleaner.engine.OptimizationStep
import com.xiaohypercleaner.engine.OptimizationStepStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Централизованный форматировщик отчётов оптимизации.
 * Вынесен из MainViewModel и AdbEnablerService для устранения дублирования.
 *
 * УЛУЧШЕНИЯ:
 * 1. Форматирование даты в читаемый вид (вместо миллисекунд)
 * 2. Защита от пустого отчёта и отсутствующих полей
 * 3. Метод formatForLog() — краткая версия для AppLog
 * 4. Безопасный доступ к verificationResult через ?.
 *
 * ЗАВИСИМОСТИ (проверить при сверке с движком):
 * - engine.OptimizationReport (timestamp, steps, successCount, errorCount, skippedCount, totalCount)
 * - engine.OptimizationStep (name, status, message, rolledBack)
 * - engine.OptimizationStepStatus (enum: SUCCESS, ERROR, SKIPPED, ROLLED_BACK, IN_PROGRESS)
 * - data.OptimizationReport (success, disabledPackages, appliedSettings, failedActions, verificationResult)
 */
object OptimizationReportFormatter {

    private const val TAG = "ReportFormatter"

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    /**
     * Полный текстовый отчёт для отображения пользователю или экспорта.
     *
     * @param report Отчёт от OptimizationEngine (Pro-режим)
     * @return Отформатированная строка с детализацией по шагам
     */
    fun formatReport(report: EngineOptimizationReport): String {
        // Защита от пустого отчёта
        if (report.steps.isEmpty() && report.totalCount == 0) {
            return buildString {
                appendLine("=== XIAO HYPER CLEANER — ОТЧЁТ ОБ ОПТИМИЗАЦИИ ===")
                appendLine()
                appendLine("📅 Дата: ${formatTimestamp(report.timestamp)}")
                appendLine("ℹ️ Нет данных об оптимизации.")
                appendLine()
                appendLine("=== КОНЕЦ ОТЧЁТА ===")
            }
        }

        return buildString {
            appendLine("=== XIAO HYPER CLEANER — ОТЧЁТ ОБ ОПТИМИЗАЦИИ ===")
            appendLine()
            appendLine("📅 Дата: ${formatTimestamp(report.timestamp)}")
            appendLine("✅ Успешно: ${report.successCount}")
            appendLine("❌ Ошибок: ${report.errorCount}")
            appendLine("⏭️ Пропущено: ${report.skippedCount}")
            appendLine()

            if (report.steps.isNotEmpty()) {
                appendLine("📋 ДЕТАЛИЗАЦИЯ ПО ШАГАМ:")
                appendLine()
                report.steps.forEachIndexed { index, step ->
                    appendLine("${index + 1}. ${step.name}")
                    appendLine("   Статус: ${formatStatus(step)}")
                    if (step.message.isNotBlank()) {
                        appendLine("   Сообщение: ${step.message}")
                    }
                    if (step.rolledBack) {
                        appendLine("   ⚠️ Откачено")
                    }
                    appendLine()
                }
            }

            if (report.errorCount > 0) {
                appendLine("⚠️ ОШИБКИ:")
                report.steps
                    .filter { it.status == OptimizationStepStatus.ERROR }
                    .forEach { step ->
                        appendLine("• ${step.name}: ${step.message}")
                    }
                appendLine()
            }

            appendLine("=== КОНЕЦ ОТЧЁТА ===")
        }
    }

    /**
     * Краткая сводка для отображения в UI (одна строка).
     *
     * @param report Отчёт от OptimizationEngine (Pro-режим)
     * @return Краткая строка вида «Выполнено: 15/20 (75%), ошибок: 3»
     */
    fun summary(report: EngineOptimizationReport): String {
        val successRate: Int = if (report.totalCount > 0) {
            (report.successCount * 100) / report.totalCount
        } else {
            0
        }

        return buildString {
            append("Выполнено: ${report.successCount}/${report.totalCount} ")
            append("($successRate%)")
            if (report.errorCount > 0) {
                append(", ошибок: ${report.errorCount}")
            }
            if (report.skippedCount > 0) {
                append(", пропущено: ${report.skippedCount}")
            }
        }
    }

    /**
     * Краткая версия для записи в AppLog (без детализации по шагам).
     * Используется, чтобы не засорять лог-файл длинными отчётами.
     *
     * @param report Отчёт от OptimizationEngine (Pro-режим)
     * @return Однострочная сводка для логирования
     */
    fun formatForLog(report: EngineOptimizationReport): String {
        return buildString {
            append("Оптимизация: ${report.successCount}/${report.totalCount} успешно")
            if (report.errorCount > 0) {
                append(", ошибок: ${report.errorCount}")
            }
            if (report.skippedCount > 0) {
                append(", пропущено: ${report.skippedCount}")
            }
        }
    }

    /**
     * Адаптер для data.OptimizationReport из OptimizationEngine.
     *
     * ЗАВИСИМОСТЬ: проверяет наличие полей:
     * - disabledPackages: List<String>
     * - appliedSettings: List<String>
     * - failedActions: List<String>
     * - success: Boolean
     * - verificationResult: объект с полем success
     *
     * @param dataReport Отчёт от OptimizationEngine (Pro-режим, data-слой)
     * @return Краткая строка с результатами
     */
    fun summary(dataReport: DataOptimizationReport): String {
        val total: Int = dataReport.disabledPackages.size + dataReport.appliedSettings.size
        val failed: Int = dataReport.failedActions.size

        return buildString {
            append("Отключено пакетов: ${dataReport.disabledPackages.size}, ")
            append("применено настроек: ${dataReport.appliedSettings.size}")

            if (failed > 0) {
                append(", ошибок: $failed")
            }

            // Безопасный доступ: если verificationResult отсутствует, не роняем
            val verificationPassed: Boolean? = try {
                dataReport.verificationResult.success
            } catch (e: Exception) {
                AppLog.w(TAG, "verificationResult недоступен: ${e.message}")
                null
            }

            if (verificationPassed == false) {
                append(", проверка не пройдена")
            }
        }
    }

    // ═══ Приватные хелперы ═══

    /**
     * Форматирует статус шага в читаемый вид с эмодзи.
     */
    private fun formatStatus(step: OptimizationStep): String {
        return when (step.status) {
            OptimizationStepStatus.SUCCESS -> "✅ Успешно"
            OptimizationStepStatus.ERROR -> "❌ Ошибка"
            OptimizationStepStatus.SKIPPED -> "⏭️ Пропущено"
            OptimizationStepStatus.ROLLED_BACK -> "🔄 Откачено"
            OptimizationStepStatus.IN_PROGRESS -> "⏳ Выполняется"
        }
    }

    /**
     * Форматирует timestamp в читаемую дату.
     * Поддерживает как миллисекунды (Long), так и строку.
     */
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            dateFormat.format(Date(timestamp))
        } catch (e: Exception) {
            // Если timestamp некорректный, возвращаем как есть
            timestamp.toString()
        }
    }
}