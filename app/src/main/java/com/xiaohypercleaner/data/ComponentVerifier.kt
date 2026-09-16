package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import kotlinx.coroutines.delay

/**
 * Проверка факта открытия целевого экрана по screen-маркерам и возврат на
 * домашний экран.
 *
 * Используется гейтом уверенности (semantic step) и discovery-движком:
 * действовать можно только тогда, когда экран действительно опознан
 * ([matchesScreen]), иначе шаг помечается `skipped(low_confidence)`.
 */
object ComponentVerifier {

    private const val TAG = "discover"
    private const val POLL_INTERVAL_MS = 200L
    private const val HOME_SETTLE_MS = 500L

    /** Таймаут ожидания экрана по умолчанию (5 с — спецификация discovery). */
    const val DEFAULT_TIMEOUT_MS = 5000L

    /** Текст всего экрана (для маркеров и диагностики). */
    fun screenText(node: AccessibilityNodeInfo?): String = NodeTree.collectText(node)

    /** Сколько screen-маркеров найдено на экране (нормализованное вхождение). */
    fun matchedMarkers(text: String?, markers: List<String>): Int {
        if (text.isNullOrEmpty()) return 0
        return markers.count { it.isNotBlank() && TextMatcher.normalizedContains(text, it) }
    }

    /** Экран опознан: найдено не меньше [minMatches] маркеров. */
    fun matchesScreen(text: String?, markers: List<String>, minMatches: Int = 1): Boolean =
        matchedMarkers(text, markers) >= minMatches.coerceAtLeast(1)

    /** Текст целевого экрана сейчас (пустая строка, если активного окна нет). */
    fun currentScreenText(service: AccessibilityService): String =
        screenText(service.rootInActiveWindow)

    /**
     * Ждёт появления любого из маркеров (точное вхождение), затем — нечёткое
     * совпадение по узлам дерева (последний рубеж для вариантов прошивок).
     */
    suspend fun awaitScreen(
        service: AccessibilityService,
        markers: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        minMatches: Int = 1,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        if (markers.isEmpty()) return false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isCancelled()) return false
            val root = service.rootInActiveWindow
            if (root != null) {
                val matched = matchesScreen(screenText(root), markers, minMatches)
                recycle(root)
                if (matched) return true
            }
            delay(POLL_INTERVAL_MS)
        }
        if (!isCancelled()) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val hit = NodeTree.findInTree(root) { NodeTree.matchesAny(it, markers, fuzzy = true) } != null
                recycle(root)
                if (hit) {
                    AppLog.w(TAG, "awaitScreen: fuzzy marker match (minMatches=$minMatches)")
                    return true
                }
            }
        }
        return false
    }

    /** Сброс на домашний экран (GLOBAL_ACTION_HOME), без resolver «Главный экран». */
    suspend fun resetToHome(service: AccessibilityService): Boolean {
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(HOME_SETTLE_MS)
        return ok
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}