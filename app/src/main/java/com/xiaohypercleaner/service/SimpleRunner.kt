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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * ФИНАЛЬНАЯ версия SimpleRunner.
 *
 * Архитектура (как в SD Maid):
 *   1. resetToHome() перед шагом — сброс стека активити MIUI
 *   2. openTargetScreen: NEW_TASK + CLEAR_TASK — открытие с корня
 *   3. waitForContentRoot() — ждём загрузку контента (иначе читаем «Назад»)
 *   4. performAction по узлу → по родителю → жест (сквозь оверлей)
 *   5. tapTopRight: accessibility → жест по нескольким Y
 *   6. toggleAdditional() — выключаем СВЯЗАННЫЕ тумблеры
 *   7. MSA: повторная попытка, если диалог подтверждения не сработал
 *   8. Живые статусы + таймаут 20с (не «молчит»)
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"
        private const val STEP_TIMEOUT_MS = 20_000L
        private const val UI_SETTLE_DELAY_MS = 900L
        private const val APP_LAUNCH_DELAY_MS = 2000L
        private const val POPUP_MENU_DELAY_MS = 1200L
        private const val HOME_RESET_DELAY_MS = 500L
        private const val CONTENT_WAIT_MS = 2500L
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SCROLL_SETTLE_DELAY_MS = 350L
        private const val TAP_DURATION_MS = 100L
        private const val SWITCH_FALLBACK_SCROLLS = 3
        private const val TEXT_DEPTH = 7
        private const val NEARBY_ANCESTORS = 3

        private val OVERFLOW_TEXTS = listOf(
            "Ещё", "More options", "Дополнительно", "Другие параметры", "⋮"
        )
        private val TOP_RIGHT_Y_DP = listOf(56, 76, 96)
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
        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${STEP_TIMEOUT_MS}ms)")
        return withTimeoutOrNull(STEP_TIMEOUT_MS) {
            runInternal(step)
        } ?: run {
            AppLog.e(TAG, "Step ${step.id}: TIMEOUT after ${STEP_TIMEOUT_MS}ms")
            Result(false, "timeout")
        }
    }

    private suspend fun runInternal(step: SimpleSteps.Step): Result {
        if (step.requiredPackages.isNotEmpty() &&
            step.requiredPackages.none { isPackageInstalled(it) }
        ) {
            AppLog.i(TAG, "Step ${step.id}: app not installed — skipping")
            return Result(false, "app_not_installed", skipped = true)
        }

        resetToHome()

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
                AppLog.w(TAG, "Step ${step.id}: no screen opened")
                return Result(false, "no_screen_opened")
            }
            delay(UI_SETTLE_DELAY_MS)
        }
        AppLog.i(
            TAG,
            "Step ${step.id}: start screen=[${collectAllText(service.rootInActiveWindow).take(150)}]"
        )
        if (cancelled) return Result(false, "cancelled")

        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE) {
            return clearDataAndDecline(step)
        }

        if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)

        drillDown(step)
        if (cancelled) return Result(false, "cancelled")

        var result = findAndToggleSwitch(step)

        // НОВОЕ: для шагов с диалогом (msa) — повтор, если тумблер не переключился
        if (!result.success && step.confirmTexts.isNotEmpty() && !cancelled) {
            AppLog.w(TAG, "Step ${step.id}: retry toggle (confirmation may have missed)")
            OverlayController.updateStatus(service, "Повторная попытка…")
            result = findAndToggleSwitch(step)
        }
        return result
    }

    // ═══ Сброс стека активити (критично для MIUI) ═══

    private suspend fun resetToHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(home)
            delay(HOME_RESET_DELAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "resetToHome failed: ${e.message}")
        }
    }

    /** Ждём, пока контент экрана загрузится (текст > 30 символов) */
    private suspend fun waitForContentRoot(): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < CONTENT_WAIT_MS) {
            if (cancelled) return null
            val root = service.rootInActiveWindow
            if (root != null) {
                if (collectAllText(root).length > 30) return root
                recycleNode(root)
            }
            delay(300)
        }
        return service.rootInActiveWindow
    }

    // ═══ Лесенка кликов (как в SD Maid) ═══

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AppLog.i(TAG, "tapNode: node ACTION_CLICK ok")
            return true
        }
        if (clickParent(node)) {
            AppLog.i(TAG, "tapNode: parent ACTION_CLICK ok")
            return true
        }
        AppLog.i(TAG, "tapNode: falling back to gesture through overlay")
        var done = false
        withOverlayPassThrough {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            done = tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return done
    }

    private fun clickParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (cur != null && depth < MAX_PARENT_DEPTH) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private suspend fun withOverlayPassThrough(block: suspend () -> Unit) {
        try {
            OverlayController.setBlocking(service, false)
        } catch (_: Exception) {
        }
        delay(250)
        block()
        delay(400)
        try {
            OverlayController.setBlocking(service, true)
        } catch (_: Exception) {
        }
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
        ) ?: searchAllWindows(listOf("Очистить данные", "Clear data"))
        recycleNode(root1)
        if (clearBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: clear button not found")
            return Result(false, "clear_button_not_found")
        }
        tapNode(clearBtn); recycleNode(clearBtn)
        delay(800)

        val root2 = service.rootInActiveWindow ?: return Result(false, "no_root")
        val allBtn = findClickableByText(
            root2, listOf(
                "Очистить все", "Clear all", "Очистить все данные", "Очистить данные", "Clear data"
            )
        ) ?: searchAllWindows(listOf("Очистить все", "Clear all"))
        recycleNode(root2)
        if (allBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: 'Очистить все' not found")
            return Result(false, "clear_all_not_found")
        }
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

    // ═══ Дополнительные (связанные) тумблеры ═══

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
            } else {
                AppLog.w(TAG, "Additional toggle not found: $t")
            }
            recycleNode(root)
        }
    }

    // ═══ Открытие экранов ═══

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

    /** NEW_TASK + CLEAR_TASK — открывает КОРНЕВОЙ экран, а не старый из recents */
    private suspend fun openTargetScreen(step: SimpleSteps.Step): Boolean {
        for (intent in step.intents) {
            if (cancelled) return false
            try {
                val resolved = intent.resolveActivity(service.packageManager)
                if (resolved == null && intent.action != null &&
                    !intent.action!!.startsWith("android.settings")
                ) continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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

    private fun searchAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (w in service.windows) {
                val r = w.root ?: continue
                val found = findClickableByText(r, texts)
                if (found != null) return found
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findSwitchInAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (w in service.windows) {
                val r = w.root ?: continue
                val found = findSwitchByText(r, texts)
                if (found != null) return found
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun drillDown(step: SimpleSteps.Step) {
        for ((index, level) in step.drillPath.withIndex()) {
            if (cancelled) return
            val root = waitForContentRoot()
            var node = if (root != null) findClickableByTextWithScroll(root, level) else null
            if (node == null) node = searchAllWindows(level)
            if (node != null) {
                AppLog.i(TAG, "Step ${step.id}: drilling into '${level.firstOrNull()}'")
                tapNode(node)
                recycleNode(node)
            } else if (level.contains("⋮") || level.contains("⚙") || level.contains("⚙️")) {
                AppLog.i(
                    TAG,
                    "Step ${step.id}: '${level.firstOrNull()}' not found — tapping top-right"
                )
                tapTopRight(step.drillPath.getOrNull(index + 1) ?: emptyList())
                delay(POPUP_MENU_DELAY_MS)
            } else {
                AppLog.w(
                    TAG,
                    "Step ${step.id}: level '${level.firstOrNull()}' not found — assuming inside"
                )
            }
            if (root != null) recycleNode(root)
        }
        delay(UI_SETTLE_DELAY_MS)
    }

    /** accessibility-поиск overflow → жест по нескольким Y */
    private suspend fun tapTopRight(expected: List<String>): Boolean {
        val root = service.rootInActiveWindow
        if (root != null) {
            val overflow = findClickableByText(root, OVERFLOW_TEXTS)
            if (overflow != null) {
                val ok = tapNode(overflow)
                recycleNode(overflow); recycleNode(root)
                if (ok) {
                    AppLog.i(TAG, "tapTopRight: overflow clicked via accessibility")
                    return true
                }
            } else {
                recycleNode(root)
            }
        }
        AppLog.i(TAG, "tapTopRight: fallback to gesture (multi-Y)")
        withOverlayPassThrough {
            val dm = service.resources.displayMetrics
            val x = dm.widthPixels - dp(24).toFloat()
            for (yDp in TOP_RIGHT_Y_DP) {
                tapAt(x, dp(yDp).toFloat())
                delay(500)
                if (expected.isNotEmpty() && searchAllWindows(expected) != null) {
                    AppLog.i(TAG, "tapTopRight: menu opened at y=${yDp}dp")
                    return@withOverlayPassThrough
                }
            }
        }
        return true
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

    @Suppress("DEPRECATION")
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        if (cancelled) return Result(false, "cancelled")
        val root = waitForContentRoot() ?: return Result(false, "no_root_window")

        var switchNode = findSwitchByText(root, step.searchTexts)

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
            switchNode = findSwitchInAllWindows(step.searchTexts)
        }

        if (switchNode == null) {
            val screenText = collectAllText(root).take(600)
            AppLog.w(TAG, "Step ${step.id}: NO SWITCH FOUND. screen=[$screenText]")
            recycleNode(root)
            return Result(false, "switch_not_found")
        }

        val isChecked = switchNode.isChecked
        AppLog.i(
            TAG,
            "Step ${step.id}: switch found, isChecked=$isChecked, target=${step.targetChecked}"
        )
        if (isChecked == step.targetChecked) {
            // уже выключено — но проверим связанные тумблеры
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            recycleNode(switchNode); recycleNode(root)
            return Result(true, "already_done")
        }

        val clicked = tapNode(switchNode)
        AppLog.i(TAG, "Step ${step.id}: tapNode result=$clicked")
        recycleNode(switchNode)
        if (!clicked) {
            recycleNode(root); return Result(false, "click_failed")
        }
        delay(UI_SETTLE_DELAY_MS)

        // Диалог подтверждения (msa: 10с) — с живым статусом
        if (step.confirmTexts.isNotEmpty()) {
            OverlayController.updateStatus(service, "Ждём диалог подтверждения…")
            val btn = searchAllWindows(step.confirmTexts)
            if (btn != null) {
                AppLog.i(
                    TAG,
                    "Step ${step.id}: confirmation dialog found, waiting ${step.confirmWaitMs}ms"
                )
                if (step.confirmWaitMs > 0) delay(step.confirmWaitMs)
                tapNode(btn)
                recycleNode(btn)
                delay(UI_SETTLE_DELAY_MS)
            }
        }

        val newRoot = service.rootInActiveWindow
        if (newRoot == null) {
            recycleNode(root); return Result(true, "toggled_no_verify")
        }
        val newSwitch = findSwitchByText(newRoot, step.searchTexts)
        val newChecked = newSwitch?.isChecked ?: !isChecked
        AppLog.i(TAG, "Step ${step.id}: verify newChecked=$newChecked")
        recycleNode(newSwitch); recycleNode(newRoot); recycleNode(root)

        if (newChecked == step.targetChecked) {
            // ИСПРАВЛЕНО: выключаем СВЯЗАННЫЕ тумблеры
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            return Result(true, "toggled")
        }
        return Result(false, "toggle_failed")
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

    @Suppress("DEPRECATION")
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?, texts: List<String>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitchLike(root, switches)
        for (sw in switches) if (findNearbyText(sw, texts) != null) return sw

        for (id in SWITCH_VIEW_IDS) {
            val byId = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in byId) if (findNearbyText(n, texts) != null) return n
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

    private val SWITCH_VIEW_IDS = listOf(
        "com.android.settings:id/switch_widget",
        "android:id/switch_widget",
        "com.miui.securitycenter:id/switch_widget",
        "com.miui.settings:id/switch_widget"
    )

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

    private fun findNearbyText(node: AccessibilityNodeInfo, texts: List<String>): String? {
        var anc: AccessibilityNodeInfo? = node.parent
        repeat(NEARBY_ANCESTORS) {
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
        if (node == null || depth > TEXT_DEPTH) return
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