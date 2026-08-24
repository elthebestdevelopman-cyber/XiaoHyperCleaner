package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Выполнение шагов Simple Mode.
 *
 * ИСПРАВЛЕНО в этой версии:
 *  1. Расширенный поиск тумблеров — ловит кастомные MIUI-виджеты
 *     (MiSwitch, Toggle и т.п.) и любые isCheckable узлы
 *  2. Поиск текста рядом проверяет 2 уровня предков (родитель + дедушка)
 *  3. При ненахождении — автоскролл списка до 3 раз
 *  4. Диагностика NO SWITCH FOUND печатает текст экрана
 *  5. Координаты ⋮/⚙ точно в тулбар (56dp сверху)
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"
        private const val UI_SETTLE_DELAY_MS = 900L
        private const val APP_LAUNCH_DELAY_MS = 2000L
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SCROLL_SETTLE_DELAY_MS = 350L
        private const val TAP_DURATION_MS = 100L
        private const val SWITCH_FALLBACK_SCROLLS = 3
    }

    @Volatile
    private var cancelled = false

    data class Result(
        val success: Boolean,
        val reason: String? = null,
        val skipped: Boolean = false
    )

    fun cancel() {
        cancelled = true
        AppLog.i(TAG, "Runner cancellation requested")
    }

    suspend fun run(step: SimpleSteps.Step): Result {
        cancelled = false
        AppLog.i(TAG, "Executing step: ${step.id}")

        if (step.requiredPackages.isNotEmpty() &&
            step.requiredPackages.none { isPackageInstalled(it) }
        ) {
            AppLog.i(TAG, "Step ${step.id}: app not installed — skipping")
            return Result(false, "app_not_installed", skipped = true)
        }

        var appLaunched = false
        if (step.launchPackage != null) {
            appLaunched = launchApp(step.launchPackage)
            if (appLaunched) {
                delay(APP_LAUNCH_DELAY_MS)
                if (step.swipeUpAfterLaunch) swipeUp()
                delay(UI_SETTLE_DELAY_MS)
            }
        }
        if (!appLaunched) {
            if (!openTargetScreen(step) && service.rootInActiveWindow == null) {
                return Result(false, "no_screen_opened")
            }
            delay(UI_SETTLE_DELAY_MS)
        }
        if (cancelled) return Result(false, "cancelled")

        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE) {
            return clearDataAndDecline(step)
        }

        if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)

        drillDown(step)
        if (cancelled) return Result(false, "cancelled")

        val result = findAndToggleSwitch(step)

        if (result.success && step.confirmTexts.isNotEmpty()) {
            delay(1000)
            val root = service.rootInActiveWindow
            if (root != null) {
                val btn = findClickableByTextWithScroll(root, step.confirmTexts)
                if (btn != null) {
                    AppLog.i(TAG, "Step ${step.id}: confirmation dialog found, waiting timer")
                    if (step.confirmWaitMs > 0) delay(step.confirmWaitMs)
                    tapNode(btn)
                    recycleNode(btn)
                }
                recycleNode(root)
            }
        }

        if (result.success && step.additionalToggles.isNotEmpty()) {
            toggleAdditional(step.additionalToggles)
        }

        return result
    }

    // ═══ Жесты (как в SD Maid) ═══

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        }

    private suspend fun swipeUp() {
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.75f)
            lineTo(x, dm.heightPixels * 0.30f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        suspendCancellableCoroutine<Unit> { cont ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }, null)
        }
        delay(UI_SETTLE_DELAY_MS)
    }

    // ═══ Спец-маршрут Проводника ═══

    private suspend fun clearDataAndDecline(step: SimpleSteps.Step): Result {
        val root1 = service.rootInActiveWindow ?: return Result(false, "no_root")
        val clearBtn = findClickableByTextWithScroll(
            root1, listOf(
                "Очистить данные", "Clear data", "Очистить хранилище",
                "Clear storage", "Очистить все", "Clear all", "Очистить", "Clear"
            )
        )
        recycleNode(root1)
        if (clearBtn == null) return Result(false, "clear_button_not_found")
        tapNode(clearBtn); recycleNode(clearBtn)
        delay(800)

        val root2 = service.rootInActiveWindow ?: return Result(false, "no_root")
        val allBtn = findClickableByText(root2, listOf("Очистить все", "Clear all"))
        recycleNode(root2)
        if (allBtn == null) return Result(false, "clear_all_not_found")
        tapNode(allBtn); recycleNode(allBtn)
        delay(1200)

        step.launchPackage?.let { launchApp(it) }
        delay(2500)
        val root3 = service.rootInActiveWindow ?: return Result(true, "cleared_no_welcome")
        val cancelBtn = findClickableByText(root3, listOf("Отмена", "Cancel"))
        recycleNode(root3)
        if (cancelBtn != null) {
            tapNode(cancelBtn); recycleNode(cancelBtn)
            AppLog.i(TAG, "Step ${step.id}: declined welcome dialog")
        }
        return Result(true, "cleared_and_declined")
    }

    // ═══ Дополнительные тумблеры ═══

    @Suppress("DEPRECATION")
    private suspend fun toggleAdditional(texts: List<String>) {
        for (t in texts) {
            if (cancelled) return
            delay(400)
            val root = service.rootInActiveWindow ?: break
            val sw = findSwitchByText(root, listOf(t))
            if (sw != null) {
                if (sw.isChecked) {
                    AppLog.i(TAG, "Toggling additional: $t")
                    tapNode(sw)
                }
                recycleNode(sw)
            }
            recycleNode(root)
        }
    }

    // ═══ Стандартные фазы ═══

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
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun openTargetScreen(step: SimpleSteps.Step): Boolean {
        for (intent in step.intents) {
            if (cancelled) return false
            try {
                val resolved = intent.resolveActivity(service.packageManager)
                if (resolved == null && intent.action != null &&
                    !intent.action!!.startsWith("android.settings")
                ) continue
                service.startActivity(intent)
                delay(UI_SETTLE_DELAY_MS)
                val root = service.rootInActiveWindow
                if (root != null) {
                    recycleNode(root); return true
                }
            } catch (e: Exception) {
            }
        }
        val root = service.rootInActiveWindow
        if (root != null) {
            recycleNode(root); return true
        }
        return false
    }

    private suspend fun drillDown(step: SimpleSteps.Step) {
        for (level in step.drillPath) {
            if (cancelled) return
            delay(UI_SETTLE_DELAY_MS)
            val root = service.rootInActiveWindow ?: break
            val node = findClickableByTextWithScroll(root, level)
            if (node != null) {
                AppLog.i(TAG, "Step ${step.id}: drilling into '${level.firstOrNull()}'")
                tapNode(node)
                recycleNode(node)
            } else if (level.contains("⋮") || level.contains("⚙") || level.contains("⚙️")) {
                AppLog.i(
                    TAG,
                    "Step ${step.id}: '${level.firstOrNull()}' not found by text — tapping top-right"
                )
                tapTopRight()
            } else {
                AppLog.w(
                    TAG,
                    "Step ${step.id}: level '${level.firstOrNull()}' not found — assuming inside"
                )
            }
            recycleNode(root)
        }
        delay(UI_SETTLE_DELAY_MS)
    }

    /**
     * ИСПРАВЛЕНО: координата Y = 56dp (точно в тулбар),
     * раньше было 88dp и тап уходил ниже в поиск.
     */
    private suspend fun tapTopRight(): Boolean {
        val dm = service.resources.displayMetrics
        return tapAt(dm.widthPixels - dp(48).toFloat(), dp(56).toFloat())
    }

    private fun dp(v: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
        service.resources.displayMetrics
    ).toInt()

    private suspend fun findClickableByTextWithScroll(
        root: AccessibilityNodeInfo?, texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        findClickableByText(root, texts)?.let { return it }

        repeat(MAX_SCROLL_ATTEMPTS) {
            if (cancelled) return null
            val currentRoot = service.rootInActiveWindow ?: return null
            val scrollable = findScrollableContainer(currentRoot)
            recycleNode(currentRoot)
            if (scrollable == null) return null
            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            recycleNode(scrollable)
            if (!scrolled) return null
            delay(SCROLL_SETTLE_DELAY_MS)
            val newRoot = service.rootInActiveWindow ?: return null
            val found = findClickableByText(newRoot, texts)
            recycleNode(newRoot)
            if (found != null) return found
        }
        return null
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollableContainer(child)
            if (found != null) {
                if (found !== child) recycleNode(child); return found
            }
            recycleNode(child)
        }
        return null
    }

    /**
     * ИСПРАВЛЕНО: при ненахождении тумблера сначала скроллим список,
     * а при полном фейле печатаем текст экрана (NO SWITCH FOUND) для диагностики.
     */
    @Suppress("DEPRECATION")
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        if (cancelled) return Result(false, "cancelled")
        val root = service.rootInActiveWindow ?: return Result(false, "no_root_window")

        var switchNode = findSwitchByText(root, step.searchTexts)

        // Не нашли — пробуем скроллить (тумблер может быть ниже)
        if (switchNode == null && step.searchTexts.isNotEmpty()) {
            repeat(SWITCH_FALLBACK_SCROLLS) {
                if (cancelled) return Result(false, "cancelled")
                val r = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                val scrollable = findScrollableContainer(r)
                if (scrollable == null) {
                    recycleNode(r); return@repeat
                }
                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                recycleNode(scrollable); recycleNode(r)
                if (!scrolled) return@repeat
                delay(400)
                val r2 = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                switchNode = findSwitchByText(r2, step.searchTexts)
                recycleNode(r2)
                if (switchNode != null) return@repeat
            }
        }

        if (switchNode == null) {
            // ДИАГНОСТИКА: что реально на экране — по этому логу чиним маршруты
            val screenText = collectAllText(service.rootInActiveWindow).take(500)
            AppLog.w(TAG, "Step ${step.id}: NO SWITCH FOUND. Screen: $screenText")
            recycleNode(root)
            return Result(false, "switch_not_found")
        }

        val isChecked = switchNode.isChecked
        if (isChecked == step.targetChecked) {
            AppLog.i(TAG, "Step ${step.id}: already in target state (checked=$isChecked)")
            recycleNode(switchNode); recycleNode(root)
            return Result(true, "already_done")
        }

        val clicked = tapNode(switchNode)
        recycleNode(switchNode)
        if (!clicked) {
            recycleNode(root); return Result(false, "click_failed")
        }
        delay(UI_SETTLE_DELAY_MS)

        val newRoot = service.rootInActiveWindow
        if (newRoot == null) {
            recycleNode(root); return Result(true, "toggled_no_verify")
        }
        val newSwitch = findSwitchByText(newRoot, step.searchTexts)
        val newChecked = newSwitch?.isChecked ?: !isChecked
        recycleNode(newSwitch); recycleNode(newRoot); recycleNode(root)
        return if (newChecked == step.targetChecked) Result(true, "toggled")
        else Result(false, "toggle_failed")
    }

    // ═══ Поиск узлов ═══

    private fun isPackageInstalled(p: String) = try {
        service.packageManager.getPackageInfo(p, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo?, texts: List<String>
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
                    cur = cur.parent; depth++
                }
            }
        }
        return null
    }

    /**
     * ИСПРАВЛЕНО: ищет ЛЮБЫЕ switch-like узлы (кастомные MIUI, isCheckable),
     * а не только android.widget.Switch/CheckBox/SwitchCompat.
     */
    @Suppress("DEPRECATION")
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?, texts: List<String>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitchLike(root, switches)

        for (sw in switches) {
            if (findNearbyText(sw, texts) != null) return sw
        }

        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                var cur: AccessibilityNodeInfo? = node
                var depth = 0
                while (cur != null && depth < 6) {
                    val cls = cur.className?.toString() ?: ""
                    if (cls.contains("Switch") || cls.contains("CheckBox") ||
                        cls.contains("Toggle") || cur.isCheckable
                    ) return cur
                    cur = cur.parent; depth++
                }
            }
        }
        return null
    }

    /**
     * ИСПРАВЛЕНО: собирает все switch-like узлы рекурсивно.
     * Ловит: android.widget.Switch, CheckBox, SwitchCompat, MiSwitch,
     * Toggle, а также любые isCheckable узлы (кастомные MIUI).
     */
    private fun collectSwitchLike(
        node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>
    ) {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch") || cls.contains("CheckBox") ||
            cls.contains("Toggle") || node.isCheckable
        ) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSwitchLike(it, result) }
        }
    }

    /**
     * ИСПРАВЛЕНО: проверяем текст на родителе И дедушке.
     * На MIUI заголовок часто лежит выше тумблера на 2 уровня.
     */
    private fun findNearbyText(node: AccessibilityNodeInfo, texts: List<String>): String? {
        var anc: AccessibilityNodeInfo? = node.parent
        repeat(2) {
            val a = anc ?: return null
            val collected = collectAllText(a)
            for (t in texts) if (collected.contains(t, ignoreCase = true)) return t
            anc = a.parent
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        collectTextRecursive(node, sb, 0)
        return sb.toString()
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 3) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectTextRecursive(node.getChild(i), sb, depth + 1)
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION") node?.recycle()
            } catch (_: Exception) {
            }
        }
    }
}