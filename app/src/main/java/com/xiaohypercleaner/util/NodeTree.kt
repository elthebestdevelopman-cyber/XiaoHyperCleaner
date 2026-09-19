package com.xiaohypercleaner.util

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Общие операции над деревом AccessibilityNodeInfo.
 *
 * Единая реализация обхода, сбора текста и сопоставления подписей: раньше
 * эти хелперы дублировались в SimpleRunner, SwitchFinder и ComponentVerifier.
 * Все сравнения идут через [TextMatcher] — MIUI вставляет в подписи невидимые
 * символы, из-за чего точное сравнение не находит узлы.
 */
object NodeTree {

    /** Максимальная глубина обхода дерева при сборе текста. */
    const val DEFAULT_MAX_DEPTH = 20

    /** Обходит дерево в глубину и возвращает первый узел, подходящий под предикат. */
    fun findInTree(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findInTree(child, predicate)
            if (found != null) return found
        }
        return null
    }

    /** Собирает `text` и `contentDescription` всего поддерева. */
    fun collectText(
        node: AccessibilityNodeInfo?,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): String {
        node ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > maxDepth) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }

    /** Нормализованное сравнение текста/описания узла со списком искомых строк. */
    fun matchesAny(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        fuzzy: Boolean = false
    ): Boolean {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        for (q in texts) {
            val qn = TextMatcher.normalize(q)
            if (qn.isEmpty()) continue
            if (TextMatcher.normalizedEquals(text, qn) || TextMatcher.normalizedEquals(desc, qn)) return true
            if (qn.length >= 4 &&
                (TextMatcher.normalizedContains(text, qn) || TextMatcher.normalizedContains(desc, qn))
            ) return true
        }
        // Последний рубеж: нечёткое совпадение (опечатки/варианты прошивок).
        if (fuzzy) {
            for (q in texts) {
                if (TextMatcher.isFuzzyMatch(text, q, threshold = 0.88) ||
                    TextMatcher.isFuzzyMatch(desc, q, threshold = 0.88)
                ) return true
            }
        }
        return false
    }

    /**
     * Обходит дерево и возвращает ВСЕ узлы, подходящие под предикат (порядок —
     * обход в глубину). Нужен там, где мало первого совпадения: кандидаты-папки
     * рабочего стола, названия в поповере.
     */
    fun findAllInTree(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        maxDepth: Int = DEFAULT_MAX_DEPTH
    ): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth) return
            if (predicate(node)) result.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result
    }

    /** Ближайший кликабельный узел (сам узел или предок до 5 уровней). */
    fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }
}