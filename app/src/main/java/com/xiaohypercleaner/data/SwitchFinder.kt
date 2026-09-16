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
     * Ищет переключатель по тексту/описанию (3 прохода + нечёткий последний рубеж):
     * 1. сам переключатель с подходящим текстом;
     * 2. подпись (TextView), рядом с которой лежит переключатель;
     * 3. нечёткое совпадение текста (варианты прошивок/опечатки).
     */
    fun findSwitch(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        NodeTree.findInTree(root) { isSwitchLike(it) && NodeTree.matchesAny(it, texts) }
            ?.let { return it }

        val labelNode = NodeTree.findInTree(root) { !isSwitchLike(it) && NodeTree.matchesAny(it, texts) }
        labelNode?.let { findSwitchNear(it)?.let { sw -> return sw } }

        NodeTree.findInTree(root) { isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
            ?.let {
                AppLog.w(TAG, "fuzzy switch match on node")
                return it
            }
        val fuzzyLabel = NodeTree.findInTree(root) { !isSwitchLike(it) && NodeTree.matchesAny(it, texts, fuzzy = true) }
        fuzzyLabel?.let { fl ->
            findSwitchNear(fl)?.let { sw ->
                AppLog.w(TAG, "fuzzy label match next to switch")
                return sw
            }
        }
        return null
    }

    /**
     * Ищет переключатель рядом с текстовой подписью.
     * Сначала — в поддереве ближайшего кликабельного предка (строка настройки):
     * на экранах уведомлений/Карусели CheckBox вложен в sibling-контейнер
     * (widget_frame), а не является прямым соседом подписи. Затем — соседи/вверх.
     */
    fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSwitchLike(node)) return node
        NodeTree.clickableAncestorOrSelf(node)?.let { row ->
            NodeTree.findInTree(row) { isSwitchLike(it) }?.let { return it }
        }
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            val parent = current.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling === node) continue
                    NodeTree.findInTree(sibling) { isSwitchLike(it) }?.let { return it }
                }
            }
            current = parent
            depth++
        }
        return null
    }
}