package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay

/**
 * Выполнение шагов Simple Mode внутри Accessibility Service.
 *
 * ИСПРАВЛЕНО в этой версии:
 *  1. cancelled — поле экземпляра (не companion object), @Volatile для thread-safety
 *  2. findScrollableContainer ищет ВНУТРИ окна (вниз), а не вверх по родителям
 *  3. Thread.sleep заменён на delay (не блокирует Main thread)
 *  4. Misleading лог "stopping drill" исправлен на "assuming already inside"
 *  5. Правильный recycle в цикле скролла
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"
        private const val UI_SETTLE_DELAY_MS = 900L
        private const val APP_LAUNCH_DELAY_MS = 1800L
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SCROLL_SETTLE_DELAY_MS = 300L
    }

    // ИСПРАВЛЕНО: поле экземпляра, а не companion object
    // @Volatile гарантирует видимость между потоками
    @Volatile
    private var cancelled = false

    data class Result(
        val success: Boolean,
        val reason: String? = null,
        val skipped: Boolean = false
    )

    suspend fun run(step: SimpleSteps.Step): Result {
        cancelled = false
        AppLog.i(TAG, "Executing step: ${step.id} - ${step.titleEn}")

        if (cancelled) return Result(false, "cancelled")

        // Фаза 0: пропуск, если нужного приложения нет на устройстве
        if (step.requiredPackages.isNotEmpty() &&
            step.requiredPackages.none { isPackageInstalled(it) }
        ) {
            AppLog.i(TAG, "Step ${step.id}: app not installed — skipping")
            return Result(false, "app_not_installed", skipped = true)
        }

        // Фаза 1: запуск приложения (для шагов внутри чужих приложений)
        var appLaunched = false
        if (step.launchPackage != null) {
            appLaunched = launchApp(step.launchPackage)
            if (appLaunched) {
                delay(APP_LAUNCH_DELAY_MS)
            } else {
                AppLog.w(TAG, "Step ${step.id}: app launch failed, fallback to intents")
            }
        }

        // Фаза 2: intents — ТОЛЬКО если приложение не запустилось
        if (!appLaunched) {
            if (!openTargetScreen(step) && service.rootInActiveWindow == null) {
                AppLog.e(TAG, "Step ${step.id}: no screen opened")
                return Result(false, "no_screen_opened")
            }
            delay(UI_SETTLE_DELAY_MS)
        }

        if (cancelled) return Result(false, "cancelled")

        // Фаза 3: бурение вглубь по drillPath
        drillDown(step)

        if (cancelled) return Result(false, "cancelled")

        // Фаза 4: поиск и переключение целевого переключателя + верификация
        return findAndToggleSwitch(step)
    }

    /** Остановить runner извне (вызывается при клике «Отменить» в оверлее) */
    fun cancel() {
        cancelled = true
        AppLog.i(TAG, "Runner cancellation requested")
    }

    // ═══════════════════════════════════════════════════════════════
    // Фаза 1: запуск приложения
    // ═══════════════════════════════════════════════════════════════

    private fun launchApp(packageName: String): Boolean {
        val launchIntent = try {
            service.packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            null
        } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            AppLog.i(TAG, "Launched app: $packageName")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "launchApp failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Фаза 2: открытие экрана через intents
    // ═══════════════════════════════════════════════════════════════

    private suspend fun openTargetScreen(step: SimpleSteps.Step): Boolean {
        for (intent in step.intents) {
            if (cancelled) return false
            try {
                val resolved = intent.resolveActivity(service.packageManager)
                if (resolved == null && intent.action != null &&
                    !intent.action!!.startsWith("android.settings")
                ) {
                    AppLog.w(TAG, "Step ${step.id}: intent not found: ${intent.action}")
                    continue
                }
                service.startActivity(intent)
                delay(UI_SETTLE_DELAY_MS)
                val root = service.rootInActiveWindow
                if (root != null) {
                    AppLog.i(TAG, "Step ${step.id}: opened screen with ${intent.action}")
                    recycleNode(root)
                    return true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Step ${step.id}: intent failed: ${e.message}")
            }
        }
        val root = service.rootInActiveWindow
        if (root != null) {
            AppLog.i(TAG, "Step ${step.id}: using current screen")
            recycleNode(root)
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // Фаза 3: бурение вглубь (drillPath) + СКРОЛЛ
    // ═══════════════════════════════════════════════════════════════

    private suspend fun drillDown(step: SimpleSteps.Step) {
        for (level in step.drillPath) {
            if (cancelled) {
                AppLog.i(TAG, "Step ${step.id}: cancelled during drill")
                return
            }
            delay(UI_SETTLE_DELAY_MS)
            val root = service.rootInActiveWindow ?: break
            val node = findClickableByTextWithScroll(root, level)
            if (node != null) {
                AppLog.i(TAG, "Step ${step.id}: drilling into '${level.firstOrNull()}'")
                clickNode(node)
                recycleNode(node)
            } else {
                // ИСПРАВЛЕНО: drill продолжается, а не останавливается
                // Возможно, мы уже внутри нужного экрана (стартовали не с корня)
                AppLog.w(
                    TAG,
                    "Step ${step.id}: level '${level.firstOrNull()}' not found — assuming already inside, continue"
                )
            }
            recycleNode(root)
        }
        delay(UI_SETTLE_DELAY_MS)
    }

    /**
     * ИСПРАВЛЕНО: теперь suspend (использует delay вместо Thread.sleep)
     * и правильно переиспользует scrollable узел на каждой итерации.
     */
    private suspend fun findClickableByTextWithScroll(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        // Попытка 1: ищем без скролла
        var node = findClickableByText(root, texts)
        if (node != null) return node

        // Попытки 2..N: скроллим и ищем снова
        repeat(MAX_SCROLL_ATTEMPTS) { attempt ->
            if (cancelled) return null

            // ИСПРАВЛЕНО: получаем свежий scrollable на каждой итерации
            // (предыдущий мог быть recycled)
            val currentRoot = service.rootInActiveWindow ?: return null
            val scrollable = findScrollableContainer(currentRoot)
            recycleNode(currentRoot)

            if (scrollable == null) {
                AppLog.d(TAG, "No scrollable container found")
                return null
            }

            AppLog.d(TAG, "Scroll attempt ${attempt + 1}/$MAX_SCROLL_ATTEMPTS")
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            recycleNode(scrollable)

            if (!scrolled) {
                AppLog.d(TAG, "Cannot scroll further")
                return null
            }

            // ИСПРАВЛЕНО: delay вместо Thread.sleep (не блокирует Main thread)
            delay(SCROLL_SETTLE_DELAY_MS)

            val newRoot = service.rootInActiveWindow ?: return null
            node = findClickableByText(newRoot, texts)
            recycleNode(newRoot)
            if (node != null) {
                AppLog.i(TAG, "Found element after ${attempt + 1} scroll attempts")
                return node
            }
        }

        return null
    }

    /**
     * ИСПРАВЛЕНО: ищет первый скроллируемый контейнер ВНУТРИ дерева (вниз),
     * а не вверх по родителям. Обычно RecyclerView — это потомок root, не родитель.
     */
    private fun findScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root

        // Ищем вглубь: первый скроллируемый потомок
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollableContainer(child)
            if (found != null) {
                if (found !== child) recycleNode(child) else return found
                return found
            }
            recycleNode(child)
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // Фаза 4: поиск и переключение + верификация
    // ═══════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        if (cancelled) return Result(false, "cancelled")

        val root = service.rootInActiveWindow ?: return Result(false, "no_root_window")

        val switchNode = findSwitchByText(root, step.searchTexts)
        if (switchNode == null) {
            AppLog.w(TAG, "Step ${step.id}: no switch found - texts: ${step.searchTexts}")
            recycleNode(root)
            return Result(false, "switch_not_found")
        }

        val isChecked = switchNode.isChecked
        val targetState = step.targetChecked

        if (isChecked == targetState) {
            AppLog.i(TAG, "Step ${step.id}: already in target state (checked=$isChecked)")
            recycleNode(switchNode)
            recycleNode(root)
            return Result(true, "already_done")
        }

        val clickResult = if (switchNode.isClickable) {
            switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            clickParent(switchNode)
        }

        if (!clickResult) {
            AppLog.w(TAG, "Step ${step.id}: click failed on switch")
            recycleNode(switchNode)
            recycleNode(root)
            return Result(false, "click_failed")
        }

        recycleNode(switchNode)
        delay(UI_SETTLE_DELAY_MS)

        if (cancelled) return Result(false, "cancelled")

        // ВЕРИФИКАЦИЯ: повторно читаем состояние после клика
        val newRoot = service.rootInActiveWindow
        if (newRoot == null) {
            recycleNode(root)
            return Result(true, "toggled_no_verify")
        }
        val newSwitch = findSwitchByText(newRoot, step.searchTexts)
        val newChecked = newSwitch?.isChecked ?: !isChecked
        recycleNode(newSwitch)
        recycleNode(newRoot)
        recycleNode(root)

        return if (newChecked == targetState) {
            AppLog.i(TAG, "Step ${step.id}: toggled successfully to $targetState")
            Result(true, "toggled")
        } else {
            AppLog.w(TAG, "Step ${step.id}: toggle did not change state")
            Result(false, "toggle_failed")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Вспомогательные функции
    // ═══════════════════════════════════════════════════════════════

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            service.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            nodes.firstOrNull { it.isClickable }?.let { return it }
            for (node in nodes) {
                var cur: AccessibilityNodeInfo? = node
                var depth = 0
                while (cur != null && depth < MAX_PARENT_DEPTH) {
                    if (cur.isClickable) return cur
                    cur = cur.parent
                    depth++
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectNodesByClass(root, "android.widget.Switch", switches)
        collectNodesByClass(root, "android.widget.CheckBox", switches)
        collectNodesByClass(root, "androidx.appcompat.widget.SwitchCompat", switches)

        for (sw in switches) {
            if (findNearbyText(sw, texts) != null) return sw
        }

        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                var depth = 0
                while (current != null && depth < 6) {
                    val cls = current.className?.toString() ?: ""
                    if (cls.contains("Switch") || cls.contains("CheckBox")) return current
                    current = current.parent
                    depth++
                }
            }
        }
        return null
    }

    private fun findNearbyText(node: AccessibilityNodeInfo, texts: List<String>): String? {
        val parent = node.parent ?: return null
        val collected = collectAllText(parent)
        for (t in texts) {
            if (collected.contains(t, ignoreCase = true)) return t
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        collectTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 3) return
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextRecursive(it, sb, depth + 1) }
        }
    }

    private fun collectNodesByClass(
        node: AccessibilityNodeInfo,
        className: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className?.toString() == className) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodesByClass(it, className, result) }
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            clickParent(node)
        }
    }

    private fun clickParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION")
                node?.recycle()
            } catch (_: Exception) {
            }
        }
    }
}