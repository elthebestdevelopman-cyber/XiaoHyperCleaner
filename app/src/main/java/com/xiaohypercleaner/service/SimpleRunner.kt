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
import android.os.Build
import android.os.Bundle
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
        private const val CONFIRM_TIMEOUT_EXTRA_MS = 4_000L
        private const val CONFIRM_QUICK_POLL_MS = 1_600L
        private const val CONFIRM_DISMISS_WAIT_MS = 8_000L
        private const val MSA_POST_TOGGLE_DELAY_MS = 700L
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
            "com.miui.cleaner",
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
                // Ограничиваем вклад длины текста: экран Accessibility нашего сервиса
                // иначе «перебивает» короткий экран уведомлений приложения.
                val cappedLen = textLen.coerceAtMost(400L)
                val isAppWindow = w.type == AccessibilityWindowInfo.TYPE_APPLICATION
                val isSystemUi = pkg == "com.android.systemui"
                val isLauncher = pkg.contains("launcher") || pkg.contains("home")
                val isPermissionDialog = pkg == "com.google.android.permissioncontroller" ||
                        pkg == "com.android.packageinstaller" ||
                        pkg == "com.android.permissioncontroller"
                val isFocusedDialog = w.isFocused && (textLen in 1..500)

                val score = (cappedLen * 10L) +
                        (if (isAppWindow) 50_000L else 0L) +
                        (if (isFocusedDialog) 60_000L else 0L) +
                        (if (w.isFocused) 80_000L else 0L) +
                        (if (w.isActive) 40_000L else 0L) -
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
        if (launchesApp || step.id.startsWith("notif_")) {
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

        if (step.id == "sys_recommendations") {
            navigateViaSettingsSearch(
                listOf(
                    "Получать рекомендации",
                    "Receive recommendations",
                    "Прочие настройки",
                    "Additional settings"
                )
            )
        } else {
            drillDown(step)
        }
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
        if (step.id == "cleaner") {
            targets.add("com.miui.cleaner")
        }
        val isAppWindowStep = (
            step.launchPackage != null &&
                step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE
            ) || (
            resolved != null &&
                step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE &&
                !step.id.startsWith("notif_") &&
                step.intents.none { it.action == android.provider.Settings.ACTION_SETTINGS }
            )
        // Cleaner открывает отдельный пакет com.miui.cleaner — держим его в targetPackages
        targetPackages = if (isAppWindowStep || step.id == "cleaner") targets else emptySet()

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
            // Уводим с чужих экранов, затем жёстко открываем корень Settings
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(350L)
            } catch (_: Exception) {
            }

            val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            service.startActivity(intent)
            waitForPackage("com.android.settings", maxMs = 2_500L)

            // MIUI часто оставляет стек «Приложения» — выходим Back до корня
            for (i in 0 until 10) {
                if (cancelled) return
                val text = getBestRoot()?.let { collectAllText(it) }.orEmpty()
                if (isSettingsRootScreen(text)) {
                    AppLog.i(TAG, "resetSettingsToRoot: reopen Settings, ready=true")
                    return
                }
                // Застряли во вложенном экране Settings
                if (text.contains("Назад", ignoreCase = true) ||
                    text.contains("Navigate up", ignoreCase = true) ||
                    text.contains("Приложения", ignoreCase = true)
                ) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(280L)
                } else {
                    break
                }
            }

            // Повторный CLEAR_TASK если Back не помог
            service.startActivity(intent)
            delay(500L)
            val ready = isSettingsRootScreen(getBestRoot()?.let { collectAllText(it) }.orEmpty())
            AppLog.i(TAG, "resetSettingsToRoot: reopen Settings, ready=$ready")
        } catch (e: Exception) {
            AppLog.w(TAG, "resetSettingsToRoot failed: ${e.message}")
        }
    }

    /** Корень Settings: есть типичные разделы, нет «Назад» как единственного якоря подменю. */
    private fun isSettingsRootScreen(screenText: String): Boolean {
        if (screenText.isBlank()) return false
        val lower = screenText.lowercase()
        // Подменю «Приложения» / privacy и т.п.
        if (lower.contains("системные приложения") ||
            lower.contains("все приложения") ||
            lower.contains("клонирование приложений")
        ) {
            return false
        }
        val rootHints = listOf(
            "wi-fi", "wifi", "bluetooth", "о телефоне", "about phone",
            "sim-карты", "блокировка экрана", "lock screen", "экран", "display"
        )
        return rootHints.count { lower.contains(it) } >= 2
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
                // ACTION_SETTINGS без CLEAR_TASK возвращает MIUI на последний подэкран («Приложения»)
                if (launch.action == android.provider.Settings.ACTION_SETTINGS ||
                    launch.action == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS ||
                    launch.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
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
            val screenNow = root?.let { collectAllText(it) } ?: ""

            if (shouldSkipDrillLevel(screenNow, level, step, index)) {
                AppLog.i(
                    TAG,
                    "Step ${step.id}: skip drill '${level.firstOrNull()}' — already on target screen"
                )
                awaitScreen(nextScreenMarkers(step, index))
                delay(UI_SETTLE_DELAY_MS)
                continue
            }

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
                val cur = screenNow.take(120).ifEmpty { "no_root" }
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

    private fun isHomeSettingsWithoutSuggestions(screenText: String): Boolean {
        val lower = screenText.lowercase()
        val onHomeSettings =
            lower.contains("рабочий стол") ||
                lower.contains("home screen") ||
                lower.contains("poco launcher") ||
                lower.contains("персонализац") ||
                lower.contains("дополнительн")
        val hasSuggestionToggle =
            lower.contains("показывать предложения") ||
                lower.contains("show suggestions") ||
                lower.contains("показывать рекомендации") ||
                lower.contains("рекомендуемое сегодня")
        return onHomeSettings && !hasSuggestionToggle
    }

    /**
     * Открывает настройку через строку поиска Settings (обход ⋮ под блокирующим оверлеем).
     */
    private suspend fun navigateViaSettingsSearch(queries: List<String>): Boolean {
        OverlayController.updateStatus(service, "Поиск системных параметров…")
        val root0 = getBestRoot() ?: return false

        val searchLabels = listOf(
            "Поиск настроек", "Search settings", "Поиск", "Search", "搜寻设置"
        )
        var searchNode = findClickableByText(root0, searchLabels)
            ?: findSearchField(root0)
        if (searchNode == null) {
            searchNode = searchAllWindows(searchLabels)
        }
        if (searchNode == null) {
            AppLog.w(TAG, "navigateViaSettingsSearch: search field not found — Apps drill fallback")
            drillDownFallbackSysRecommendations()
            return false
        }

        tapNode(searchNode)
        delay(450L)

        val query = queries.firstOrNull { it.isNotBlank() } ?: return false
        val edit = findSearchField(getBestRoot()) ?: getBestRoot()?.let { findFocusedEditable(it) }
        if (edit != null) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query
            )
            val setOk = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            AppLog.i(TAG, "navigateViaSettingsSearch: SET_TEXT '$query' ok=$setOk")
            delay(900L)
        } else {
            AppLog.w(TAG, "navigateViaSettingsSearch: no editable after search tap")
        }

        for (q in queries) {
            if (cancelled) return false
            val hit = getBestRoot()?.let { findClickableByTextWithScroll(it, listOf(q)) }
                ?: searchAllWindows(listOf(q))
            if (hit != null) {
                AppLog.i(TAG, "navigateViaSettingsSearch: opening result '$q'")
                tapNode(hit)
                delay(500L)
                dismissFreshDeviceObstacles(preferDecline = false)
                val screen = getBestRoot()?.let { collectAllText(it) }.orEmpty()
                if (queries.any { screen.contains(it, ignoreCase = true) } ||
                    screen.contains("рекомендац", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        AppLog.w(TAG, "navigateViaSettingsSearch: no matching result for $queries")
        drillDownFallbackSysRecommendations()
        return false
    }

    private suspend fun drillDownFallbackSysRecommendations() {
        val fallback = SimpleSteps.ALL.firstOrNull { it.id == "sys_recommendations" } ?: return
        // Временно используем классический путь через копию с drillPath
        val withDrill = fallback.copy(
            drillPath = listOf(
                listOf("Приложения", "Apps", "Apps & notifications", "Приложения и уведомления"),
                listOf("Ещё", "Еще", "More options", "More", "⋮", "更多"),
                listOf("Прочие настройки", "Additional settings", "Other settings")
            )
        )
        drillDown(withDrill)
    }

    private fun findSearchField(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val cls = n.className?.toString().orEmpty()
            val vid = n.viewIdResourceName.orEmpty().lowercase()
            val hint = n.hintText?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val isEdit = cls.contains("EditText", ignoreCase = true) ||
                cls.contains("SearchView", ignoreCase = true) ||
                vid.contains("search") ||
                hint.contains("поиск", ignoreCase = true) ||
                hint.contains("search", ignoreCase = true) ||
                desc.contains("поиск", ignoreCase = true) ||
                desc.contains("search", ignoreCase = true)
            if (isEdit && (n.isClickable || n.isEditable || cls.contains("EditText"))) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isFocused && (n.isEditable ||
                    n.className?.toString()?.contains("EditText", ignoreCase = true) == true)
            ) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    /**
     * Не кликаем повторно по уровню, если экран уже нужный
     * (например «Все приложения» вместо хаба «Приложения»).
     */
    private fun shouldSkipDrillLevel(
        screenText: String,
        level: List<String>,
        step: SimpleSteps.Step,
        index: Int
    ): Boolean {
        if (screenText.isBlank()) return false
        val lower = screenText.lowercase()

        val isAppsLevel = level.any {
            it.contains("Приложения", ignoreCase = true) ||
                it.equals("Apps", ignoreCase = true) ||
                it.contains("Applications", ignoreCase = true) ||
                it.contains("Все приложения", ignoreCase = true) ||
                it.contains("All apps", ignoreCase = true)
        }
        if (isAppsLevel && (
                lower.contains("все приложения") ||
                    lower.contains("all apps") ||
                    lower.contains("all applications") ||
                    lower.contains("управление приложениями")
                )
        ) {
            return true
        }

        val nextMarkers = nextScreenMarkers(step, index)
            .filter { it.length >= 5 && !isIconOnly(it) }
        if (nextMarkers.any { lower.contains(it.lowercase()) }) {
            return true
        }

        return false
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

            // Диалоги отзыва разрешения / согласия — НЕ welcome, не жмём Skip/Отмена
            if (isRevokeOrConsentDialog(screenText)) {
                AppLog.i(TAG, "fresh-device dismiss: skip — revoke/consent dialog")
                return
            }

            // Экран с целевыми тумблерами — не трогаем «welcome»-логикой
            if (hasSwitchLikeControls(currentRoot) &&
                !looksLikeWelcomeOrPermission(screenText)
            ) {
                return
            }

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
            val mediaPkgs = targetPackages + preferredPackages
            val isMediaConsumerApp = mediaPkgs.any { pkg ->
                pkg.contains("player", ignoreCase = true) ||
                    pkg.contains("video", ignoreCase = true) ||
                    pkg.contains("cleaner", ignoreCase = true) ||
                    pkg.contains("fileexplorer", ignoreCase = true) ||
                    pkg.contains("Fileexplorer", ignoreCase = true) ||
                    pkg.contains("midrop", ignoreCase = true) ||
                    pkg.contains("thememanager", ignoreCase = true) ||
                    pkg.contains("browser", ignoreCase = true) ||
                    pkg.contains("downloads", ignoreCase = true) ||
                    pkg.contains("securitycenter", ignoreCase = true)
            } || currentPkg.contains("cleaner", ignoreCase = true) ||
                currentPkg.contains("fileexplorer", ignoreCase = true) ||
                currentPkg.contains("player", ignoreCase = true) ||
                currentPkg.contains("video", ignoreCase = true) ||
                (currentPkg == "com.miui.securitycenter" &&
                    (screenText.contains("Очистка", ignoreCase = true) ||
                        screenText.contains("Cleaner", ignoreCase = true) ||
                        screenText.contains("Cleanup", ignoreCase = true)))

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

            // 2b. Диалог «Нет доступа к файлам» / «Требуются разрешения»
            if (screenText.contains("Нет доступа к файлам", ignoreCase = true) ||
                screenText.contains("Требуются разрешения", ignoreCase = true) ||
                screenText.contains("Permissions required", ignoreCase = true) ||
                screenText.contains("не может получить доступ", ignoreCase = true) ||
                screenText.contains("can't access", ignoreCase = true) ||
                screenText.contains("cannot access", ignoreCase = true)
            ) {
                // Для Проводника/Очистки — пробуем «Разрешите» / Settings, иначе OK/Отмена
                if (isMediaConsumerApp &&
                    tryClickAny(listOf("Настройки", "Settings", "Разрешить", "Allow"))
                ) {
                    AppLog.i(TAG, "fresh-device dismiss: no-access -> open settings/allow")
                    delay(400L)
                    return@repeat
                }
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

            // 3. Соглашения первого запуска (только явный welcome, не отзыв согласия)
            if (looksLikeWelcomeTerms(screenText)) {
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

    private fun isRevokeOrConsentDialog(screenText: String): Boolean {
        val lower = screenText.lowercase()
        return lower.contains("отзыв разрешения") ||
            lower.contains("отозвать разрешение") ||
            lower.contains("revoke permission") ||
            lower.contains("отзыв согласия") ||
            lower.contains("withdraw consent") ||
            (lower.contains("отозвать") && lower.contains("отмена"))
    }

    private fun looksLikeWelcomeTerms(screenText: String): Boolean {
        if (isRevokeOrConsentDialog(screenText)) return false
        val lower = screenText.lowercase()
        val hasPolicy = lower.contains("политика конфиденциальности") ||
            lower.contains("условия использования") ||
            lower.contains("юридические документы") ||
            lower.contains("privacy policy") ||
            lower.contains("terms of service") ||
            lower.contains("legal documents")
        val hasWelcome = lower.contains("добро пожаловать") ||
            lower.contains("welcome") ||
            lower.contains("первый запуск") ||
            lower.contains("get started")
        return hasPolicy && (hasWelcome || lower.contains("соглас"))
    }

    private fun looksLikeWelcomeOrPermission(screenText: String): Boolean {
        val lower = screenText.lowercase()
        return lower.contains("разрешить приложению") ||
            (lower.contains("allow") && lower.contains("access")) ||
            lower.contains("добро пожаловать") ||
            lower.contains("welcome") ||
            lower.contains("условия использования")
    }

    private fun hasSwitchLikeControls(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        val list = mutableListOf<AccessibilityNodeInfo>()
        collectSwitchLike(root, list)
        return list.isNotEmpty()
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
                ?: findOverflowByDescription(root)
            if (overflow != null) {
                val clicked = tapNode(overflow)
                if (clicked) {
                    AppLog.i(TAG, "tapTopRight: overflow via accessibility")
                    return true
                }
            }
        }

        // Без снятия оверлея: пробуем ImageButton в правом верхнем углу через дерево
        val corner = root?.let { findTopRightClickable(it) }
        if (corner != null && tapNode(corner)) {
            AppLog.i(TAG, "tapTopRight: top-right clickable via tree")
            delay(POPUP_MENU_DELAY_MS)
            if (expected.isEmpty() || searchAllWindows(expected) != null) return true
        }

        AppLog.w(TAG, "tapTopRight: no overflow node (overlay stays blocking, no gesture)")
        return false
    }

    private fun findOverflowByDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val markers = OVERFLOW_TEXTS.map { it.lowercase() }
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
            val vid = n.viewIdResourceName?.lowercase().orEmpty()
            if (markers.any { desc.contains(it) } ||
                vid.contains("more") || vid.contains("overflow") || vid.contains("menu")
            ) {
                if (n.isClickable) return n
                var p: AccessibilityNodeInfo? = n.parent
                repeat(3) {
                    if (p?.isClickable == true) return p
                    p = p?.parent
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun findTopRightClickable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = service.resources.displayMetrics
        val minX = dm.widthPixels * 0.75f
        val maxY = dm.heightPixels * 0.18f
        var best: AccessibilityNodeInfo? = null
        var bestScore = Float.MAX_VALUE

        fun walk(n: AccessibilityNodeInfo) {
            if (n.isClickable) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()
                if (cx >= minX && cy in 1f..maxY && rect.width() in 24..200) {
                    val score = cy + (dm.widthPixels - cx) / 10f
                    if (score < bestScore) {
                        bestScore = score
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        return best
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
        // Оверлей всегда остаётся blocking — иначе пользователь может случайно нажать.
        // Жесты accessibility (dispatchGesture) и ACTION_CLICK работают без «просвета».
        block()
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

        // Диалог отзыва мог остаться с прошлой попытки — добиваем его
        val screen0 = collectAllText(root)
        if (step.confirmTexts.isNotEmpty() && isRevokeOrConsentDialog(screen0)) {
            AppLog.i(TAG, "Step ${step.id}: pending confirm dialog — finishing first")
            if (handleConfirmation(step) && verifySwitchReachedTarget(step, "msa")) {
                return Result(true, "toggled")
            }
        }

        var scrollCount = 0
        while (scrollCount <= SWITCH_FALLBACK_SCROLLS) {
            if (cancelled) return Result(false, "cancelled")
            val currentRoot = getBestRoot() ?: break

            val switches = mutableListOf<AccessibilityNodeInfo>()
            collectSwitchLike(currentRoot, switches)

            for (sw in switches) {
                val matchedKeyword = findNearbyText(sw, targetKeywords)
                if (matchedKeyword != null && !handledTexts.contains(matchedKeyword)) {
                    val isChecked = nodeIsChecked(sw)
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
                            if (step.confirmTexts.isNotEmpty()) {
                                delay(MSA_POST_TOGGLE_DELAY_MS)
                                val confirmed = handleConfirmation(step)
                                if (!confirmed) {
                                    AppLog.w(TAG, "Step ${step.id}: confirmation failed for '$matchedKeyword'")
                                    continue
                                }
                                if (!verifySwitchReachedTarget(step, matchedKeyword)) {
                                    AppLog.w(TAG, "Step ${step.id}: switch '$matchedKeyword' not verified off")
                                    return Result(false, "toggle_not_verified")
                                }
                            } else {
                                delay(UI_SETTLE_DELAY_MS)
                                if (!verifySwitchReachedTarget(step, matchedKeyword)) {
                                    AppLog.w(TAG, "Step ${step.id}: soft verify miss for '$matchedKeyword' — re-tap")
                                    delay(400L)
                                    val rootRetry = getBestRoot()
                                    val switchesRetry = mutableListOf<AccessibilityNodeInfo>()
                                    rootRetry?.let { collectSwitchLike(it, switchesRetry) }
                                    val sw2 = switchesRetry.firstOrNull {
                                        findNearbyText(it, listOf(matchedKeyword)) != null &&
                                            nodeIsChecked(it) != step.targetChecked
                                    }
                                    if (sw2 != null) {
                                        tapNode(sw2)
                                        delay(450L)
                                    }
                                    if (!verifySwitchReachedTarget(step, matchedKeyword)) {
                                        AppLog.w(TAG, "Step ${step.id}: soft verify still miss for '$matchedKeyword'")
                                    }
                                }
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
            // POCO Launcher: после отзыва msa пункта «Показывать предложения» часто нет
            if (step.id == "home_suggestions" && isHomeSettingsWithoutSuggestions(screenText)) {
                AppLog.i(TAG, "Step home_suggestions: feature absent on this launcher — skip OK")
                return Result(true, "feature_absent")
            }
            val allSwitches = mutableListOf<AccessibilityNodeInfo>()
            collectSwitchLike(root, allSwitches)
            val switchDetails = allSwitches.joinToString { sw ->
                "${sw.className}(checked=${nodeIsChecked(sw)})"
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

        // Если кнопка уже активна (повтор после сбоя) — не ждём полный таймер
        val earlyBtn = findExactConfirmButton(confirmSynonyms)
        val alreadyEnabled = earlyBtn != null && earlyBtn.isEnabled

        if (!alreadyEnabled && mustWaitMs > 5_000L) {
            OverlayController.updateStatus(service, "Синхронизация с системой…")
            while (System.currentTimeMillis() - start < mustWaitMs) {
                if (cancelled) return false
                val node = findExactConfirmButton(confirmSynonyms)
                if (node != null && !node.isEnabled) sawDisabledButton = true
                if (node != null && node.isEnabled && sawDisabledButton) {
                    AppLog.i(
                        TAG,
                        "Step ${step.id}: confirm enabled early after ${System.currentTimeMillis() - start}ms"
                    )
                    break
                }
                delay(200L)
            }
        } else if (alreadyEnabled) {
            AppLog.i(TAG, "Step ${step.id}: confirm button already enabled — skip timer wait")
        }

        // Фаза 2: ждём пока кнопка станет доступной
        val quickDeadline = System.currentTimeMillis() + CONFIRM_QUICK_POLL_MS
        while (System.currentTimeMillis() < fullDeadline) {
            if (cancelled) return false
            val node = findExactConfirmButton(confirmSynonyms)
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
            button = findExactConfirmButton(confirmSynonyms)
        }
        if (button == null || !button.isEnabled) {
            AppLog.w(TAG, "Step ${step.id}: confirmation button not ready")
            return false
        }

        AppLog.i(
            TAG,
            "Step ${step.id}: confirmation ready after ${System.currentTimeMillis() - start}ms"
        )

        // Несколько попыток: MIUI часто «глотает» первый ACTION_CLICK на диалоге
        repeat(6) { attempt ->
            val btn = findExactConfirmButton(confirmSynonyms) ?: button
            val label = btn.text?.toString() ?: btn.contentDescription?.toString() ?: "?"
            AppLog.i(TAG, "Step ${step.id}: confirm tap attempt=${attempt + 1} label='$label'")
            tapNode(btn)
            delay(700L)
            val dismissWait = if (step.id == "msa") CONFIRM_DISMISS_WAIT_MS else 2_500L
            if (waitConfirmDialogDismissed(step, timeoutMs = dismissWait)) {
                return true
            }
        }
        AppLog.w(TAG, "Step ${step.id}: confirmation click did not dismiss dialog")
        return false
    }

    /**
     * Ищет именно кнопку подтверждения с точным текстом («Отозвать»),
     * а не текст вопроса «Отозвать разрешение?».
     */
    private fun findExactConfirmButton(texts: List<String>): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == service.packageName) continue
                collectExactTextNodes(r, texts, candidates)
            }
        } catch (_: Exception) {
        }
        if (candidates.isEmpty()) return null

        // Приоритет: Button / кликабельный / самый короткий точный текст / ниже на экране
        return candidates.minWithOrNull(
            compareBy<AccessibilityNodeInfo> { n ->
                val cls = n.className?.toString().orEmpty()
                when {
                    cls.contains("Button", ignoreCase = true) -> 0
                    n.isClickable -> 1
                    else -> 2
                }
            }.thenBy { n ->
                (n.text?.toString() ?: n.contentDescription?.toString() ?: "").length
            }.thenByDescending { n ->
                val rect = Rect()
                n.getBoundsInScreen(rect)
                rect.centerY()
            }
        )
    }

    private fun collectExactTextNodes(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
        if (label.isNotEmpty()) {
            for (t in texts) {
                if (label.equals(t, ignoreCase = true)) {
                    out.add(node)
                    break
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectExactTextNodes(it, texts, out) }
        }
    }

    /** Ждёт исчезновения диалога подтверждения (MSA «Отозвать» и т.п.). */
    private suspend fun waitConfirmDialogDismissed(
        step: SimpleSteps.Step,
        timeoutMs: Long = 4_000L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            val screen = getBestRoot()?.let { collectAllText(it) }.orEmpty()
            if (!isRevokeOrConsentDialog(screen)) return true
            delay(200L)
        }
        val finalScreen = getBestRoot()?.let { collectAllText(it).take(200) }.orEmpty()
        AppLog.w(TAG, "Step ${step.id}: dialog still present: [$finalScreen]")
        return false
    }

    /** Проверяет, что целевой тумблер дошёл до targetChecked. */
    private fun verifySwitchReachedTarget(step: SimpleSteps.Step, keyword: String): Boolean {
        val root = getBestRoot() ?: return false
        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitchLike(root, switches)
        for (sw in switches) {
            val near = findNearbyText(sw, listOf(keyword)) ?: continue
            if (near.equals(keyword, ignoreCase = true) || near.contains(keyword, ignoreCase = true)) {
                val checked = nodeIsChecked(sw)
                val ok = checked == step.targetChecked
                AppLog.i(
                    TAG,
                    "Step ${step.id}: verify '$keyword' checked=$checked target=${step.targetChecked} ok=$ok"
                )
                return ok
            }
        }
        // Тумблер мог пропасть с экрана после отзыва — для MSA это успех
        if (step.id == "msa") {
            val screen = collectAllText(root)
            val stillListed = screen.contains("msa", ignoreCase = true)
            AppLog.i(TAG, "Step msa: verify by absence on list, stillListed=$stillListed")
            return !stillListed || !screen.contains("Отозвать", ignoreCase = true)
        }
        return true
    }

    /**
     * API 36+: [AccessibilityNodeInfo.isChecked] deprecated → [AccessibilityNodeInfo.getChecked].
     * На старых SDK остаётся boolean isChecked.
     */
    @Suppress("DEPRECATION")
    private fun nodeIsChecked(node: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            node.isChecked
        }
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

        data class Hit(val node: AccessibilityNodeInfo, val label: String, val query: String)

        val hits = mutableListOf<Hit>()
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in nodes) {
                val nodeText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
                if (nodeText.isEmpty()) continue
                // Отсекаем ложные подстроки («Рабочий стол» ⊂ «Управление ярлыками на рабочем столе»)
                val exact = nodeText.equals(text, ignoreCase = true)
                val close = exact ||
                    (nodeText.length <= text.length + 8 &&
                        nodeText.contains(text, ignoreCase = true))
                if (!close && !exact) continue
                if (!exact && nodeText.length > text.length * 2) continue

                val clickable = when {
                    node.isClickable -> node
                    else -> {
                        var current: AccessibilityNodeInfo? = node.parent
                        var depth = 0
                        var found: AccessibilityNodeInfo? = null
                        while (current != null && depth < MAX_PARENT_DEPTH) {
                            if (current.isClickable) {
                                found = current
                                break
                            }
                            current = current.parent
                            depth++
                        }
                        found
                    }
                } ?: continue
                hits.add(Hit(clickable, nodeText, text))
            }
        }
        if (hits.isEmpty()) return null

        // Предпочитаем точное совпадение и более короткий label
        return hits.minWithOrNull(
            compareBy<Hit> { h -> if (h.label.equals(h.query, ignoreCase = true)) 0 else 1 }
                .thenBy { it.label.length }
        )?.node
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
        // Короткие слова («Вкл», «On») дают ложные срабатывания на любом тумблере.
        val usable = texts.filter { it.isNotBlank() && (it.length >= 4 || it.equals("msa", ignoreCase = true)) }
        if (usable.isEmpty()) return null

        // Приоритет №1: Сопоставление по системному ID
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        for (id in KNOWN_SWITCH_VIEW_IDS) {
            if (viewId.contains(id)) {
                var checkAnc: AccessibilityNodeInfo? = node.parent
                repeat(NEARBY_ANCESTORS) {
                    val a = checkAnc ?: return@repeat
                    val collected = collectAllText(a)
                    for (t in usable) {
                        if (collected.contains(t, ignoreCase = true)) return t
                    }
                    checkAnc = a.parent
                }
            }
        }

        // Приоритет №2: текст рядом с переключателем (точное вхождение, без коротких stem)
        var anc: AccessibilityNodeInfo? = node.parent
        repeat(NEARBY_ANCESTORS) {
            val a = anc ?: return null
            val collected = collectAllText(a)
            for (t in usable) {
                if (collected.contains(t, ignoreCase = true)) return t
                val words = t.split(" ", "_", "-").filter { it.length >= 5 }
                if (words.isNotEmpty() && words.all { w ->
                    collected.contains(w, ignoreCase = true)
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
