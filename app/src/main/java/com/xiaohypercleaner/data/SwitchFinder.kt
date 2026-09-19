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
     * Ищет переключатель по тексту/описанию. Строгое правило: совпадение ключа
     * обязано быть в text/desc самого switch ИЛИ в строке-контейнере, где лежит
     * ровно один переключатель. Фолбэк «первый switch на экране» запрещён как
     * класс — именно он давал ложные OK (bounds совпадали с чужим тумблером).
     *
     * 1. сам переключатель с подходящим текстом;
     * 2. подпись (TextView), в строке которой ровно один переключатель;
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
            ?.let { label -> findSwitchInRow(label)?.let { sw -> return sw } }

        NodeTree.findInTree(root) { isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
            ?.let {
                AppLog.w(TAG, "fuzzy switch match on node")
                return it
            }
        NodeTree.findInTree(root) { !isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
            ?.let { label ->
                findSwitchInRow(label)?.let { sw ->
                    AppLog.w(TAG, "fuzzy label match next to switch")
                    return sw
                }
            }
        return null
    }

    /**
     * Переключатель строки подписи: поднимаемся по предкам и в первом контейнере,
     * где найден РОВНО ОДИН переключатель, возвращаем его. Контейнер с двумя и
     * более переключателями — не строка настройки: переключать чужой тумблер
     * запрещено, поиск отклоняется ([AppLog] логирует отказ).
     */
    fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchLike(node)) return node
        return findSwitchInRow(node)
    }

    /** Максимальная глубина подъёма по предкам при поиске строки настройки. */
    private const val MAX_ROW_DEPTH = 6

    /** Лимит собранных переключателей: достаточно, чтобы различить 1 и «больше одного». */
    private const val SWITCH_COLLECT_LIMIT = 2

    private fun findSwitchInRow(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Кликабельная строка подписи: в ней обязан быть ровно один тумблер,
        //    и он должен принадлежать этой же строке.
        val labelRow = NodeTree.clickableAncestorOrSelf(label)
        if (labelRow != null) {
            val switches = collectSwitches(labelRow)
            if (switches.size > 1) {
                AppLog.w(TAG, "ambiguous row: switches=${switches.size} — switch_not_found")
                return null
            }
            switches.firstOrNull()?.let { candidate -> return acceptIfSameRow(candidate, labelRow) }
        }

        // 2. Строка без кликабельного предка: поднимаемся по предкам, принимая только
        //    контейнер с РОВНО ОДНИМ тумблером, который принадлежит этому контейнеру.
        var current: AccessibilityNodeInfo? = label.parent
        var depth = 0
        while (current != null && depth <= MAX_ROW_DEPTH) {
            val switches = collectSwitches(current)
            if (switches.size > 1) {
                AppLog.w(TAG, "ambiguous row: switches=${switches.size} — switch_not_found")
                return null
            }
            switches.firstOrNull()?.let { candidate -> return acceptIfSameRow(candidate, current) }
            current = current.parent
            depth++
        }
        return null
    }

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