package com.xiaohypercleaner.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree

/**
 * Поиск переключателей (Switch/CheckBox/RadioButton/ToggleButton) в дереве
 * Accessibility и чтение их фактического состояния.
 *
 * Ключевое требование отката: состояние читается В МОМЕНТ тумблера
 * ([Found.checkedBefore]) и сохраняется в снапшот — откат возвращает
 * фактическое «было», а не инверсию целевого значения.
 */
object SwitchFinder {

    private const val TAG = "switch"

    /** Найденный переключатель вместе с состоянием до действия. */
    data class Found(
        val node: AccessibilityNodeInfo,
        val checkedBefore: Boolean,
        val label: String,
        val desc: String,
        val bounds: Rect
    )

    fun isSwitchLike(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        return cls.contains("Switch") || cls.contains("CheckBox") ||
            cls.contains("RadioButton") || cls.contains("ToggleButton") ||
            node.isCheckable
    }

    /** Фактическое состояние переключателя (deprecated API скрыт в Compat). */
    @Suppress("DEPRECATION")
    fun isChecked(node: AccessibilityNodeInfo): Boolean =
        AccessibilityNodeInfoCompat.wrap(node).isChecked

    /** Снимок состояния переключателя для снапшота отката. */
    fun describe(node: AccessibilityNodeInfo, fallbackLabel: String = ""): Found {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return Found(
            node = node,
            checkedBefore = isChecked(node),
            label = node.text?.toString() ?: fallbackLabel,
            desc = node.contentDescription?.toString() ?: "",
            bounds = bounds
        )
    }

    /**
     * Ищет переключатель по тексту/описанию. Фолбэк «первый switch на экране»
     * запрещён как класс — именно он давал ложные OK (bounds совпадали с чужим
     * тумблером).
     *
     * 1. сам переключатель с подходящим текстом;
     * 2. подпись (TextView) → тумблер ТОЙ ЖЕ строки;
     * 3. нечёткое совпадение — тем же правилом строки.
     */
    fun findSwitch(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        NodeTree.findInTree(root) { isSwitchLike(it) && NodeTree.matchesAny(it, texts) }
            ?.let { return it }

        NodeTree.findInTree(root) { !isSwitchLike(it) && NodeTree.matchesAny(it, texts) }
            ?.let { label -> findSwitchInRow(label, texts)?.let { sw -> return sw } }

        NodeTree.findInTree(root) { isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
            ?.let {
                AppLog.w(TAG, "fuzzy switch match on node")
                return it
            }
        NodeTree.findInTree(root) { !isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
            ?.let { label ->
                findSwitchInRow(label, texts)?.let { sw ->
                    AppLog.w(TAG, "fuzzy label match next to switch")
                    return sw
                }
            }
        // Диагностика прогона: видно, что именно не сошлось (файл/строку ищем по логу).
        AppLog.w(TAG, "switch not found for '${texts.firstOrNull()}' (texts=${texts.size})")
        return null
    }

    /**
     * Переключатель строки подписи.
     *
     * Строка настройки MIUI содержит ДВА узла-тумблера: внешний `Switch` строки
     * (его `contentDescription` — текст строки) и внутренний `Switch id="checkbox"`
     * в `widget_frame`. Прежнее правило «ровно один тумблер в контейнере» из-за
     * этого отклоняло настоящие строки (`ambiguous row: switches=2`, `switch
     * belongs to another row` — прогон rmu8lzcu9: carousel, ads_personalization).
     *
     * Различаем строки по геометрии: тумблер строки пересекается по вертикали с
     * подписью, тумблер чужой строки — нет. Без геометрии (узлы без bounds)
     * сохраняется строгий контракт: контейнер с двумя тумблерами отклоняется.
     */
    fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchLike(node)) return node
        return findSwitchInRow(node)
    }

    /** Максимальная глубина подъёма по предкам при поиске строки настройки. */
    private const val MAX_ROW_DEPTH = 6

    /** Лимит собранных переключателей контейнера: строка MIUI отдаёт внешний + внутренний. */
    private const val SWITCH_COLLECT_LIMIT = 4

    private fun findSwitchInRow(
        label: AccessibilityNodeInfo,
        texts: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val labelBounds = boundsOf(label)
        var current: AccessibilityNodeInfo? = label.parent
        var depth = 0
        while (current != null && depth <= MAX_ROW_DEPTH) {
            val switches = collectSwitches(current)
            if (switches.size > 1) {
                val sameRow = switches.filter { sharesRow(it, labelBounds) }
                if (sameRow.isNotEmpty()) return pickNearest(sameRow, labelBounds, texts)
                AppLog.w(TAG, "ambiguous row: switches=${switches.size} — switch_not_found")
                return null
            }
            switches.firstOrNull()?.let { candidate ->
                val row = current
                if (sharesRow(candidate, labelBounds)) return candidate
                // Без геометрии действует прежний структурный контракт.
                return acceptIfSameRow(candidate, row)
            }
            current = current.parent
            depth++
        }
        AppLog.w(
            TAG,
            "row search failed: label='${label.text ?: label.contentDescription}' depth=$depth"
        )
        return null
    }

    /** Тумблер и подпись в одной визуальной строке (пересечение по вертикали). */
    private fun sharesRow(node: AccessibilityNodeInfo, labelBounds: Rect): Boolean {
        if (labelBounds.isEmpty) return false
        val b = boundsOf(node)
        if (b.isEmpty) return false
        return b.top < labelBounds.bottom && labelBounds.top < b.bottom
    }

    /** Выбор тумблера строки: совпадение по тексту → наибольшее пересечение с подписью. */
    private fun pickNearest(
        switches: List<AccessibilityNodeInfo>,
        labelBounds: Rect,
        texts: List<String>
    ): AccessibilityNodeInfo {
        if (switches.size == 1) return switches.first()
        if (texts.isNotEmpty()) {
            switches.firstOrNull { NodeTree.matchesAny(it, texts) }?.let { return it }
        }
        val best = switches.maxByOrNull { verticalOverlap(it, labelBounds) }
        AppLog.w(TAG, "row has ${switches.size} switches — pick nearest to label")
        return best ?: switches.first()
    }

    private fun verticalOverlap(node: AccessibilityNodeInfo, labelBounds: Rect): Int {
        val b = boundsOf(node)
        val top = maxOf(b.top, labelBounds.top)
        val bottom = minOf(b.bottom, labelBounds.bottom)
        return (bottom - top).coerceAtLeast(0) * 1000 + (b.width() * b.height()).coerceAtMost(999)
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    /** Тумблер принимается, только если он лежит в том же контейнере, что и подпись. */
    private fun acceptIfSameRow(
        switchNode: AccessibilityNodeInfo,
        row: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val switchRow = NodeTree.clickableAncestorOrSelf(switchNode)
        if (switchRow == null || switchRow === row) return switchNode
        AppLog.w(TAG, "switch belongs to another row — switch_not_found")
        return null
    }

    private fun collectSwitches(
        node: AccessibilityNodeInfo,
        limit: Int = SWITCH_COLLECT_LIMIT
    ): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(limit)
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > NodeTree.DEFAULT_MAX_DEPTH || result.size >= limit) return
            if (isSwitchLike(n)) {
                result.add(n)
                return
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return result
    }
}