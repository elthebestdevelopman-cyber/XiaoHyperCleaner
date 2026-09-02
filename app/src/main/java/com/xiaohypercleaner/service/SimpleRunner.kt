package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
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
 * SimpleRunner — UI-автоматизация Simple Mode.
 *
 * Критичные фиксы:
 * - getBestRoot(): окно целевого пакета (Settings/app), не оверлей
 * - resolvePackage(): launch/notifications по реально установленному alias
 * - MSA: клик «Отозвать» сразу как кнопка enabled, без слепого ожидания 11с
 * - settings-шаги без resetToHome (экономия бюджета таймаута)
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"

        private const val BASE_STEP_TIMEOUT_MS = 35_000L
        private const val MSA_STEP_TIMEOUT_MS = 65_000L
        private const val CONFIRM_TIMEOUT_EXTRA_MS = 3_000L
        private const val SCREEN_WAIT_MS = 3_500L
        private const val CONTENT_WAIT_MS = 3_500L
        private const val UI_SETTLE_DELAY_MS = 400L
        private const val POPUP_MENU_DELAY_MS = 1_000L
        private const val HOME_RESET_DELAY_MS = 400L
        private const val FORCE_STOP_DELAY_MS = 500L
        private const val SCROLL_SETTLE_DELAY_MS = 350L
        private const val TAP_DURATION_MS = 100L
        private const val RETRY_DELAY_MS = 800L

        private const val MAX_POLLING_MS = 2_500L
        private const val POLLING_INTERVAL_MS = 150L
        private const val MIN_CONTENT_LENGTH = 8

        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 4
        private const val SWITCH_FALLBACK_SCROLLS = 3
        private const val TEXT_DEPTH = 12
        private const val NEARBY_ANCESTORS = 3
        private const val SWITCH_ANCESTOR_DEPTH = 6
        private const val MAX_SCROLL_WITHOUT_PROGRESS = 2

        private val TOP_RIGHT_Y_DP = listOf(56, 76, 96)

        private val OVERFLOW_TEXTS = listOf(
            "Ещё", "More options", "Дополнительно", "Другие параметры", "⋮"
        )

        /** Свежий телефон: сначала уход / пропуск, не «Согласиться» */
        private val SKIP_TEXTS = listOf(
            "Пропустить", "Skip", "Позже", "Later", "Не сейчас", "Not now",
            "Закрыть", "Close", "Нет, спасибо", "No thanks", "Без входа",
            "Continue without account", "Гостевой режим", "Guest",
            "Напомнить позже", "Remind me later", "Не входить", "Skip login"
        )

        private val DECLINE_TEXTS = listOf(
            "Отмена", "Cancel", "Отклонить", "Decline", "Не согласен", "Disagree",
            "Запретить", "Deny", "Don't allow", "Не разрешать"
        )

        /** Чтобы войти в приложение и дойти до тумблеров рекламы */
        private val ENTER_TEXTS = listOf(
            "Согласиться", "Принять", "Agree", "Accept", "Начать", "Start",
            "Продолжить", "Continue", "Понятно", "Got it", "Далее", "Next",
            "OK", "ОК", "Хорошо", "Done"
        )

        private val PERMISSION_ALLOW_TEXTS = listOf(
            "Разрешить", "Allow", "While using the app", "При использовании",
            "Только в этот раз", "Only this time"
        )

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.securitycore",
            "com.xiaomi.misettings"
        )

        private const val MAX_FRESH_DISMISS_ROUNDS = 6

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    @Volatile
    private var cancelled: Boolean = false

    /** Пакеты, которые считаем «целевым» окном на текущем шаге */
    private var preferredPackages: Set<String> = emptySet()

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
        isRunning = true

        val effectiveTimeoutMs: Long = if (step.id == "msa") {
            MSA_STEP_TIMEOUT_MS
        } else {
            BASE_STEP_TIMEOUT_MS + step.preDrillWaitMs +
                    if (step.confirmWaitMs > 0L) step.confirmWaitMs + CONFIRM_TIMEOUT_EXTRA_MS else 0L
        }

        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${effectiveTimeoutMs}ms)")

        return try {
            withTimeoutOrNull(effectiveTimeoutMs) { runInternal(step) }
                ?: run {
                    val screenText = try {
                        getBestRoot()?.let { collectAllText(it).take(200) } ?: "no_root"
                    } catch (_: Exception) {
                        "error"
                    }
                    AppLog.e(TAG, "Step ${step.id}: TIMEOUT, screen=[$screenText]")
                    Result(false, "timeout")
                }
        } finally {
            isRunning = false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // КЛЮЧЕВОЙ ФИКС (beta10): лучшее окно вместо rootInActiveWindow
    // ═══════════════════════════════════════════════════════════════

    /**
     * Корень целевого окна: предпочитаем preferredPackages / Settings,
     * никогда не берём окно нашего пакета (оверлей).
     */
    private fun getBestRoot(): AccessibilityNodeInfo? {
        var bestPreferred: AccessibilityNodeInfo? = null
        var bestPreferredScore = -1L
        var bestOther: AccessibilityNodeInfo? = null
        var bestOtherScore = -1L

        try {
            for (w in service.windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: continue
                if (pkg == service.packageName) continue

                val textLen = collectAllText(r).length.toLong()
                val score = textLen +
                        (if (w.isFocused) 100_000L else 0L) +
                        (if (w.isActive) 50_000L else 0L)

                val isPreferred = preferredPackages.contains(pkg) ||
                        SETTINGS_PACKAGES.contains(pkg)

                if (isPreferred) {
                    if (score > bestPreferredScore) {
                        bestPreferredScore = score
                        bestPreferred = r
                    }
                } else if (score > bestOtherScore) {
                    bestOtherScore = score
                    bestOther = r
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getBestRoot scan failed: ${e.message}")
        }

        val chosen = bestPreferred ?: bestOther
        if (chosen != null) return chosen

        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() != service.packageName) {
            return active
        }
        return null
    }

    /** Polling: ждём контент в лучшем окне, выходим сразу как готов */
    private suspend fun waitForContent(maxMs: Long = MAX_POLLING_MS): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (cancelled) return false
            val root = getBestRoot()
            if (root != null && collectAllText(root).length >= MIN_CONTENT_LENGTH) {
                return true
            }
            delay(POLLING_INTERVAL_MS)
        }
        return false
    }

    private suspend fun waitForContentRoot(): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < CONTENT_WAIT_MS) {
            if (cancelled) return null
            val root = getBestRoot()
            if (root != null && collectAllText(root).length > MIN_CONTENT_LENGTH) {
                return root
            }
            delay(300L)
        }
        return getBestRoot()
    }

    // ═══════════════════════════════════════════════════════════════
    // Пайплайн шага
    // ═══════════════════════════════════════════════════════════════

    private suspend fun runInternal(step: SimpleSteps.Step): Result {
        val resolvedPkg = resolveInstalledPackage(step)
        if (step.requiredPackages.isNotEmpty() && resolvedPkg == null) {
            AppLog.i(
                TAG,
                "Step ${step.id}: NONE of [${step.requiredPackages.joinToString()}] — skipping"
            )
            return Result(false, "app_not_installed", skipped = true)
        }
        if (resolvedPkg != null) {
            AppLog.i(TAG, "Step ${step.id}: resolved package $resolvedPkg")
        }

        preferredPackages = buildPreferredPackages(step, resolvedPkg)

        val launchesApp = step.launchPackage != null ||
                step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE
        // Settings-шаги: не уходим на Home — иначе теряем окно и жжём таймаут
        if (launchesApp) {
            resetToHome()
        }

        val packageName = resolvedPkg ?: step.launchPackage
        val needForceStop = packageName != null && step.forceStopBeforeLaunch &&
                step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE

        if (packageName != null && needForceStop) {
            forceStopPackage(packageName)
        }

        var appLaunched = false

        if (step.launchPackage != null && packageName != null &&
            step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE
        ) {
            OverlayController.updateStatus(service, "Открываем приложение…")
            appLaunched = launchApp(packageName, clearTask = needForceStop)

            if (appLaunched) {
                waitForContent()
                dismissFreshDeviceObstacles(preferDecline = false)
                if (step.swipeUpAfterLaunch) swipeUp()
                delay(UI_SETTLE_DELAY_MS)
                dismissFreshDeviceObstacles(preferDecline = false)
            }
        }

        if (!appLaunched) {
            OverlayController.updateStatus(service, "Открываем нужный экран…")
            val intents = buildResolvedIntents(step, resolvedPkg)
            if (!openTargetScreen(intents) && !waitForContent(MAX_POLLING_MS)) {
                AppLog.w(TAG, "Step ${step.id}: no screen opened")
                return Result(false, "no_screen_opened")
            }
            waitForContent()
            dismissFreshDeviceObstacles(preferDecline = false)
            delay(UI_SETTLE_DELAY_MS)
        }

        getBestRoot()?.let {
            AppLog.i(
                TAG,
                "Step ${step.id}: start pkg=${it.packageName} screen=[${collectAllText(it).take(150)}]"
            )
        }

        if (cancelled) return Result(false, "cancelled")

        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE) {
            return clearDataAndDecline(step)
        }

        if (step.preDrillWaitMs > 0L) {
            OverlayController.updateStatus(service, "Ждём загрузку экрана…")
            delay(step.preDrillWaitMs.coerceAtMost(2_500L))
            dismissFreshDeviceObstacles(preferDecline = false)
        }

        drillDown(step)
        if (cancelled) return Result(false, "cancelled")

        OverlayController.updateStatus(service, "Ищем нужный переключатель…")
        dismissFreshDeviceObstacles(preferDecline = false)
        var result = findAndToggleSwitch(step, attempt = 1)

        if (!result.success && !cancelled && result.reason != "app_not_installed") {
            AppLog.w(TAG, "Step ${step.id}: retry after ${result.reason}")
            OverlayController.updateStatus(service, "Повторная попытка…")
            delay(RETRY_DELAY_MS)
            result = findAndToggleSwitch(step, attempt = 2)
        }

        if (!result.success && !cancelled) {
            val finalScreen = getBestRoot()?.let { collectAllText(it).take(300) } ?: "no_root"
            AppLog.i(TAG, "Step ${step.id}: fail, final screen=[$finalScreen]")
        }

        return result
    }

    private fun resolveInstalledPackage(step: SimpleSteps.Step): String? {
        val candidates = linkedSetOf<String>()
        step.requiredPackages.forEach { candidates.add(it) }
        step.launchPackage?.let { candidates.add(it) }
        return candidates.firstOrNull { isPackageInstalled(it) }
    }

    private fun buildPreferredPackages(step: SimpleSteps.Step, resolved: String?): Set<String> {
        val set = linkedSetOf<String>()
        set.addAll(SETTINGS_PACKAGES)
        resolved?.let { set.add(it) }
        step.launchPackage?.let { set.add(it) }
        step.requiredPackages.forEach { set.add(it) }
        return set
    }

    private fun buildResolvedIntents(
        step: SimpleSteps.Step,
        resolvedPkg: String?
    ): List<Intent> {
        if (resolvedPkg == null) return step.intents
        return when (step.actionType) {
            SimpleSteps.ActionType.CLEAR_DATA_DECLINE -> listOf(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$resolvedPkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            else -> {
                if (step.id.startsWith("notif_")) {
                    listOf(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, resolvedPkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                } else {
                    step.intents
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Навигация / запуск
    // ═══════════════════════════════════════════════════════════════

    private suspend fun resetToHome() {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(home)
            delay(HOME_RESET_DELAY_MS)
            AppLog.i(TAG, "resetToHome: ok")
        } catch (e: Exception) {
            AppLog.w(TAG, "resetToHome failed: ${e.message}")
        }
    }

    private suspend fun forceStopPackage(pkg: String) {
        try {
            val am = service.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            try {
                am.killBackgroundProcesses(pkg)
            } catch (_: Throwable) {
            }
            try {
                am.javaClass.getMethod("forceStopPackage", String::class.java)
                    .invoke(am, pkg)
            } catch (_: Throwable) {
            }
            AppLog.i(TAG, "forceStop best effort: $pkg")
            delay(FORCE_STOP_DELAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "forceStop failed for $pkg: ${e.message}")
        }
    }

    private fun launchApp(packageName: String, clearTask: Boolean = false): Boolean {
        val launchIntent: Intent = try {
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
            if (clearTask) launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            service.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "launchApp failed for $packageName: ${e.message}")
            false
        }
    }

    private suspend fun openTargetScreen(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (cancelled) return false
            try {
                val resolved = intent.resolveActivity(service.packageManager)
                if (resolved == null &&
                    intent.action != null &&
                    !intent.action!!.startsWith("android.settings")
                ) continue

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                service.startActivity(intent)
                if (waitForContent(MAX_POLLING_MS)) return true
                delay(UI_SETTLE_DELAY_MS)
                if (getBestRoot() != null) return true
            } catch (e: Exception) {
                AppLog.w(TAG, "openTargetScreen intent failed: ${e.message}")
            }
        }
        return getBestRoot() != null
    }

    // ═══════════════════════════════════════════════════════════════
    // Ожидание экранов (теперь через getBestRoot)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun awaitScreen(markers: List<String>): Boolean {
        val filtered = markers.filterNot { it.isBlank() || isIconOnly(it) }.distinct()

        if (filtered.isEmpty()) {
            return waitForContentRoot() != null
        }

        val start = System.currentTimeMillis()
        var lastText = ""

        while (System.currentTimeMillis() - start < SCREEN_WAIT_MS) {
            if (cancelled) return false

            // Сначала точный поиск по тексту во всех окнах (не зависит от collectAllText depth)
            if (findAnyNodeInAllWindows(filtered) != null || searchAllWindows(filtered) != null) {
                AppLog.i(TAG, "awaitScreen: matched via node search")
                return true
            }

            val root = getBestRoot()
            if (root != null) {
                val text = collectAllText(root)
                lastText = text
                val matched = filtered.firstOrNull { text.contains(it, ignoreCase = true) }
                if (matched != null) {
                    AppLog.i(TAG, "awaitScreen: matched '$matched'")
                    return true
                }
            }
            delay(250L)
        }

        AppLog.w(
            TAG,
            "awaitScreen: timeout, markers=${filtered.take(2)}, screen=[${lastText.take(100)}]"
        )
        return false
    }

    private fun isIconOnly(value: String): Boolean {
        val t = value.trim()
        return t.isEmpty() || t.none { it.isLetterOrDigit() }
    }

    // ═══════════════════════════════════════════════════════════════
    // Спец-маршрут Проводника
    // ═══════════════════════════════════════════════════════════════

    private suspend fun clearDataAndDecline(step: SimpleSteps.Step): Result {
        OverlayController.updateStatus(service, "Ищем кнопку очистки…")

        val root1 = waitForContentRoot() ?: return Result(false, "no_root")

        val clearBtn = findClickableByTextWithScroll(
            root1,
            listOf(
                "Очистить данные", "Clear data", "Очистить хранилище",
                "Clear storage", "Очистить все", "Clear all", "Очистить", "Clear"
            )
        ) ?: searchAllWindows(listOf("Очистить данные", "Clear data"))

        if (clearBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: clear button not found")
            return Result(false, "clear_button_not_found")
        }

        tapNode(clearBtn)
        delay(800L)

        val root2 = waitForContentRoot() ?: return Result(false, "no_root")

        val allBtn = findClickableByText(
            root2,
            listOf(
                "Очистить все",
                "Clear all",
                "Очистить все данные",
                "Очистить данные",
                "Clear data"
            )
        ) ?: searchAllWindows(listOf("Очистить все", "Clear all"))

        if (allBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: 'Очистить все' not found")
            return Result(false, "clear_all_not_found")
        }

        tapNode(allBtn)
        delay(1_200L)

        step.launchPackage?.let { pkg ->
            val launchPkg = resolveInstalledPackage(step) ?: pkg
            OverlayController.updateStatus(service, "Проверяем первый запуск…")
            launchApp(launchPkg, clearTask = true)
            waitForContent(2_500L)
            dismissFreshDeviceObstacles(preferDecline = true)
        }

        val root3 = getBestRoot() ?: return Result(true, "cleared_no_welcome")

        val cancelBtn = findClickableByText(
            root3,
            listOf("Отмена", "Cancel", "Отклонить", "Decline", "Не согласен")
        ) ?: searchAllWindows(listOf("Отмена", "Cancel", "Отклонить", "Decline"))

        if (cancelBtn != null) {
            tapNode(cancelBtn)
            AppLog.i(TAG, "Step ${step.id}: declined welcome dialog")
        }

        return Result(true, "cleared_and_declined")
    }

    // ═══════════════════════════════════════════════════════════════
    // Drill-down
    // ═══════════════════════════════════════════════════════════════

    private suspend fun drillDown(step: SimpleSteps.Step) {
        for ((index, level) in step.drillPath.withIndex()) {
            if (cancelled) return

            dismissFreshDeviceObstacles(preferDecline = false)

            val root = waitForContentRoot()
            var node = if (root != null) findClickableByTextWithScroll(root, level) else null

            if (node == null) node = searchAllWindows(level)

            if (node != null) {
                AppLog.i(TAG, "Step ${step.id}: drilling into '${level.firstOrNull()}'")
                tapNode(node)
            } else if (level.any { it.contains("⋮") || it.contains("⚙") || isIconOnly(it) }) {
                AppLog.i(TAG, "Step ${step.id}: '${level.firstOrNull()}' not found — top-right")
                tapTopRight(nextScreenMarkers(step, index))
                delay(POPUP_MENU_DELAY_MS)
            } else {
                val cur = root?.let { collectAllText(it).take(120) } ?: "no_root"
                AppLog.w(TAG, "Step ${step.id}: level not found — assuming inside, screen=[$cur]")
            }

            dismissFreshDeviceObstacles(preferDecline = false)
            awaitScreen(nextScreenMarkers(step, index))
            delay(UI_SETTLE_DELAY_MS)
        }
    }

    private fun nextScreenMarkers(step: SimpleSteps.Step, index: Int): List<String> {
        val next = step.drillPath.getOrNull(index + 1) ?: step.searchTexts
        // Иконки ⚙/⋮ и слово «Настройки» на главном экране Безопасности дают ложный match
        val filtered = next.filterNot { it.isBlank() || isIconOnly(it) }
            .filterNot { it.equals("Настройки", ignoreCase = true) || it.equals("Settings", ignoreCase = true) }
        return if (filtered.isNotEmpty()) filtered.distinct()
        else step.searchTexts.filterNot { it.isBlank() }.distinct()
    }

    /**
     * Свежий ROM: онбординг / политика / логин / runtime-разрешения.
     * Порядок: Skip → Decline(если нужно) → Allow(permission) → Agree(чтобы войти в UI).
     */
    private suspend fun dismissFreshDeviceObstacles(preferDecline: Boolean) {
        OverlayController.updateStatus(service, "Закрываем диалоги первого запуска…")
        repeat(MAX_FRESH_DISMISS_ROUNDS) {
            if (cancelled) return
            val clicked = when {
                tryClickAny(SKIP_TEXTS) -> "skip"
                preferDecline && tryClickAny(DECLINE_TEXTS) -> "decline"
                tryClickAny(PERMISSION_ALLOW_TEXTS) -> "permission"
                !preferDecline && tryClickAny(ENTER_TEXTS) -> "enter"
                else -> null
            }
            if (clicked == null) return
            AppLog.i(TAG, "fresh-device dismiss: $clicked")
            delay(500L)
            waitForContent(1_200L)
        }
    }

    private suspend fun tryClickAny(texts: List<String>): Boolean {
        val node = searchAllWindows(texts) ?: findAnyNodeInAllWindows(texts) ?: return false
        val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (label.length > 40) return false
        return tapNode(node)
    }

    private suspend fun tapTopRight(expected: List<String>): Boolean {
        val root = getBestRoot()

        if (root != null) {
            val overflow = findClickableByText(root, OVERFLOW_TEXTS)
            if (overflow != null) {
                val clicked = tapNode(overflow)
                if (clicked) {
                    AppLog.i(TAG, "tapTopRight: overflow via accessibility")
                    return true
                }
            }
        }

        AppLog.i(TAG, "tapTopRight: fallback gesture (multi-Y)")

        withOverlayPassThrough {
            val dm = service.resources.displayMetrics
            val x = dm.widthPixels - dp(24).toFloat()

            for (yDp in TOP_RIGHT_Y_DP) {
                tapAt(x, dp(yDp).toFloat())
                delay(500L)

                if (expected.isNotEmpty()) {
                    val found = searchAllWindows(expected)
                    if (found != null) {
                        AppLog.i(TAG, "tapTopRight: menu opened at y=${yDp}dp")
                        return@withOverlayPassThrough
                    }
                }
            }
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // Клики
    // ═══════════════════════════════════════════════════════════════

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AppLog.i(TAG, "tapNode: node ACTION_CLICK ok")
            return true
        }
        if (clickParent(node)) {
            AppLog.i(TAG, "tapNode: parent ACTION_CLICK ok")
            return true
        }

        AppLog.i(TAG, "tapNode: gesture fallback")
        var done = false
        withOverlayPassThrough {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                done = tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        return done
    }

    private fun clickParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private suspend fun withOverlayPassThrough(block: suspend () -> Unit) {
        try {
            OverlayController.setBlocking(service, false)
        } catch (_: Exception) {
        }
        delay(250L)
        block()
        delay(400L)
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

    // ═══════════════════════════════════════════════════════════════
    // Переключатели
    // ═══════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private suspend fun toggleAdditional(texts: List<String>) {
        for (text in texts) {
            if (cancelled) return
            delay(400L)

            val root = getBestRoot() ?: break
            val sw = findSwitchByText(root, listOf(text))

            if (sw != null) {
                if (sw.isChecked) {
                    AppLog.i(TAG, "Toggling additional: $text")
                    tapNode(sw)
                    delay(600L)
                }
            } else {
                AppLog.w(TAG, "Additional toggle not found: $text")
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step, attempt: Int): Result {
        if (cancelled) return Result(false, "cancelled")

        val root = waitForContentRoot() ?: return Result(false, "no_root_window")

        var switchNode = findSwitchByText(root, step.searchTexts)

        if (switchNode == null && step.searchTexts.isNotEmpty()) {
            var noProgress = 0

            repeat(SWITCH_FALLBACK_SCROLLS) {
                if (cancelled) return Result(false, "cancelled")

                val scrollRoot = getBestRoot() ?: return Result(false, "no_root_window")
                val scrollable = findScrollableContainer(scrollRoot)
                if (scrollable == null) return@repeat

                val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (!scrolled) return@repeat

                delay(400L)

                val newRoot = getBestRoot() ?: return Result(false, "no_root_window")
                val found = findSwitchByText(newRoot, step.searchTexts)

                if (found != null) {
                    switchNode = found
                    return@repeat
                }

                noProgress++
                if (noProgress >= MAX_SCROLL_WITHOUT_PROGRESS) {
                    AppLog.w(TAG, "Step ${step.id}: scrolls without progress — stopping")
                    return@repeat
                }
            }

            if (switchNode == null) {
                switchNode = findSwitchInAllWindows(step.searchTexts)
            }
        }

        if (switchNode == null) {
            val screenText = collectAllText(root).take(600)
            AppLog.w(TAG, "Step ${step.id}: NO SWITCH FOUND. screen=[$screenText]")
            return Result(false, "switch_not_found")
        }

        val isChecked = switchNode.isChecked
        AppLog.i(
            TAG,
            "Step ${step.id}: switch found, isChecked=$isChecked, target=${step.targetChecked}, attempt=$attempt"
        )

        if (isChecked == step.targetChecked) {
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            return Result(true, "already_done")
        }

        OverlayController.updateStatus(service, "Переключаем элемент…")

        val clicked = tapNode(switchNode)
        AppLog.i(TAG, "Step ${step.id}: tapNode result=$clicked")

        if (!clicked) return Result(false, "click_failed")

        delay(UI_SETTLE_DELAY_MS)

        // Диалог подтверждения (MSA)
        var confirmationClicked = false
        if (step.confirmTexts.isNotEmpty()) {
            confirmationClicked = handleConfirmation(step)
            if (!confirmationClicked) {
                AppLog.w(TAG, "Step ${step.id}: confirmation not completed")
            }
        }

        // ФИКС (beta10): если «Отозвать» кликнут успешно — успех СРАЗУ.
        // Раньше верификация после клика съедала остаток таймаута MSA.
        if (confirmationClicked) {
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            return Result(true, "confirmed")
        }

        // Верификация для обычных шагов
        val newRoot = getBestRoot()
        if (newRoot == null) {
            return Result(true, "toggled_no_verify")
        }

        val newSwitch = findSwitchByText(newRoot, step.searchTexts)
        val newChecked = newSwitch?.isChecked ?: !isChecked
        AppLog.i(TAG, "Step ${step.id}: verify newChecked=$newChecked")

        if (newChecked == step.targetChecked) {
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            return Result(true, "toggled")
        }

        return Result(false, "toggle_failed")
    }

    private suspend fun handleConfirmation(step: SimpleSteps.Step): Boolean {
        OverlayController.updateStatus(service, "Ждём диалог подтверждения…")

        val start = System.currentTimeMillis()
        val deadline = start + step.confirmWaitMs + CONFIRM_TIMEOUT_EXTRA_MS
        var button: AccessibilityNodeInfo? = null

        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            val node = searchAllWindows(step.confirmTexts)
                ?: findAnyNodeInAllWindows(step.confirmTexts)
            if (node != null && node.isEnabled) {
                button = node
                break
            }
            delay(250L)
        }

        if (button == null) {
            button = searchAllWindows(step.confirmTexts)
                ?: findAnyNodeInAllWindows(step.confirmTexts)
        }
        if (button == null) {
            AppLog.w(TAG, "Step ${step.id}: confirmation button not found")
            return false
        }

        AppLog.i(
            TAG,
            "Step ${step.id}: confirmation ready after ${System.currentTimeMillis() - start}ms"
        )
        if (!tapNode(button)) {
            AppLog.w(TAG, "Step ${step.id}: confirmation click failed")
            return false
        }
        delay(300L)
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // Поиск узлов
    // ═══════════════════════════════════════════════════════════════

    private fun isPackageInstalled(p: String): Boolean = try {
        service.packageManager.getPackageInfo(p, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun searchAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == service.packageName) continue
                findClickableByText(r, texts)?.let { return it }
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
                if (r.packageName?.toString() == service.packageName) continue
                findSwitchByText(r, texts)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findAnyNodeInAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == service.packageName) continue
                findAnyNodeByText(r, texts)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun findAnyNodeByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            nodes.firstOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun findClickableByTextWithScroll(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        findClickableByText(root, texts)?.let { return it }

        var noProgress = 0

        repeat(MAX_SCROLL_ATTEMPTS) {
            if (cancelled) return null

            val currentRoot = getBestRoot() ?: return null
            val scrollable = findScrollableContainer(currentRoot)
            if (scrollable == null) return null

            val scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!scrolled) return null

            delay(SCROLL_SETTLE_DELAY_MS)

            val newRoot = getBestRoot() ?: return null
            val found = findClickableByText(newRoot, texts)
            if (found != null) return found

            noProgress++
            if (noProgress >= MAX_SCROLL_WITHOUT_PROGRESS) {
                AppLog.w(TAG, "findClickableByTextWithScroll: no progress — stopping")
                return null
            }
        }
        return null
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable && root.childCount > 0) return root

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findScrollableContainer(child)?.let { return it }
        }
        return null
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            val exact = text.length <= 3

            for (node in nodes) {
                val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                if (exact && !nodeText.equals(text, ignoreCase = true)) continue

                if (node.isClickable) return node

                var current: AccessibilityNodeInfo? = node
                var depth = 0
                while (current != null && depth < MAX_PARENT_DEPTH) {
                    if (current.isClickable) return current
                    current = current.parent
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
        collectSwitchLike(root, switches)

        for (sw in switches) {
            if (findNearbyText(sw, texts) != null) return sw
        }

        for (id in SWITCH_VIEW_IDS) {
            val byId = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (n in byId) {
                if (findNearbyText(n, texts) != null) return n
            }
        }

        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                var depth = 0
                while (current != null && depth < SWITCH_ANCESTOR_DEPTH) {
                    val cls = current.className?.toString() ?: ""
                    if (cls.contains("Switch") || cls.contains("CheckBox") ||
                        cls.contains("Toggle") || current.isCheckable
                    ) return current
                    current = current.parent
                    depth++
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
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
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
        for (i in 0 until node.childCount) {
            collectTextRecursive(node.getChild(i), sb, depth + 1)
        }
    }

    private fun dp(v: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        service.resources.displayMetrics
    ).toInt()
}