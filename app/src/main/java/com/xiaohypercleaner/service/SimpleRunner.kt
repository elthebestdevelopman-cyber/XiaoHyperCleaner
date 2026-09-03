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
import android.media.AudioManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomProfile
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
 * - getBestRoot(): целевой app-пакет строго выше Settings
 * - resolvePackage(): alias + RomProfile (CN/Global)
 * - MSA: короткий poll подтверждения; success если тумблер уже в цели
 * - Intent clone (не мутируем static SimpleSteps)
 * - AdaptiveCatalog из assets для OTA-адаптации строк/пакетов
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"

        private const val BASE_STEP_TIMEOUT_MS = 16_000L
        private const val MSA_STEP_TIMEOUT_MS = 40_000L
        private const val CONFIRM_TIMEOUT_EXTRA_MS = 1_500L
        /** Короткий poll: если кнопки нет (HyperOS toggle-only) — не жжём 13с */
        private const val CONFIRM_QUICK_POLL_MS = 1_600L
        private const val SCREEN_WAIT_MS = 1_400L
        private const val CONTENT_WAIT_MS = 1_400L
        private const val UI_SETTLE_DELAY_MS = 120L
        private const val POPUP_MENU_DELAY_MS = 350L
        private const val HOME_RESET_DELAY_MS = 200L
        private const val FORCE_STOP_DELAY_MS = 250L
        private const val SCROLL_SETTLE_DELAY_MS = 160L
        private const val TAP_DURATION_MS = 60L
        private const val RETRY_DELAY_MS = 280L

        private const val MAX_POLLING_MS = 1_200L
        private const val POLLING_INTERVAL_MS = 70L
        private const val MIN_CONTENT_LENGTH = 6
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 5
        private const val SWITCH_FALLBACK_SCROLLS = 4
        private const val TEXT_DEPTH = 40
        private const val MAX_COLLECT_TEXT_CHARS = 4000
        private const val NEARBY_ANCESTORS = 3
        private const val SWITCH_ANCESTOR_DEPTH = 6
        private const val MAX_SCROLL_WITHOUT_PROGRESS = 2
        private const val MAX_FRESH_DISMISS_ROUNDS = 4

        private val TOP_RIGHT_Y_DP = listOf(56, 76, 96)

        private val OVERFLOW_TEXTS = listOf(
            "Ещё", "Еще", "More options", "More", "Дополнительно", "Другие параметры", "⋮"
        )

        /** Свежий телефон: сначала уход / пропуск, не «Согласиться» */
        private val SKIP_TEXTS = listOf(
            "Пропуск", "Пропустить", "Skip", "Позже", "Later", "Не сейчас", "Not now",
            "Закрыть", "Close", "Нет, спасибо", "No thanks", "Без входа",
            "Continue without account", "Гостевой режим", "Guest",
            "Напомнить позже", "Remind me later", "Не входить", "Skip login",
            "Понятно", "Got it", "Не обновлять", "Don't update",
            "跳过", "稍后", "关闭", "Omitir", "Más tarde", "Cerrar"
        )

        private val DECLINE_TEXTS = listOf(
            "Отмена", "Cancel", "Отклонить", "Decline", "Не согласен", "Disagree",
            "Запретить", "Deny", "Don't allow", "Не разрешать",
            "取消", "拒绝", "Cancelar", "Rechazar"
        )

        /** Согласие первого запуска — БЕЗ «Начать»/«Start» (Безопасность запускает очистку) */
        private val ENTER_TEXTS_DEFAULT = listOf(
            "Согласиться", "Принять", "Agree", "Accept",
            "Понятно", "Got it", "Далее", "Next",
            "Продолжить", "Continue", "Начать использование", "Начать работу",
            "同意", "接受", "下一步",
            "Aceptar", "Siguiente", "Entendido"
        )

        /** Runtime-разрешения: для медиа/файлов предпочитаем отказ — рекламные тумблеры
         *  не требуют доступ к фото, а «Разрешить» ломает оверлей и цикл кликов */
        private val PERMISSION_DENY_TEXTS = listOf(
            "Запретить", "Deny", "Don't allow", "Не разрешать", "Отклонить",
            "拒绝", "不允许", "Rechazar", "Denegar"
        )

        private val PERMISSION_ALLOW_TEXTS_DEFAULT = listOf(
            "Разрешить", "Allow", "While using the app", "При использовании",
            "Только в этот раз", "Only this time",
            "允许", "仅在使用中允许", "Permitir"
        )

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.securitycore",
            "com.xiaomi.misettings"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    @Volatile
    private var cancelled: Boolean = false

    /** Целевой app-пакет шага (строго предпочтителен над Settings) */
    private var targetPackages: Set<String> = emptySet()

    /** Settings + target — fallback-пул */
    private var preferredPackages: Set<String> = emptySet()

    private val romProfile: RomProfile by lazy { RomProfile.detect(service) }

    private fun catalog() = AdaptiveCatalog.ensureLoaded(service)

    private fun skipTexts(): List<String> =
        (catalog().skip + SKIP_TEXTS).distinct()

    private fun declineTexts(): List<String> =
        (catalog().decline + DECLINE_TEXTS).distinct()

    private fun enterTexts(): List<String> =
        (catalog().enter + ENTER_TEXTS_DEFAULT).distinct()

    private fun permissionAllowTexts(): List<String> =
        (catalog().permissionAllow + PERMISSION_ALLOW_TEXTS_DEFAULT).distinct()
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
            restoreMediaVolume()
            isRunning = false
        }
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

    // ═══════════════════════════════════════════════════════════════
    // КЛЮЧЕВОЙ ФИКС (beta10): лучшее окно вместо rootInActiveWindow
    // ═══════════════════════════════════════════════════════════════

    /**
     * Корень целевого окна.
     * Приоритет: 1) target app  2) Settings  3) прочие  — никогда наш оверлей.
     */
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

        buildPreferredPackages(step, resolvedPkg)

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
            // Mi Video / Music — приглушаем звук, чтобы рекомендации не играли на всю громкость
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
            OverlayController.updateStatus(service, "Открываем нужный экран…")
            val isSettingsRootStep = step.launchPackage == null &&
                    step.intents.any { it.action == android.provider.Settings.ACTION_SETTINGS }

            if (isSettingsRootStep) {
                resetSettingsToRoot()
            }

            // Быстрый путь: поиск в Настройках только для системных шагов без launchPackage
            val useSearch = isSettingsRootStep &&
                    !step.id.startsWith("notif_") &&
                    step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE &&
                    step.searchTexts.any { it.length >= 6 }
            val jumped = useSearch && tryJumpViaSettingsSearch(step.searchTexts)
            if (!jumped) {
                val intents = buildResolvedIntents(step, resolvedPkg)
                if (!openTargetScreen(intents) && !waitForContent(MAX_POLLING_MS)) {
                    AppLog.w(TAG, "Step ${step.id}: no screen opened")
                    return Result(false, "no_screen_opened")
                }
                if (isSettingsRootStep) {
                    resetSettingsToRoot()
                }
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
            delay(step.preDrillWaitMs)
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

        // Если шаг открывал экран уведомлений или внешнее приложение — возвращаемся назад,
        // чтобы не оставаться внутри чужого приложения (например, Темы / Браузер)
        val openedSubScreen = step.id.startsWith("notif_") || step.launchPackage != null
        if (openedSubScreen && !cancelled) {
            delay(250L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }

        // ФИНАЛЬНЫЙ ВОЗВРАТ В ПРИЛОЖЕНИЕ (для экрана результата и восстановления фокуса)
        if (step.id == SimpleSteps.ALL.lastOrNull()?.id && !cancelled) {
            AppLog.i(TAG, "Last step finished — returning to app")
            val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (intent != null) {
                runCatching { service.startActivity(intent) }
            }
        }

        return result
    }

    private fun resolveInstalledPackage(step: SimpleSteps.Step): String? {
        val defaults = linkedSetOf<String>()
        step.requiredPackages.forEach { defaults.add(it) }
        step.launchPackage?.let { defaults.add(it) }
        val ordered = AdaptiveCatalog.packagesForStep(
            service,
            step.id,
            defaults.toList(),
            romProfile
        )
        return ordered.firstOrNull { isPackageInstalled(it) }
    }

    private fun buildPreferredPackages(step: SimpleSteps.Step, resolved: String?) {
        val targets = linkedSetOf<String>()
        resolved?.let { targets.add(it) }
        step.launchPackage?.let { targets.add(it) }
        // Для in-app шагов Settings не должен перебивать окно приложения
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

    private suspend fun resetSettingsToRoot() {
        val root = getBestRoot() ?: return
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "com.android.settings") return

        val backTexts = listOf("Назад", "Back", "返回", "Atrás")
        var popCount = 0
        while (popCount < 5) {
            val currentRoot = getBestRoot() ?: break
            if (currentRoot.packageName?.toString() != "com.android.settings") break

            val currentText = collectAllText(currentRoot)
            val hasSearch = currentText.contains("Поиск настроек", ignoreCase = true) ||
                    currentText.contains("Search settings", ignoreCase = true)
            val backNode = searchAllWindows(backTexts) ?: findAnyNodeInAllWindows(backTexts)
            if (backNode == null || (hasSearch && popCount > 0)) break

            AppLog.i(TAG, "resetSettingsToRoot: popping sub-screen #$popCount via back button")
            tapNode(backNode)
            delay(350L)
            popCount++
        }
        if (popCount > 0) {
            delay(UI_SETTLE_DELAY_MS)
            AppLog.i(TAG, "resetSettingsToRoot: completed, popped $popCount screens")
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
                // Клонируем: static Intent из SimpleSteps нельзя мутировать
                val launch = Intent(intent)
                val resolved = launch.resolveActivity(service.packageManager)
                if (resolved == null &&
                    launch.action != null &&
                    !launch.action!!.startsWith("android.settings")
                ) continue

                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
        // Иконки ⚙/⋮ и слово «Настройки» на главном экране Безопасности дают ложный match
        val filtered = next.filterNot { it.isBlank() || isIconOnly(it) }
            .filterNot { it.equals("Настройки", ignoreCase = true) || it.equals("Settings", ignoreCase = true) }
        return if (filtered.isNotEmpty()) filtered.distinct()
        else step.searchTexts.filterNot { it.isBlank() }.distinct()
    }

    private suspend fun tryJumpViaSettingsSearch(queries: List<String>): Boolean {
        // Более длинные фразы реже дают ложный hit в поиске Настроек
        val query = queries
            .filter { it.length in 4..48 }
            .maxByOrNull { it.length }
            ?: return false
        return try {
            OverlayController.updateStatus(service, "Поиск в настройках…")
            val settings = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            service.startActivity(settings)
            if (!waitForContent(MAX_POLLING_MS)) return false

            val searchLabels = (catalog().settingsSearch + listOf(
                "Поиск", "Search", "搜索", "Buscar", "Search settings", "Поиск настроек"
            )).distinct()
            val searchNode = searchAllWindows(searchLabels)
                ?: findAnyNodeInAllWindows(searchLabels)
            if (searchNode != null) {
                tapNode(searchNode)
                delay(200L)
            }

            val root = getBestRoot() ?: return false
            val edit = findEditable(root) ?: return false
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query
            )
            if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                AppLog.w(TAG, "settings search: SET_TEXT failed")
                return false
            }
            delay(350L)

            val hit = findClickableByTextWithScroll(getBestRoot(), queries)
                ?: searchAllWindows(queries)
                ?: return false
            tapNode(hit)
            waitForContent(MAX_POLLING_MS)
            AppLog.i(TAG, "settings search jump ok for '$query'")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "tryJumpViaSettingsSearch: ${e.message}")
            false
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val cls = root.className?.toString().orEmpty()
        if (root.isEditable || cls.contains("EditText")) return root
        for (i in 0 until root.childCount) {
            findEditable(root.getChild(i))?.let { return it }
        }
        return null
    }

    /**
     * Свежий ROM: онбординг / политика / логин / runtime-разрешения.
     * Медиа/фото — ЗАПРЕТИТЬ (не нужны для тумблеров рекламы; «Разрешить» ломает оверлей).
     */
    private suspend fun dismissFreshDeviceObstacles(preferDecline: Boolean) {
        OverlayController.updateStatus(service, "Закрываем диалоги первого запуска…")
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

            // 2b. Диалог «Требуются разрешения» после отказа — закрываем «Отмена»
            if (screenText.contains("Требуются разрешения", ignoreCase = true) ||
                screenText.contains("Permissions required", ignoreCase = true) ||
                screenText.contains("не может получить доступ", ignoreCase = true) ||
                screenText.contains("can't access", ignoreCase = true) ||
                screenText.contains("cannot access", ignoreCase = true)
            ) {
                val cancelTexts = listOf("Отмена", "Cancel", "OK", "Понятно", "Got it")
                // Предпочитаем Отмена, чтобы не уходить в системные настройки
                if (tryClickAny(listOf("Отмена", "Cancel")) || tryClickAny(cancelTexts)) {
                    AppLog.i(TAG, "fresh-device dismiss: permission-required dialog -> cancel")
                    delay(250L)
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
                // Mi Music / свежий MIUI: чекбоксы обязательны — сначала «Выбрать все»
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
                // Обычные (не медиа) разрешения — разрешить один раз
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
            var lastFingerprint = ""
            var unchangedCount = 0

            repeat(SWITCH_FALLBACK_SCROLLS) { scrollIdx ->
                if (cancelled) return Result(false, "cancelled")

                val scrollRoot = getBestRoot() ?: return Result(false, "no_root_window")
                val scrollable = findScrollableContainer(scrollRoot)
                val scrolled = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
                if (!scrolled) {
                    AppLog.d(TAG, "Step ${step.id}: switch scroll action false, fallback to swipeUp (scroll #$scrollIdx)")
                    swipeUp()
                } else {
                    AppLog.d(TAG, "Step ${step.id}: switch ACTION_SCROLL_FORWARD succeeded (scroll #$scrollIdx)")
                    delay(400L)
                }

                val newRoot = getBestRoot() ?: return Result(false, "no_root_window")
                val found = findSwitchByText(newRoot, step.searchTexts)

                if (found != null) {
                    AppLog.i(TAG, "Step ${step.id}: switch found after scroll #$scrollIdx")
                    switchNode = found
                    return@repeat
                }

                val after = collectAllText(newRoot)
                if (after == lastFingerprint) {
                    unchangedCount++
                    if (unchangedCount >= 3) {
                        AppLog.w(TAG, "Step ${step.id}: scroll content unchanged — stopping")
                        return@repeat
                    }
                } else {
                    unchangedCount = 0
                }
                lastFingerprint = after
            }

            if (switchNode == null) {
                switchNode = findSwitchInAllWindows(step.searchTexts)
            }
        }

        if (switchNode == null) {
            val screenText = collectAllText(root).take(600)
            val allSwitches = mutableListOf<AccessibilityNodeInfo>()
            collectSwitchLike(root, allSwitches)
            val switchDetails = allSwitches.joinToString { sw ->
                "${sw.className}(checked=${sw.isChecked})"
            }
            AppLog.w(
                TAG,
                "Step ${step.id}: NO SWITCH FOUND. searchTexts=${step.searchTexts}. switchesCount=${allSwitches.size} details=[$switchDetails]. screen=[$screenText]"
            )
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

        // Диалог подтверждения (MSA) — короткий poll; HyperOS часто без «Отозвать»
        var confirmationClicked = false
        if (step.confirmTexts.isNotEmpty()) {
            confirmationClicked = handleConfirmation(step)
            if (!confirmationClicked) {
                AppLog.w(TAG, "Step ${step.id}: confirmation not completed — verify switch")
            }
        }

        if (confirmationClicked) {
            if (step.additionalToggles.isNotEmpty()) toggleAdditional(step.additionalToggles)
            return Result(true, "confirmed")
        }

        // Верификация для обычных шагов / MSA без кнопки revoke
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
        // MSA: 10-сек таймер. Кнопка «Отозвать» может быть isEnabled=true сразу,
        // но клик не срабатывает до конца отсчёта — всегда ждём confirmWaitMs.
        val mustWaitMs = step.confirmWaitMs
        val fullDeadline = start + mustWaitMs + CONFIRM_TIMEOUT_EXTRA_MS
        var sawDisabledButton = false
        var button: AccessibilityNodeInfo? = null

        // Фаза 1: ждём минимальное время таймера (MSA ~11с).
        // Короткий confirmWaitMs (диалог Безопасности) — без принудительной паузы.
        if (mustWaitMs > 5_000L) {
            OverlayController.updateStatus(service, "Ждём таймер отзыва…")
            while (System.currentTimeMillis() - start < mustWaitMs) {
                if (cancelled) return false
                val node = searchAllWindows(step.confirmTexts)
                    ?: findAnyNodeInAllWindows(step.confirmTexts)
                if (node != null && !node.isEnabled) sawDisabledButton = true
                delay(200L)
            }
        }

        // Фаза 2: ждём пока кнопка станет enabled (или быстрый выход если кнопки нет)
        val quickDeadline = System.currentTimeMillis() + CONFIRM_QUICK_POLL_MS
        while (System.currentTimeMillis() < fullDeadline) {
            if (cancelled) return false
            val node = searchAllWindows(step.confirmTexts)
                ?: findAnyNodeInAllWindows(step.confirmTexts)
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
            button = searchAllWindows(step.confirmTexts)
                ?: findAnyNodeInAllWindows(step.confirmTexts)
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
        searchAllWindows(texts)?.let { return it }

        var lastFingerprint = collectAllText(root)
        var unchangedCount = 0

        repeat(MAX_SCROLL_ATTEMPTS) { attemptIndex ->
            if (cancelled) return null

            val currentRoot = getBestRoot() ?: return null
            val scrollable = findScrollableContainer(currentRoot)
            val scrolled = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (!scrolled) {
                // Если performAction не сработал или scrollable не найден напрямую, делаем жестовый свайп
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

    private fun findScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
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

    @Suppress("DEPRECATION")
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val switches = mutableListOf<AccessibilityNodeInfo>()
        collectSwitchLike(root, switches)

        if (switches.isEmpty()) {
            val targetPkg = root.packageName?.toString()
            for (w in service.windows) {
                val r = w.root ?: continue
                if (r == root || r.packageName?.toString() == service.packageName) continue
                if (targetPkg == null || r.packageName?.toString() == targetPkg) {
                    collectSwitchLike(r, switches)
                }
            }
        }

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
                        cls.contains("Toggle") || cls.contains("SlidingButton") || current.isCheckable
                    ) return current
                    current = current.parent
                    depth++
                }
            }
        }

        // Если на экране ровно один переключатель — НЕ автовыбираем без совпадения текста.
        // Ложный клик (карусель на экране Конфиденциальность) ломает статистику.
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
            cls.contains("Toggle") || cls.contains("SlidingButton") || node.isCheckable
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
            for (t in texts) {
                if (collected.contains(t, ignoreCase = true)) return t
                // Сравнение по корням ключевых слов (длина >= 4) для учёта падежей и склонений
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
}