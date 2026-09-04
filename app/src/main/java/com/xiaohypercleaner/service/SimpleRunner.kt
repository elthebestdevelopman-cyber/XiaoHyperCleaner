package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.DirectIntentNavigator
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.DiagnosticSnapshotManager
import com.xiaohypercleaner.util.StepDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SimpleRunner — UI-автоматизация Simple Mode эталонного уровня (Core OS / Senior Android).
 *
 * Архитектура и ключевые решения:
 * 1. Прямая навигация (Direct Intent Navigation): исключает слепой поиск по меню и строкам поиска.
 * 2. Комплексный парсинг экрана (Batch Screen Sweeper): сканирует всё дерево элементов и отключает
 *    все целевые переключатели/чекбоксы за одно посещение экрана.
 * 3. Полная поддержка планшетов Сяоми (Xiaomi Pad / Redmi Pad): раздельный учёт левой панели
 *    категорий и правой панели деталей, точечный скролл в целевой колонке, динамические координаты.
 * 4. Умные клики и Fluent Wait: рекурсивный подъём по родителям (getParent) до кликабельного слоя
 *    с жестовым fallback по динамическому центру boundsInScreen.
 * 5. Диагностический модуль и локальные снимки сбоев (DiagnosticSnapshotManager) для краудсорсинга.
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"

        private const val BASE_STEP_TIMEOUT_MS = 16_000L
        private const val MSA_STEP_TIMEOUT_MS = 40_000L
        private const val CONFIRM_TIMEOUT_EXTRA_MS = 1_500L
        private const val CONFIRM_QUICK_POLL_MS = 1_600L
        private const val SCREEN_WAIT_MS = 1_500L
        private const val CONTENT_WAIT_MS = 1_500L
        private const val UI_SETTLE_DELAY_MS = 150L
        private const val POPUP_MENU_DELAY_MS = 350L
        private const val HOME_RESET_DELAY_MS = 200L
        private const val FORCE_STOP_DELAY_MS = 250L
        private const val SCROLL_SETTLE_DELAY_MS = 200L
        private const val TAP_DURATION_MS = 60L
        private const val RETRY_DELAY_MS = 280L

        private const val MAX_POLLING_MS = 1_400L
        private const val POLLING_INTERVAL_MS = 80L
        private const val MIN_CONTENT_LENGTH = 6
        private const val MAX_PARENT_DEPTH = 6
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SWITCH_FALLBACK_SCROLLS = 4
        private const val TEXT_DEPTH = 40
        private const val MAX_COLLECT_TEXT_CHARS = 4000
        private const val NEARBY_ANCESTORS = 4
        private const val MAX_FRESH_DISMISS_ROUNDS = 4

        private val TOP_RIGHT_Y_DP = listOf(56, 76, 96)

        private val OVERFLOW_TEXTS = listOf(
            "Ещё", "Еще", "More options", "More", "Дополнительно", "Другие параметры", "⋮",
            "更多", "Más", "Lainnya", "Mais opções", "अधिक विकल्प"
        )

        private val SKIP_TEXTS = listOf(
            "Пропуск", "Пропустить", "Skip", "Позже", "Later", "Не сейчас", "Not now",
            "Закрыть", "Close", "Нет, спасибо", "No thanks", "Без входа",
            "Continue without account", "Гостевой режим", "Guest",
            "Напомнить позже", "Remind me later", "Не входить", "Skip login",
            "Понятно", "Got it", "Не обновлять", "Don't update",
            "跳过", "稍后", "关闭", "Omitir", "Más tarde", "Cerrar",
            "छोड़ें", "Pular", "Lewati"
        )

        private val DECLINE_TEXTS = listOf(
            "Отмена", "Cancel", "Отклонить", "Decline", "Не согласен", "Disagree",
            "Запретить", "Deny", "Don't allow", "Не разрешать",
            "取消", "拒绝", "Cancelar", "Rechazar",
            "रद्द करें", "Recusar", "Batal", "Tolak"
        )

        private val ENTER_TEXTS_DEFAULT = listOf(
            "Согласиться", "Принять", "Agree", "Accept",
            "Понятно", "Got it", "Далее", "Next",
            "Продолжить", "Continue",
            "同意", "接受", "下一步",
            "Aceptar", "Siguiente", "Entendido",
            "सहमत हैं", "Concordar", "Setuju"
        )

        private val PERMISSION_DENY_TEXTS = listOf(
            "Запретить", "Deny", "Don't allow", "Не разрешать", "Отклонить",
            "拒绝", "不允许", "Rechazar", "Denegar",
            "अनुमति न दें", "Não permitir", "Jangan izinkan"
        )

        private val PERMISSION_ALLOW_TEXTS_DEFAULT = listOf(
            "Разрешить", "Allow", "While using the app", "При использовании",
            "Только в этот раз", "Only this time",
            "允许", "仅在使用中允许", "Permitir",
            "अनुमति दें", "Izinkan"
        )

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.securitycore",
            "com.xiaomi.misettings"
        )

        private val KNOWN_SWITCH_VIEW_IDS = listOf(
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
            "com.miui.securitycenter:id/switch_widget",
            "com.miui.settings:id/switch_widget",
            "com.android.settings:id/checkbox",
            "android:id/checkbox",
            "com.miui.securitycenter:id/sliding_button",
            "com.android.settings:id/sliding_button",
            "android:id/switch_screen"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    @Volatile
    private var cancelled: Boolean = false

    private var targetPackages: Set<String> = emptySet()
    private var preferredPackages: Set<String> = emptySet()

    private val romProfile: RomProfile by lazy { RomProfile.detect(service) }

    private fun catalog() = AdaptiveCatalog.ensureLoaded(service)

    private fun skipTexts(): List<String> =
        (catalog().skip + SKIP_TEXTS).distinct()

    private fun declineTexts(): List<String> =
        (catalog().decline + DECLINE_TEXTS).distinct()

    private fun enterTexts(): List<String> =
        AdaptiveCatalog.safeEnterTexts(catalog().enter + ENTER_TEXTS_DEFAULT).distinct()

    private fun permissionAllowTexts(): List<String> =
        (catalog().permissionAllow + PERMISSION_ALLOW_TEXTS_DEFAULT).distinct()

    fun cancel() {
        cancelled = true
        AppLog.i(TAG, "Runner cancellation requested")
    }

    private var savedMusicVolume: Int = -1

    private fun muteMediaVolume() {
        try {
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedMusicVolume < 0) {
                savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            AppLog.i(TAG, "media muted (was=$savedMusicVolume)")
        } catch (e: Exception) {
            AppLog.w(TAG, "mute failed: ${e.message}")
        }
    }

    private fun restoreMediaVolume() {
        if (savedMusicVolume < 0) return
        try {
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            AppLog.i(TAG, "media volume restored to $savedMusicVolume")
        } catch (e: Exception) {
            AppLog.w(TAG, "unmute failed: ${e.message}")
        }
        savedMusicVolume = -1
    }

    suspend fun run(step: SimpleSteps.Step): Result {
        cancelled = false
        isRunning = true
        val start = System.currentTimeMillis()

        val effectiveTimeoutMs: Long = if (step.id == "msa") {
            MSA_STEP_TIMEOUT_MS
        } else {
            BASE_STEP_TIMEOUT_MS + step.preDrillWaitMs +
                    if (step.confirmWaitMs > 0L) step.confirmWaitMs + CONFIRM_TIMEOUT_EXTRA_MS else 0L
        }

        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${effectiveTimeoutMs}ms)")

        val res = try {
            withTimeoutOrNull(effectiveTimeoutMs) { runInternal(step) }
                ?: run {
                    val screenText = try {
                        getBestRoot()?.let { collectAllText(it).take(200) } ?: "no_root"
                    } catch (_: Exception) {
                        "error"
                    }
                    AppLog.e(TAG, "Step ${step.id}: TIMEOUT, screen=[$screenText]")
                    DiagnosticSnapshotManager.captureAndSaveSnapshot(
                        context = service,
                        stepId = step.id,
                        failureReason = "timeout",
                        rootNode = getBestRoot(),
                        profile = romProfile,
                        targetPackage = step.launchPackage
                    )
                    Result(false, "timeout")
                }
        } finally {
            restoreMediaVolume()
            isRunning = false
        }

        val elapsed = System.currentTimeMillis() - start
        StepDiagnostics.stepResult(
            stepId = step.id,
            success = res.success,
            reason = res.reason,
            elapsedMs = elapsed,
            root = getBestRoot(),
            service = service as? AdbEnablerService
        )
        return res
    }

    // ═══════════════════════════════════════════════════════════════
    // Определение и выбор целевого окна
    // ═══════════════════════════════════════════════════════════════

    private fun getBestRoot(): AccessibilityNodeInfo? {
        var bestTarget: AccessibilityNodeInfo? = null
        var bestTargetScore = -1L
        var bestSettings: AccessibilityNodeInfo? = null
        var bestSettingsScore = -1L
        var bestPermissionDialog: AccessibilityNodeInfo? = null
        var bestPermissionDialogScore = -1L
        var bestOther: AccessibilityNodeInfo? = null
        var bestOtherScore = -1L

        try {
            for (w in service.windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: continue
                if (pkg == service.packageName) continue

                val textLen = collectAllText(r).length.toLong()
                val isAppWindow = w.type == AccessibilityWindowInfo.TYPE_APPLICATION
                val isSystemUi = pkg == "com.android.systemui"
                val isLauncher = pkg.contains("launcher") || pkg.contains("home")
                val isPermissionDialog = pkg == "com.google.android.permissioncontroller" ||
                        pkg == "com.android.packageinstaller"
                val isFocusedDialog = w.isFocused && (textLen in 1..500)

                val score = (textLen * 10L) +
                        (if (isAppWindow) 50_000L else 0L) +
                        (if (isFocusedDialog) 60_000L else 0L) +
                        (if (w.isFocused) 5_000L else 0L) +
                        (if (w.isActive) 2_000L else 0L) -
                        (if (!w.isFocused && textLen < 50L) 40_000L else 0L) -
                        (if (isSystemUi) 200_000L else 0L) -
                        (if (isLauncher) 150_000L else 0L)

                when {
                    isPermissionDialog -> {
                        if (score > bestPermissionDialogScore) {
                            bestPermissionDialogScore = score
                            bestPermissionDialog = r
                        }
                    }
                    targetPackages.contains(pkg) -> {
                        if (score > bestTargetScore) {
                            bestTargetScore = score
                            bestTarget = r
                        }
                    }
                    SETTINGS_PACKAGES.contains(pkg) || preferredPackages.contains(pkg) -> {
                        if (score > bestSettingsScore) {
                            bestSettingsScore = score
                            bestSettings = r
                        }
                    }
                    score > bestOtherScore -> {
                        bestOtherScore = score
                        bestOther = r
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getBestRoot scan failed: ${e.message}")
        }

        val chosen = bestTarget ?: bestPermissionDialog ?: if (targetPackages.isNotEmpty()) {
            null
        } else {
            bestSettings ?: bestOther
        }
        if (chosen != null) return chosen

        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() != service.packageName) {
            val actPkg = active.packageName?.toString()
            if (targetPackages.isEmpty() || targetPackages.contains(actPkg)) {
                return active
            }
        }
        return null
    }

    private suspend fun <T> fluentWait(
        timeoutMs: Long = 3_000L,
        pollIntervalMs: Long = 150L,
        condition: suspend () -> T?
    ): T? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cancelled) return null
            val result = condition()
            if (result != null) return result
            delay(pollIntervalMs)
        }
        return null
    }

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

        buildPreferredPackages(step, resolvedPkg)

        val launchesApp = step.launchPackage != null ||
                step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE
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
            OverlayController.updateStatus(service, "Анализ конфигурации системы…")
            if (packageName.contains("video", ignoreCase = true) ||
                packageName.contains("player", ignoreCase = true) ||
                packageName.contains("music", ignoreCase = true)
            ) {
                muteMediaVolume()
            }
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
            OverlayController.updateStatus(service, "Оптимизация параметров системы…")
            val isSettingsRootStep = step.launchPackage == null &&
                    step.intents.any { it.action == android.provider.Settings.ACTION_SETTINGS }

            if (isSettingsRootStep) {
                resetSettingsToRoot()
            }

            // Прямые целевые Intent-вызовы на основе DirectIntentNavigator (без слепого поиска)
            val directIntents = DirectIntentNavigator.buildIntentsForStep(
                service, step, resolvedPkg, romProfile
            )
            val opened = openTargetScreen(directIntents) || waitForContent(MAX_POLLING_MS)
            if (!opened && isSettingsRootStep) {
                resetSettingsToRoot()
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
            OverlayController.updateStatus(service, "Оптимизация компонентов…")
            delay(step.preDrillWaitMs)
            dismissFreshDeviceObstacles(preferDecline = false)
        }

        drillDown(step)
        if (cancelled) return Result(false, "cancelled")

        OverlayController.updateStatus(service, "Повышение конфиденциальности…")
        dismissFreshDeviceObstacles(preferDecline = false)

        // Комплексный парсинг экрана: находим и переключаем ВСЕ целевые элементы за 1 визит
        var result = sweepAndToggleAllSwitches(step, attempt = 1)

        if (!result.success && !cancelled && result.reason != "app_not_installed") {
            AppLog.w(TAG, "Step ${step.id}: retry after ${result.reason}")
            OverlayController.updateStatus(service, "Повторная оптимизация…")
            delay(RETRY_DELAY_MS)
            result = sweepAndToggleAllSwitches(step, attempt = 2)
        }

        if (!result.success && !cancelled) {
            val finalScreen = getBestRoot()?.let { collectAllText(it).take(300) } ?: "no_root"
            AppLog.i(TAG, "Step ${step.id}: fail, final screen=[$finalScreen]")
            DiagnosticSnapshotManager.captureAndSaveSnapshot(
                context = service,
                stepId = step.id,
                failureReason = result.reason,
                rootNode = getBestRoot(),
                profile = romProfile,
                targetPackage = resolvedPkg ?: step.launchPackage
            )
        }

        val openedSubScreen = step.id.startsWith("notif_") || step.launchPackage != null
        if (openedSubScreen && !cancelled) {
            delay(250L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }

        return result
    }

    private fun resolveInstalledPackage(step: SimpleSteps.Step): String? {
        val candidates = AdaptiveCatalog.packagesForStep(
            service,
            step.id,
            step.requiredPackages,
            romProfile
        )
        for (pkg in candidates) {
            if (isPackageInstalled(pkg)) return pkg
        }
        step.launchPackage?.let { if (isPackageInstalled(it)) return it }
        return null
    }

    private fun buildPreferredPackages(step: SimpleSteps.Step, resolved: String?) {
        val targets = linkedSetOf<String>()
        resolved?.let { targets.add(it) }
        step.launchPackage?.let { targets.add(it) }
        val isAppWindowStep = step.launchPackage != null || (
            resolved != null &&
                step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE &&
                !step.id.startsWith("notif_") &&
                step.intents.none { it.action == android.provider.Settings.ACTION_SETTINGS }
            )
        targetPackages = if (isAppWindowStep) targets else emptySet()

        val set = linkedSetOf<String>()
        set.addAll(SETTINGS_PACKAGES)
        set.addAll(targets)
        step.requiredPackages.forEach { set.add(it) }
        preferredPackages = set
    }

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

    private suspend fun resetSettingsToRoot() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            service.startActivity(intent)
            val ready = waitForPackage("com.android.settings", maxMs = 2_500L)
            AppLog.i(TAG, "resetSettingsToRoot: reopen Settings, ready=$ready")
        } catch (e: Exception) {
            AppLog.w(TAG, "resetSettingsToRoot failed: ${e.message}")
        }
    }

    private suspend fun waitForPackage(pkg: String, maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            if (cancelled) return false
            val root = getBestRoot()
            if (root?.packageName?.toString() == pkg &&
                collectAllText(root).length >= MIN_CONTENT_LENGTH
            ) {
                return true
            }
            try {
                for (w in service.windows) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == pkg &&
                        collectAllText(r).length >= MIN_CONTENT_LENGTH
                    ) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
            delay(POLLING_INTERVAL_MS)
        }
        return false
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
                val launch = Intent(intent)
                val resolved = launch.resolveActivity(service.packageManager)
                if (resolved == null &&
                    launch.action != null &&
                    !launch.action!!.startsWith("android.settings")
                ) continue

                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(launch)

                if (waitForContent(MAX_POLLING_MS)) return true
                delay(UI_SETTLE_DELAY_MS)
                if (getBestRoot() != null) return true
            } catch (e: Exception) {
                AppLog.w(TAG, "openTargetScreen intent failed: ${e.message}")
            }
        }
        return getBestRoot() != null
    }

    private suspend fun awaitScreen(markers: List<String>): Boolean {
        val filtered = markers.filterNot { it.isBlank() || isIconOnly(it) }.distinct()
        if (filtered.isEmpty()) {
            return waitForContentRoot() != null
        }

        val start = System.currentTimeMillis()
        var lastText = ""

        while (System.currentTimeMillis() - start < SCREEN_WAIT_MS) {
            if (cancelled) return false

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
    // Спец-маршрут Проводника (Clear Data & Decline Policy)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun clearDataAndDecline(step: SimpleSteps.Step): Result {
        OverlayController.updateStatus(service, "Оптимизация параметров системы…")

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
        delay(600L)

        val confirmAllTexts = listOf(
            "Очистить все", "Clear all",
            "Очистить все данные", "Clear all data",
            "Удалить все данные", "Delete all data",
            "Удалить данные", "Delete data",
            "Все данные", "All data",
            "Очистить", "Clear",
            "OK", "ОК", "Да", "Yes", "Подтвердить"
        )

        var allBtn: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 2_500L
        while (System.currentTimeMillis() < deadline) {
            allBtn = searchAllWindows(confirmAllTexts) ?: findAnyNodeInAllWindows(confirmAllTexts)
            if (allBtn != null) break
            delay(150L)
        }

        if (allBtn == null) {
            val root2 = getBestRoot()
            allBtn = root2?.let { findClickableByText(it, confirmAllTexts) }
        }

        if (allBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: 'Очистить все' not found")
            return Result(false, "clear_all_not_found")
        }

        tapNode(allBtn)
        delay(600L)

        val secondConfirmTexts = listOf("OK", "ОК", "Да", "Yes", "Удалить", "Delete", "Подтвердить")
        val secondBtn = searchAllWindows(secondConfirmTexts) ?: findAnyNodeInAllWindows(secondConfirmTexts)
        if (secondBtn != null) {
            AppLog.i(TAG, "Step ${step.id}: second confirmation clicked")
            tapNode(secondBtn)
            delay(800L)
        }

        step.launchPackage?.let { pkg ->
            val launchPkg = resolveInstalledPackage(step) ?: pkg
            OverlayController.updateStatus(service, "Проверяем параметры запуска…")
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
    // Навигация по подменю (Drill-Down)
    // ═══════════════════════════════════════════════════════════════

    private suspend fun drillDown(step: SimpleSteps.Step) {
        val adaptiveDrillPath = AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)

        for ((index, level) in adaptiveDrillPath.withIndex()) {
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
                val markers = nextScreenMarkers(step, index)
                val alreadyInside = markers.isNotEmpty() && (
                    searchAllWindows(markers) != null || markers.any { cur.contains(it, ignoreCase = true) }
                )
                if (alreadyInside) {
                    AppLog.i(TAG, "Step ${step.id}: already inside next level '${markers.firstOrNull()}'")
                } else {
                    AppLog.w(TAG, "Step ${step.id}: drill level '${level.firstOrNull()}' not found, screen=[$cur]")
                }
            }

            dismissFreshDeviceObstacles(preferDecline = false)
            awaitScreen(nextScreenMarkers(step, index))
            delay(UI_SETTLE_DELAY_MS)
        }
    }

    private fun nextScreenMarkers(step: SimpleSteps.Step, index: Int): List<String> {
        val next = step.drillPath.getOrNull(index + 1) ?: step.searchTexts
        val filtered = next.filterNot { it.isBlank() || isIconOnly(it) }
            .filterNot { it.equals("Настройки", ignoreCase = true) || it.equals("Settings", ignoreCase = true) }
        return if (filtered.isNotEmpty()) filtered.distinct()
        else step.searchTexts.filterNot { it.isBlank() }.distinct()
    }

    // ═══════════════════════════════════════════════════════════════
    // Обработка системных диалогов первого запуска
    // ═══════════════════════════════════════════════════════════════

    private suspend fun dismissFreshDeviceObstacles(preferDecline: Boolean) {
        repeat(MAX_FRESH_DISMISS_ROUNDS) {
            if (cancelled) return
            val currentRoot = getBestRoot()
            val screenText = currentRoot?.let { collectAllText(it) } ?: ""

            // 1. Диалог «Установите по умолчанию» — всегда отклоняем
            if (screenText.contains("по умолчанию", ignoreCase = true) ||
                screenText.contains("default browser", ignoreCase = true) ||
                screenText.contains("set as default", ignoreCase = true)
            ) {
                val cancelTexts = listOf("Отмена", "Cancel", "Не сейчас", "Not now", "Позже", "Later", "Нет, спасибо", "No thanks")
                if (tryClickAny(cancelTexts)) {
                    AppLog.i(TAG, "fresh-device dismiss: default app prompt -> cancel")
                    delay(250L)
                    return@repeat
                }
            }

            // 2. Runtime permission на медиа/файлы/микрофон
            val isMediaPermission = screenText.contains("фото", ignoreCase = true) ||
                    screenText.contains("мультимедиа", ignoreCase = true) ||
                    screenText.contains("файл", ignoreCase = true) ||
                    screenText.contains("хранилищ", ignoreCase = true) ||
                    screenText.contains("media", ignoreCase = true) ||
                    screenText.contains("photos", ignoreCase = true) ||
                    screenText.contains("storage", ignoreCase = true) ||
                    screenText.contains("микрофон", ignoreCase = true) ||
                    screenText.contains("microphone", ignoreCase = true)

            val currentPkg = currentRoot?.packageName?.toString().orEmpty()
            val isMediaConsumerApp = targetPackages.contains("com.miui.player") ||
                    targetPackages.contains("com.miui.videoplayer") ||
                    currentPkg == "com.miui.player" ||
                    currentPkg == "com.miui.videoplayer"

            if (isMediaPermission) {
                if (isMediaConsumerApp) {
                    if (tryClickAny(permissionAllowTexts())) {
                        AppLog.i(TAG, "fresh-device dismiss: media permission -> ALLOW (required for media app)")
                        delay(300L)
                        return@repeat
                    }
                } else if (tryClickAny(PERMISSION_DENY_TEXTS)) {
                    AppLog.i(TAG, "fresh-device dismiss: media permission -> DENY")
                    delay(250L)
                    return@repeat
                }
            }

            // 2b. Диалог «Требуются разрешения» после отказа — закрываем «Отмена»
            if (screenText.contains("Требуются разрешения", ignoreCase = true) ||
                screenText.contains("Permissions required", ignoreCase = true) ||
                screenText.contains("не может получить доступ", ignoreCase = true) ||
                screenText.contains("can't access", ignoreCase = true) ||
                screenText.contains("cannot access", ignoreCase = true)
            ) {
                val cancelTexts = listOf("Отмена", "Cancel", "OK", "Понятно", "Got it")
                if (tryClickAny(listOf("Отмена", "Cancel")) || tryClickAny(cancelTexts)) {
                    AppLog.i(TAG, "fresh-device dismiss: permission-required dialog -> cancel")
                    delay(250L)
                    return@repeat
                }
            }

            // 2c. Сброс режима удаления/редактирования на рабочем столе
            if (screenText.contains("ОТМЕНА", ignoreCase = true) &&
                screenText.contains("УДАЛИТЬ", ignoreCase = true) &&
                (currentPkg.contains("launcher") || currentPkg.contains("home"))
            ) {
                if (tryClickAny(listOf("ОТМЕНА", "Отмена", "Cancel", "CANCEL"))) {
                    AppLog.i(TAG, "fresh-device dismiss: launcher edit mode -> CANCEL")
                    delay(300L)
                    return@repeat
                }
            }

            // 3. Соглашения первого запуска (в т.ч. «Выбрать все» → «Согласиться»)
            if (screenText.contains("Политика конфиденциальности", ignoreCase = true) ||
                screenText.contains("Условия использования", ignoreCase = true) ||
                screenText.contains("Юридические документы", ignoreCase = true) ||
                screenText.contains("Privacy Policy", ignoreCase = true) ||
                screenText.contains("Terms of Service", ignoreCase = true) ||
                screenText.contains("Legal documents", ignoreCase = true)
            ) {
                if (screenText.contains("Выбрать все", ignoreCase = true) ||
                    screenText.contains("Select all", ignoreCase = true)
                ) {
                    if (tryClickAny(listOf("Выбрать все", "Select all", "全选"))) {
                        AppLog.i(TAG, "fresh-device dismiss: select-all legal checkboxes")
                        delay(200L)
                    }
                }
                val acceptTexts = listOf("Согласиться", "Согласен", "Принять", "Agree", "Accept", "Agree and continue")
                if (tryClickAny(acceptTexts)) {
                    AppLog.i(TAG, "fresh-device dismiss: terms accepted")
                    delay(280L)
                    return@repeat
                }
            }

            val clicked = when {
                tryClickAny(skipTexts()) -> "skip"
                preferDecline && tryClickAny(declineTexts()) -> "decline"
                !isMediaPermission && tryClickAny(permissionAllowTexts()) -> "permission"
                !preferDecline && tryClickAny(enterTexts()) -> "enter"
                else -> null
            }
            if (clicked == null) return
            AppLog.i(TAG, "fresh-device dismiss: $clicked")
            delay(220L)
            waitForContent(600L)
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
        val pkg = root?.packageName?.toString().orEmpty()
        if (pkg.contains("launcher") || pkg.contains("home")) {
            AppLog.w(TAG, "tapTopRight: current window is launcher ($pkg), skipping blind gesture")
            return false
        }

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
            val x = (dm.widthPixels - dp(24)).toFloat()

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
    // Клики и жесты (с рекурсивным поиском кликабельного родителя)
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
                val dm = service.resources.displayMetrics
                val targetX = rect.centerX().toFloat().coerceIn(10f, dm.widthPixels - 10f)
                val targetY = rect.centerY().toFloat().coerceIn(10f, dm.heightPixels - 10f)
                done = tapAt(targetX, targetY)
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
        val isTablet = romProfile.isTablet
        // На планшетах свайпаем по правой (контентной) половине экрана
        val x = if (isTablet) dm.widthPixels * 0.65f else dm.widthPixels / 2f

        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.75f)
            lineTo(x, dm.heightPixels * 0.25f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        withOverlayPassThrough {
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
        }
        delay(UI_SETTLE_DELAY_MS)
    }

    // ═══════════════════════════════════════════════════════════════
    // Комплексный парсинг экрана (Batch Screen Sweeper)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Сканирует всё дерево элементов текущего экрана.
     * Если на экране одновременно несколько целевых переключателей — находит и отключает их ВСЕ за один визит!
     */
    @Suppress("DEPRECATION")
    private suspend fun sweepAndToggleAllSwitches(step: SimpleSteps.Step, attempt: Int): Result {
        if (cancelled) return Result(false, "cancelled")

        val root = fluentWait(timeoutMs = 3_000L, pollIntervalMs = 150L) {
            val r = getBestRoot()
            if (r != null && collectAllText(r).length > MIN_CONTENT_LENGTH) r else null
        } ?: return Result(false, "no_root_window")

        val targetKeywords = (
            step.searchTexts +
            step.additionalToggles +
            AdaptiveCatalog.mergeSearchTexts(service, step.id, step.searchTexts) +
            AdaptiveCatalog.mergeAdditionalToggles(service, step.id, step.additionalToggles)
        ).distinct()

        var toggledCount = 0
        var alreadyDoneCount = 0
        val handledTexts = mutableSetOf<String>()

        var scrollCount = 0
        while (scrollCount <= SWITCH_FALLBACK_SCROLLS) {
            if (cancelled) return Result(false, "cancelled")
            val currentRoot = getBestRoot() ?: break

            val switches = mutableListOf<AccessibilityNodeInfo>()
            collectSwitchLike(currentRoot, switches)

            for (sw in switches) {
                val matchedKeyword = findNearbyText(sw, targetKeywords)
                if (matchedKeyword != null && !handledTexts.contains(matchedKeyword)) {
                    val isChecked = sw.isChecked
                    AppLog.i(
                        TAG,
                        "Step ${step.id}: switch found '$matchedKeyword', isChecked=$isChecked, target=${step.targetChecked}, attempt=$attempt"
                    )

                    if (isChecked == step.targetChecked) {
                        alreadyDoneCount++
                        handledTexts.add(matchedKeyword)
                    } else {
                        OverlayController.updateStatus(service, "Оптимизация параметров системы…")
                        val clicked = tapNode(sw)
                        AppLog.i(TAG, "Step ${step.id}: tapNode result=$clicked for '$matchedKeyword'")

                        if (clicked) {
                            delay(UI_SETTLE_DELAY_MS)
                            if (step.confirmTexts.isNotEmpty()) {
                                handleConfirmation(step)
                            }
                            toggledCount++
                            handledTexts.add(matchedKeyword)
                        }
                    }
                }
            }

            if (handledTexts.isNotEmpty() || scrollCount >= SWITCH_FALLBACK_SCROLLS) {
                break
            }

            scrollCount++
            val scrollable = findTargetScrollableContainer(currentRoot)
            val scrolled = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (!scrolled) {
                swipeUp()
            } else {
                delay(400L)
            }
        }

        if (handledTexts.isEmpty()) {
            val screenText = collectAllText(root).take(600)
            val allSwitches = mutableListOf<AccessibilityNodeInfo>()
            collectSwitchLike(root, allSwitches)
            val switchDetails = allSwitches.joinToString { sw ->
                "${sw.className}(checked=${sw.isChecked})"
            }
            AppLog.w(
                TAG,
                "Step ${step.id}: NO SWITCH FOUND. targetKeywords=$targetKeywords. switchesCount=${allSwitches.size} details=[$switchDetails]. screen=[$screenText]"
            )
            return Result(false, "switch_not_found")
        }

        return when {
            toggledCount > 0 -> Result(true, "toggled")
            alreadyDoneCount > 0 -> Result(true, "already_done")
            else -> Result(true, "already_done")
        }
    }

    private suspend fun handleConfirmation(step: SimpleSteps.Step): Boolean {
        OverlayController.updateStatus(service, "Применение системных настроек…")

        val start = System.currentTimeMillis()
        val mustWaitMs = step.confirmWaitMs
        val fullDeadline = start + mustWaitMs + CONFIRM_TIMEOUT_EXTRA_MS
        var sawDisabledButton = false
        var button: AccessibilityNodeInfo? = null

        val confirmSynonyms = AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts)

        // Фаза 1: ждём минимальное время таймера (MSA ~11с)
        if (mustWaitMs > 5_000L) {
            OverlayController.updateStatus(service, "Синхронизация с системой…")
            while (System.currentTimeMillis() - start < mustWaitMs) {
                if (cancelled) return false
                val node = searchAllWindows(confirmSynonyms)
                    ?: findAnyNodeInAllWindows(confirmSynonyms)
                if (node != null && !node.isEnabled) sawDisabledButton = true
                delay(200L)
            }
        }

        // Фаза 2: ждём пока кнопка станет доступной
        val quickDeadline = System.currentTimeMillis() + CONFIRM_QUICK_POLL_MS
        while (System.currentTimeMillis() < fullDeadline) {
            if (cancelled) return false
            val node = searchAllWindows(confirmSynonyms)
                ?: findAnyNodeInAllWindows(confirmSynonyms)
            if (node != null) {
                if (node.isEnabled) {
                    button = node
                    break
                }
                sawDisabledButton = true
            } else if (!sawDisabledButton &&
                mustWaitMs <= 5_000L &&
                System.currentTimeMillis() >= quickDeadline
            ) {
                AppLog.i(TAG, "Step ${step.id}: no confirm button after quick poll")
                return false
            }
            delay(120L)
        }

        if (button == null) {
            button = searchAllWindows(confirmSynonyms)
                ?: findAnyNodeInAllWindows(confirmSynonyms)
        }
        if (button == null || !button.isEnabled) {
            AppLog.w(TAG, "Step ${step.id}: confirmation button not ready")
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
    // Поиск узлов и скроллинг
    // ═══════════════════════════════════════════════════════════════

    private fun isPackageInstalled(p: String): Boolean = try {
        service.packageManager.getPackageInfo(p, 0)
        true
    } catch (e: Exception) {
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
        searchAllWindows(texts)?.let { return it }

        var lastFingerprint = collectAllText(root)
        var unchangedCount = 0

        repeat(MAX_SCROLL_ATTEMPTS) { attemptIndex ->
            if (cancelled) return null

            val currentRoot = getBestRoot() ?: return null
            val scrollable = findTargetScrollableContainer(currentRoot)
            val scrolled = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (!scrolled) {
                AppLog.d(TAG, "findClickableByTextWithScroll: scroll action false, fallback to swipeUp (attempt $attemptIndex)")
                swipeUp()
            } else {
                AppLog.d(TAG, "findClickableByTextWithScroll: ACTION_SCROLL_FORWARD succeeded (attempt $attemptIndex)")
                delay(SCROLL_SETTLE_DELAY_MS)
            }

            val newRoot = getBestRoot() ?: return null
            val found = findClickableByText(newRoot, texts) ?: searchAllWindows(texts)
            if (found != null) {
                AppLog.i(TAG, "findClickableByTextWithScroll: found '${texts.firstOrNull()}' after scroll #$attemptIndex")
                return found
            }

            val after = collectAllText(newRoot)
            if (after == lastFingerprint) {
                unchangedCount++
                if (unchangedCount >= 3) {
                    AppLog.w(TAG, "findClickableByTextWithScroll: no content change after 3 scrolls — stopping")
                    return null
                }
            } else {
                unchangedCount = 0
            }
            lastFingerprint = after
        }
        return null
    }

    /**
     * Находит подходящий scrollable контейнер с учётом планшетного двухпанельного режима.
     */
    private fun findTargetScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val dm = service.resources.displayMetrics
        val isTablet = romProfile.isTablet

        if (isTablet) {
            val rightPaneThreshold = dm.widthPixels * 0.35f
            val rightContainer = findScrollableInPane(root, minX = rightPaneThreshold)
            if (rightContainer != null) {
                AppLog.d(TAG, "findTargetScrollableContainer: selected right-pane scrollable for tablet")
                return rightContainer
            }
        }

        findScrollableRecursive(root)?.let { return it }

        val targetPkg = root.packageName?.toString()
        for (w in service.windows) {
            val r = w.root ?: continue
            if (r == root || r.packageName?.toString() == service.packageName) continue
            if (targetPkg == null || r.packageName?.toString() == targetPkg) {
                findScrollableRecursive(r)?.let { return it }
            }
        }
        return null
    }

    private fun findScrollableInPane(node: AccessibilityNodeInfo?, minX: Float): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable && node.childCount > 0) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.left >= minX || rect.centerX() >= minX) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollableInPane(child, minX)?.let { return it }
        }
        return null
    }

    private fun findScrollableRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable && node.childCount > 0) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollableRecursive(child)?.let { return it }
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

    private fun collectSwitchLike(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch") || cls.contains("CheckBox") ||
            cls.contains("Toggle") || cls.contains("SlidingButton") || node.isCheckable
        ) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSwitchLike(it, result) }
        }
    }

    private fun findNearbyText(node: AccessibilityNodeInfo, texts: List<String>): String? {
        // Приоритет №1: Сопоставление по системному ID
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        for (id in KNOWN_SWITCH_VIEW_IDS) {
            if (viewId.contains(id)) {
                // Если переключатель идентифицирован по системному ID, ищем сопутствующий текст
                var checkAnc: AccessibilityNodeInfo? = node.parent
                repeat(NEARBY_ANCESTORS) {
                    val a = checkAnc ?: return@repeat
                    val collected = collectAllText(a)
                    for (t in texts) {
                        if (collected.contains(t, ignoreCase = true)) return t
                    }
                    checkAnc = a.parent
                }
            }
        }

        // Приоритет №2: Сопоставление с текстом/описанием в предках и соседки элемента
        var anc: AccessibilityNodeInfo? = node.parent
        repeat(NEARBY_ANCESTORS) {
            val a = anc ?: return null
            val collected = collectAllText(a)
            for (t in texts) {
                if (collected.contains(t, ignoreCase = true)) return t
                val words = t.split(" ", "_", "-").filter { it.length >= 4 }
                if (words.isNotEmpty() && words.all { w ->
                    val stem = w.take(w.length - 2).lowercase()
                    collected.lowercase().contains(stem)
                }) {
                    return t
                }
            }
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
        if (node == null || depth > TEXT_DEPTH || sb.length >= MAX_COLLECT_TEXT_CHARS) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            if (sb.length >= MAX_COLLECT_TEXT_CHARS) break
            collectTextRecursive(node.getChild(i), sb, depth + 1)
        }
    }

    private fun dp(v: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        service.resources.displayMetrics
    ).toInt()

    data class Result(
        val success: Boolean,
        val reason: String,
        val skipped: Boolean = false
    )
}
