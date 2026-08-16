package com.xiaohypercleaner.util

import com.xiaohypercleaner.data.OptimizationReport as DataOptimizationReport
import com.xiaohypercleaner.engine.OptimizationReport as EngineOptimizationReport
import com.xiaohypercleaner.engine.OptimizationStep
import com.xiaohypercleaner.engine.OptimizationStepStatus

/**
 * Централизованный форматировщик отчётов оптимизации.
 * Вынесен из MainViewModel и AdbEnablerService для устранения дублирования.
 */
object OptimizationReportFormatter {

    fun formatReport(report: EngineOptimizationReport): String {
        return buildString {
            appendLine("=== XIAO HYPER CLEANER - ОТЧЁТ ОБ ОПТИМИЗАЦИИ ===")
            appendLine()
            appendLine("📅 Дата: ${report.timestamp}")
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
                report.steps.filter { it.status == OptimizationStepStatus.ERROR }.forEach { step ->
                    appendLine("• ${step.name}: ${step.message}")
                }
                appendLine()
            }

            appendLine("=== КОНЕЦ ОТЧЁТА ===")
        }
    }

    private fun formatStatus(step: OptimizationStep): String {
        return when (step.status) {
            OptimizationStepStatus.SUCCESS -> "✅ Успешно"
            OptimizationStepStatus.ERROR -> "❌ Ошибка"
            OptimizationStepStatus.SKIPPED -> "⏭️ Пропущено"
            OptimizationStepStatus.ROLLED_BACK -> "🔄 Откачено"
            OptimizationStepStatus.IN_PROGRESS -> "⏳ Выполняется"
        }
    }

    fun summary(report: EngineOptimizationReport): String {
        val successRate = if (report.totalCount > 0) {
            (report.successCount * 100) / report.totalCount
        } else 0

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
     * Адаптер для data.OptimizationReport из OptimizationEngine
     */
    fun summary(dataReport: DataOptimizationReport): String {
        val total = dataReport.disabledPackages.size + dataReport.appliedSettings.size
        val success = if (dataReport.success) total else total - dataReport.failedActions.size
        val failed = dataReport.failedActions.size
        
        return buildString {
            append("Отключено пакетов: ${dataReport.disabledPackages.size}, ")
            append("применено настроек: ${dataReport.appliedSettings.size}")
            if (failed > 0) {
                append(", ошибок: $failed")
            }
            if (!dataReport.verificationResult.success) {
                append(", проверка не пройдена")
            }
        }
    }
}