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
 * Финальная версия SimpleRunner — ядро UI-автоматизации Simple Mode.
 *
 * Архитектура:
 * 1. resetToHome() перед каждым шагом — сброс стека активити MIUI.
 * 2. openTargetScreen с NEW_TASK + CLEAR_TASK — старт с корня.
 * 3. forceStopPackage — для шагов с forceStopBeforeLaunch=true очищает recents-стек MIUI.
 * 4. awaitScreen() — ждём ожидаемый текст экрана, а не «любой контент».
 * 5. dismissDialogs() — гасим first-launch согласия и системные ошибки.
 * 6. Клики accessibility-first (узел → родитель); жест — только fallback через setBlocking(false).
 * 7. Верификация результата + повторная попытка.
 * 8. Для MSA отдельно ждём диалог подтверждения и действие «Отозвать».
 * 9. Гейтинг: пока работает SimpleRunner, событийный автокликер должен молчать.
 */
class SimpleRunner(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "SimpleRunner"

        // ── Тайминги ──
        private const val BASE_STEP_TIMEOUT_MS = 11_000L
        private const val CONFIRM_TIMEOUT_EXTRA_MS = 8_000L
        private const val SCREEN_WAIT_MS = 4_000L
        private const val UI_SETTLE_DELAY_MS = 700L
        private const val APP_LAUNCH_DELAY_MS = 2_000L
        private const val POPUP_MENU_DELAY_MS = 1_200L
        private const val HOME_RESET_DELAY_MS = 500L
        private const val FORCE_STOP_DELAY_MS = 500L
        private const val SCROLL_SETTLE_DELAY_MS = 350L
        private const val TAP_DURATION_MS = 100L

        // ── Глубины и лимиты ──
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_SCROLL_ATTEMPTS = 4
        private const val SWITCH_FALLBACK_SCROLLS = 3
        private const val TEXT_DEPTH = 7
        private const val NEARBY_ANCESTORS = 3
        private const val SWITCH_ANCESTOR_DEPTH = 6

        private val TOP_RIGHT_Y_DP = listOf(56, 76, 96)

        private val OVERFLOW_TEXTS = listOf(
            "Ещё",
            "More options",
            "Дополнительно",
            "Другие параметры",
            "⋮"
        )

        /**
         * Глобальный флаг для гейтинга:
         * пока выполняется шаг, событийный автокликер в AdbEnablerService должен пропускать действия.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        // Согласия первого запуска — принимаем, чтобы попасть в приложение.
        private val ACCEPT_TEXTS = listOf(
            "Согласиться",
            "Принять",
            "Разрешить",
            "Начать",
            "Продолжить",
            "Включить",
            "OK",
            "ОК",
            "Да",
            "Allow",
            "Agree",
            "Start",
            "Accept",
            "Continue"
        )

        // Маркеры системных ошибок и случайных окон.
        private val ERROR_MARKERS = listOf(
            "Невозможно подключиться",
            "Не удается подключиться",
            "Не удалось подключиться",
            "Отсканируйте QR",
            "Facebook",
            "Проверьте подключение",
            "нет подключения",
            "Ошибка соединения",
            "Не удалось загрузить",
            "Can't connect",
            "Cannot connect",
            "Unable to connect",
            "No connection"
        )

        private val ERROR_DISMISS = listOf(
            "ОТМЕНА",
            "Отмена",
            "Закрыть",
            "Назад",
            "ОК",
            "OK",
            "Cancel",
            "Close"
        )

        // Маркеры настроек — чтобы не нажать согласие прямо внутри них.
        private val SETTINGS_SCREEN_FALLBACK = listOf(
            "Настройки",
            "Settings",
            "Приложения",
            "Apps",
            "Конфиденциальность",
            "Privacy",
            "Отпечатки",
            "Fingerprints",
            "Пароли",
            "Passwords",
            "Уведомления",
            "Notifications",
            "Блокировка экрана",
            "Lock screen"
        )

        private val SWITCH_VIEW_IDS = listOf(
            "com.android.settings:id/switch_widget",
            "android:id/switch_widget",
            "com.miui.securitycenter:id/switch_widget",
            "com.miui.settings:id/switch_widget"
        )
    }

    @Volatile
    private var cancelled: Boolean = false

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

        // База 11 секунд, но продлеваем на предзагрузку и долгие диалоги подтверждения (MSA).
        val effectiveTimeoutMs: Long = BASE_STEP_TIMEOUT_MS +
                step.preDrillWaitMs +
                if (step.confirmWaitMs > 0L) step.confirmWaitMs + CONFIRM_TIMEOUT_EXTRA_MS else 0L

        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${effectiveTimeoutMs}ms)")

        return try {
            val result: Result? = withTimeoutOrNull(effectiveTimeoutMs) {
                runInternal(step)
            }

            if (result == null) {
                AppLog.e(TAG, "Step ${step.id}: TIMEOUT after ${effectiveTimeoutMs}ms")
                Result(false, "timeout")
            } else {
                result
            }
        } finally {
            isRunning = false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Основной пайплайн выполнения шага
    // ═══════════════════════════════════════════════════════════════

    private suspend fun runInternal(step: SimpleSteps.Step): Result {
        // Пропуск, если нужное приложение не установлено.
        if (step.requiredPackages.isNotEmpty() &&
            step.requiredPackages.none { isPackageInstalled(it) }
        ) {
            AppLog.i(TAG, "Step ${step.id}: app not installed — skipping")
            return Result(false, "app_not_installed", skipped = true)
        }

        // 1. Сброс стека активити MIUI.
        resetToHome()

        val packageName: String? = step.launchPackage
        val needForceStop: Boolean = packageName != null && step.forceStopBeforeLaunch

        // 2. Для шагов-приложений с forceStopBeforeLaunch=true —
        //    очищаем recents-стек MIUI, иначе приложение откроется на старом экране.
        if (packageName != null && needForceStop) {
            forceStopPackage(packageName)
        }

        // 3. Запуск приложения или открытие целевого экрана.
        var appLaunched: Boolean = false

        if (packageName != null) {
            OverlayController.updateStatus(service, "Открываем приложение…")
            appLaunched = launchApp(packageName, clearTask = needForceStop)

            if (appLaunched) {
                delay(APP_LAUNCH_DELAY_MS)

                if (step.swipeUpAfterLaunch) {
                    swipeUp()
                }

                delay(UI_SETTLE_DELAY_MS)
                // После первого запуска безопасно гасим согласия и ошибки.
                dismissDialogs(allowAccept = true)
                awaitScreen(launchScreenMarkers(step))
            }
        }

        if (!appLaunched) {
            OverlayController.updateStatus(service, "Открываем нужный экран…")

            if (!openTargetScreen(step) && service.rootInActiveWindow == null) {
                AppLog.w(TAG, "Step ${step.id}: no screen opened")
                return Result(false, "no_screen_opened")
            }

            delay(UI_SETTLE_DELAY_MS)
            dismissDialogs(allowAccept = false)
            awaitScreen(settingsScreenMarkers(step))
        }

        // 4. Диагностика: где реально оказались.
        val startRoot: AccessibilityNodeInfo? = service.rootInActiveWindow
        if (startRoot != null) {
            AppLog.i(
                TAG,
                "Step ${step.id}: start screen=[${collectAllText(startRoot).take(150)}]"
            )
            recycleNode(startRoot)
        }

        if (cancelled) return Result(false, "cancelled")

        // 5. Гасим случайные диалоги перед специальными маршрутами.
        dismissDialogs(allowAccept = false)

        // 6. Спец-маршрут Проводника: очистить данные → отменить приветствие.
        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE) {
            return clearDataAndDecline(step)
        }

        // 7. Предварительная пауза для медленных экранов (например, скан Очистки).
        if (step.preDrillWaitMs > 0L) {
            OverlayController.updateStatus(service, "Ждём загрузку экрана…")
            delay(step.preDrillWaitMs)
        }

        // 8. Drill-down по drillPath.
        drillDown(step)
        if (cancelled) return Result(false, "cancelled")

        // 9. Ещё раз гасим всплывающие окна после drill-down.
        dismissDialogs(allowAccept = false)

        // 10. Переключение тумблера + верификация.
        OverlayController.updateStatus(service, "Ищем нужный переключатель…")
        var result: Result = findAndToggleSwitch(step, attempt = 1)

        // 11. Повторная попытка для любого неуспешного шага (кроме skip по неустановленному приложению).
        if (!result.success && !cancelled && result.reason != "app_not_installed") {
            AppLog.w(TAG, "Step ${step.id}: retry after reason=${result.reason}")
            OverlayController.updateStatus(service, "Повторная попытка…")
            delay(1_000L)
            dismissDialogs(allowAccept = true)
            result = findAndToggleSwitch(step, attempt = 2)
        }

        // 12. После неудачи возвращаемся на HOME, чтобы не оставлять мусорный экран.
        if (!result.success && !cancelled) {
            AppLog.i(TAG, "Step ${step.id}: returning to HOME after failure")
            resetToHome()
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // Навигация
    // ═══════════════════════════════════════════════════════════════

    /** Сброс на HOME перед каждым шагом — критично для MIUI. */
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

    /**
     * Принудительная остановка пакета.
     * Используем безопасный reflection-вариант, потому что прямой вызов
     * может быть недоступен из публичного SDK.
     */
    private suspend fun forceStopPackage(pkg: String) {
        try {
            val activityManager: ActivityManager =
                service.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return

            try {
                activityManager.killBackgroundProcesses(pkg)
            } catch (_: Throwable) {
                // Не критично: это только дополнительная очистка.
            }

            try {
                activityManager.javaClass
                    .getMethod("forceStopPackage", String::class.java)
                    .invoke(activityManager, pkg)
            } catch (_: Throwable) {
                // Если прав недостаточно, молча пропускаем.
            }

            AppLog.i(TAG, "forceStop best effort: $pkg")
            delay(FORCE_STOP_DELAY_MS)
        } catch (e: Exception) {
            AppLog.w(TAG, "forceStop failed for $pkg: ${e.message}")
        }
    }

    /** Запуск приложения по packageName. */
    private fun launchApp(packageName: String, clearTask: Boolean = false): Boolean {
        val launchIntent: Intent = try {
            service.packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            AppLog.w(TAG, "getLaunchIntentForPackage failed: ${e.message}")
            null
        } ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (clearTask) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            service.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "launchApp failed for $packageName: ${e.message}")
            false
        }
    }

    /**
     * Открытие целевого экрана по списку интентов.
     * NEW_TASK + CLEAR_TASK — открываем корневой экран, а не старый из recents.
     */
    private suspend fun openTargetScreen(step: SimpleSteps.Step): Boolean {
        for (intent in step.intents) {
            if (cancelled) return false

            try {
                val resolved = intent.resolveActivity(service.packageManager)
                if (resolved == null &&
                    intent.action != null &&
                    !intent.action!!.startsWith("android.settings")
                ) {
                    continue
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                service.startActivity(intent)
                delay(UI_SETTLE_DELAY_MS)
                dismissDialogs(allowAccept = false)

                val root: AccessibilityNodeInfo? = service.rootInActiveWindow
                if (root != null) {
                    recycleNode(root)
                    return true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "openTargetScreen intent failed: ${e.message}")
            }
        }

        val root: AccessibilityNodeInfo? = service.rootInActiveWindow
        if (root != null) {
            recycleNode(root)
            return true
        }

        return false
    }

    /**
     * Ждём, пока на экране появится один из ожидаемых текстов.
     * Если маркеров нет — ждём любой загруженный контент.
     */
    private suspend fun awaitScreen(markers: List<String>): Boolean {
        val filtered: List<String> = markers
            .filterNot { it.isBlank() || isIconOnly(it) }
            .distinct()

        if (filtered.isEmpty()) {
            val root: AccessibilityNodeInfo? = waitForContentRoot()
            if (root != null) {
                recycleNode(root)
                return true
            }
            return false
        }

        val start: Long = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < SCREEN_WAIT_MS) {
            if (cancelled) return false

            dismissDialogs(allowAccept = true)

            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                val text: String = collectAllText(root)
                val matched: String? = filtered.firstOrNull { marker ->
                    text.contains(marker, ignoreCase = true)
                }

                recycleNode(root)

                if (matched != null) {
                    AppLog.i(TAG, "awaitScreen: matched '$matched'")
                    return true
                }
            }

            delay(300L)
        }

        AppLog.w(TAG, "awaitScreen: timeout, markers=${filtered.take(2)}")
        return false
    }

    /** Ждём, пока контент экрана загрузится (текст > 30 символов). */
    private suspend fun waitForContentRoot(): AccessibilityNodeInfo? {
        val start: Long = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < SCREEN_WAIT_MS) {
            if (cancelled) return null

            val root: AccessibilityNodeInfo? = service.rootInActiveWindow
            if (root != null) {
                if (collectAllText(root).length > 30) {
                    return root
                }
                recycleNode(root)
            }

            delay(300L)
        }

        return service.rootInActiveWindow
    }

    private fun launchScreenMarkers(step: SimpleSteps.Step): List<String> {
        val firstLevel: List<String> = step.drillPath.firstOrNull().orEmpty()
        return (firstLevel + step.searchTexts)
            .filterNot { it.isBlank() }
            .distinct()
    }

    private fun settingsScreenMarkers(step: SimpleSteps.Step): List<String> {
        val firstLevel: List<String> = step.drillPath.firstOrNull().orEmpty()
        return (firstLevel + SETTINGS_SCREEN_FALLBACK)
            .filterNot { it.isBlank() }
            .distinct()
    }

    private fun nextScreenMarkers(step: SimpleSteps.Step, index: Int): List<String> {
        val nextLevel: List<String> = step.drillPath.getOrNull(index + 1)
            ?: step.searchTexts

        return nextLevel
            .filterNot { it.isBlank() }
            .distinct()
    }

    private fun isIconOnly(value: String): Boolean {
        val trimmed: String = value.trim()
        return trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }
    }

    // ═══════════════════════════════════════════════════════════════
    // Диалоги
    // ═══════════════════════════════════════════════════════════════

    /**
     * Гасим согласия первого запуска и системные ошибки.
     *
     * allowAccept = true — после запуска приложений, где могут быть экраны первого старта.
     * allowAccept = false — в настройках, где «Согласиться» может увести не туда.
     */
    private fun dismissDialogs(allowAccept: Boolean) {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return
        val text: String = collectAllText(root)
        recycleNode(root)

        // Системные ошибки.
        val hasError: Boolean = ERROR_MARKERS.any { marker ->
            text.contains(marker, ignoreCase = true)
        }

        if (hasError) {
            AppLog.i(TAG, "dismissDialogs: error detected, trying to close")

            val closeBtn: AccessibilityNodeInfo? = searchAllWindows(ERROR_DISMISS)
            if (closeBtn != null) {
                tapNodeSync(closeBtn)
                recycleNode(closeBtn)
                return
            }

            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        // Согласия первого запуска.
        val hasAccept: Boolean = ACCEPT_TEXTS.any { acceptText ->
            text.contains(acceptText, ignoreCase = true)
        }

        val hasSettingsContent: Boolean = text.length > 100 &&
                SETTINGS_SCREEN_FALLBACK.any { settingsMarker ->
                    text.contains(settingsMarker, ignoreCase = true)
                }

        if (hasAccept && !hasSettingsContent && allowAccept) {
            AppLog.i(TAG, "dismissDialogs: accepting first-launch consent")

            val acceptBtn: AccessibilityNodeInfo? = searchAllWindows(ACCEPT_TEXTS)
            if (acceptBtn != null) {
                tapNodeSync(acceptBtn)
                recycleNode(acceptBtn)
            }
        }
    }

    /** Синхронный тап для диалогов: только Accessibility, без жестов. */
    private fun tapNodeSync(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0

        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }

        return false
    }

    private fun performGlobalAction(action: Int) {
        try {
            service.performGlobalAction(action)
        } catch (e: Exception) {
            AppLog.w(TAG, "performGlobalAction($action) failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Спец-маршрут Проводника
    // ═══════════════════════════════════════════════════════════════

    private suspend fun clearDataAndDecline(step: SimpleSteps.Step): Result {
        OverlayController.updateStatus(service, "Ищем кнопку очистки…")

        val root1: AccessibilityNodeInfo = waitForContentRoot()
            ?: return Result(false, "no_root")

        val clearBtn: AccessibilityNodeInfo? = findClickableByTextWithScroll(
            root1,
            listOf(
                "Очистить данные",
                "Clear data",
                "Очистить хранилище",
                "Clear storage",
                "Очистить все",
                "Clear all",
                "Очистить",
                "Clear"
            )
        ) ?: searchAllWindows(listOf("Очистить данные", "Clear data"))

        recycleNode(root1)

        if (clearBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: clear button not found")
            return Result(false, "clear_button_not_found")
        }

        tapNode(clearBtn)
        recycleNode(clearBtn)
        delay(800L)
        dismissDialogs(allowAccept = false)

        val root2: AccessibilityNodeInfo = waitForContentRoot()
            ?: return Result(false, "no_root")

        val allBtn: AccessibilityNodeInfo? = findClickableByText(
            root2,
            listOf(
                "Очистить все",
                "Clear all",
                "Очистить все данные",
                "Очистить данные",
                "Clear data"
            )
        ) ?: searchAllWindows(listOf("Очистить все", "Clear all"))

        recycleNode(root2)

        if (allBtn == null) {
            AppLog.w(TAG, "Step ${step.id}: 'Очистить все' not found")
            return Result(false, "clear_all_not_found")
        }

        tapNode(allBtn)
        recycleNode(allBtn)
        delay(1_200L)
        dismissDialogs(allowAccept = false)

        step.launchPackage?.let { pkg ->
            OverlayController.updateStatus(service, "Проверяем первый запуск…")
            launchApp(pkg, clearTask = true)
            delay(2_500L)
        }

        val root3: AccessibilityNodeInfo = service.rootInActiveWindow
            ?: return Result(true, "cleared_no_welcome")

        val cancelBtn: AccessibilityNodeInfo? =
            findClickableByText(root3, listOf("Отмена", "Cancel"))
                ?: searchAllWindows(listOf("Отмена", "Cancel"))

        recycleNode(root3)

        if (cancelBtn != null) {
            tapNode(cancelBtn)
            recycleNode(cancelBtn)
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

            dismissDialogs(allowAccept = index > 0)

            val root: AccessibilityNodeInfo? = waitForContentRoot()
            var node: AccessibilityNodeInfo? =
                if (root != null) findClickableByTextWithScroll(root, level) else null

            if (node == null) {
                node = searchAllWindows(level)
            }

            if (node != null) {
                AppLog.i(TAG, "Step ${step.id}: drilling into '${level.firstOrNull()}'")
                tapNode(node)
                recycleNode(node)
            } else if (level.any { it.contains("⋮") || it.contains("⚙") || it.contains("⚙️") }) {
                AppLog.i(
                    TAG,
                    "Step ${step.id}: '${level.firstOrNull()}' not found — tapping top-right"
                )
                tapTopRight(nextScreenMarkers(step, index))
                delay(POPUP_MENU_DELAY_MS)
            } else {
                AppLog.w(
                    TAG,
                    "Step ${step.id}: level '${level.firstOrNull()}' not found — assuming inside"
                )
            }

            if (root != null) {
                recycleNode(root)
            }

            awaitScreen(nextScreenMarkers(step, index))
            dismissDialogs(allowAccept = true)
            delay(UI_SETTLE_DELAY_MS)
        }
    }

    /** Тап по кнопке меню в правом верхнем углу: сначала Accessibility, затем жест. */
    private suspend fun tapTopRight(expected: List<String>): Boolean {
        val root: AccessibilityNodeInfo? = service.rootInActiveWindow

        if (root != null) {
            val overflow: AccessibilityNodeInfo? = findClickableByText(root, OVERFLOW_TEXTS)

            if (overflow != null) {
                val clicked: Boolean = tapNode(overflow)
                recycleNode(overflow)
                recycleNode(root)

                if (clicked) {
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
            val x: Float = dm.widthPixels - dp(24).toFloat()

            for (yDp in TOP_RIGHT_Y_DP) {
                tapAt(x, dp(yDp).toFloat())
                delay(500L)

                if (expected.isNotEmpty()) {
                    val found: AccessibilityNodeInfo? = searchAllWindows(expected)
                    if (found != null) {
                        recycleNode(found)
                        AppLog.i(TAG, "tapTopRight: menu opened at y=${yDp}dp")
                        return@withOverlayPassThrough
                    }
                }
            }
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // Клики: Accessibility → родитель → жест
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

        AppLog.i(TAG, "tapNode: falling back to gesture through overlay")

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
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
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
            // Оверлей может быть ещё не готов; не критично.
        }

        delay(250L)
        block()
        delay(400L)

        try {
            OverlayController.setBlocking(service, true)
        } catch (_: Exception) {
            // Возврат блокировки мог не сработать из-за скрытия оверлея.
        }
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()

            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
        }

    private suspend fun swipeUp() {
        val dm = service.resources.displayMetrics
        val x: Float = dm.widthPixels / 2f

        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.75f)
            lineTo(x, dm.heightPixels * 0.30f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        suspendCancellableCoroutine<Unit> { continuation ->
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                },
                null
            )
        }

        delay(UI_SETTLE_DELAY_MS)
    }

    // ═══════════════════════════════════════════════════════════════
    // Дополнительные связанные переключатели
    // ═══════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private suspend fun toggleAdditional(texts: List<String>) {
        for (text in texts) {
            if (cancelled) return

            delay(400L)

            val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: break
            val switchNode: AccessibilityNodeInfo? = findSwitchByText(root, listOf(text))

            if (switchNode != null) {
                if (switchNode.isChecked) {
                    AppLog.i(TAG, "Toggling additional: $text")
                    tapNode(switchNode)
                    delay(600L)
                    dismissDialogs(allowAccept = false)
                }
                recycleNode(switchNode)
            } else {
                AppLog.w(TAG, "Additional toggle not found: $text")
            }

            recycleNode(root)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Переключение основного тумблера
    // ═══════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step, attempt: Int): Result {
        if (cancelled) return Result(false, "cancelled")

        val root: AccessibilityNodeInfo = waitForContentRoot()
            ?: return Result(false, "no_root_window")

        var switchNode: AccessibilityNodeInfo? = findSwitchByText(root, step.searchTexts)

        if (switchNode == null && step.searchTexts.isNotEmpty()) {
            repeat(SWITCH_FALLBACK_SCROLLS) {
                if (cancelled) return Result(false, "cancelled")

                val scrollRoot: AccessibilityNodeInfo = service.rootInActiveWindow
                    ?: return Result(false, "no_root_window")

                val scrollable: AccessibilityNodeInfo? = findScrollableContainer(scrollRoot)
                if (scrollable == null) {
                    recycleNode(scrollRoot)
                    return@repeat
                }

                val scrolled: Boolean =
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

                recycleNode(scrollable)
                recycleNode(scrollRoot)

                if (!scrolled) return@repeat

                delay(400L)

                val newRoot: AccessibilityNodeInfo = service.rootInActiveWindow
                    ?: return Result(false, "no_root_window")

                val found: AccessibilityNodeInfo? = findSwitchByText(newRoot, step.searchTexts)

                if (found != null) {
                    switchNode = found
                    return@repeat
                }

                recycleNode(newRoot)
            }

            if (switchNode == null) {
                switchNode = findSwitchInAllWindows(step.searchTexts)
            }
        }

        if (switchNode == null) {
            val screenText: String = collectAllText(root).take(600)
            AppLog.w(TAG, "Step ${step.id}: NO SWITCH FOUND. screen=[$screenText]")
            recycleNode(root)
            return Result(false, "switch_not_found")
        }

        val isChecked: Boolean = switchNode.isChecked

        AppLog.i(
            TAG,
            "Step ${step.id}: switch found, isChecked=$isChecked, " +
                    "target=${step.targetChecked}, attempt=$attempt"
        )

        if (isChecked == step.targetChecked) {
            if (step.additionalToggles.isNotEmpty()) {
                toggleAdditional(step.additionalToggles)
            }

            recycleNode(switchNode)
            recycleNode(root)
            return Result(true, "already_done")
        }

        OverlayController.updateStatus(service, "Переключаем элемент…")

        val clicked: Boolean = tapNode(switchNode)
        AppLog.i(TAG, "Step ${step.id}: tapNode result=$clicked")
        recycleNode(switchNode)

        if (!clicked) {
            recycleNode(root)
            return Result(false, "click_failed")
        }

        delay(UI_SETTLE_DELAY_MS)

        // Диалог подтверждения: сначала ждём появление, затем кликаем.
        var confirmationClicked: Boolean = false
        if (step.confirmTexts.isNotEmpty()) {
            confirmationClicked = handleConfirmation(step)

            if (!confirmationClicked) {
                AppLog.w(TAG, "Step ${step.id}: confirmation dialog not completed")
            }
        }

        // Верификация результата.
        val newRoot: AccessibilityNodeInfo? = waitForContentRoot()

        if (newRoot == null) {
            recycleNode(root)
            return Result(
                success = confirmationClicked,
                reason = if (confirmationClicked) "confirm_no_verify" else "toggled_no_verify"
            )
        }

        val newSwitch: AccessibilityNodeInfo? = findSwitchByText(newRoot, step.searchTexts)
        val newChecked: Boolean = newSwitch?.isChecked
            ?: if (confirmationClicked) step.targetChecked else !isChecked

        AppLog.i(TAG, "Step ${step.id}: verify newChecked=$newChecked")

        recycleNode(newSwitch)
        recycleNode(newRoot)
        recycleNode(root)

        if (newChecked == step.targetChecked) {
            if (step.additionalToggles.isNotEmpty()) {
                toggleAdditional(step.additionalToggles)
            }

            return Result(true, if (confirmationClicked) "confirmed" else "toggled")
        }

        return Result(false, if (confirmationClicked) "confirm_failed" else "toggle_failed")
    }

    /**
     * Ожидаем диалог подтверждения и нажимаем целевую кнопку.
     * Используется для шагов с длинным таймером, например для MSA.
     */
    private suspend fun handleConfirmation(step: SimpleSteps.Step): Boolean {
        OverlayController.updateStatus(service, "Ждём диалог подтверждения…")

        val start: Long = System.currentTimeMillis()
        val appearanceTimeoutMs: Long = step.confirmWaitMs + CONFIRM_TIMEOUT_EXTRA_MS
        var appeared: Boolean = false

        while (System.currentTimeMillis() - start < appearanceTimeoutMs) {
            if (cancelled) return false

            val node: AccessibilityNodeInfo? = findAnyNodeInAllWindows(step.confirmTexts)

            if (node != null) {
                appeared = true
                recycleNode(node)
                break
            }

            delay(400L)
        }

        if (!appeared) {
            AppLog.w(TAG, "Step ${step.id}: confirmation text did not appear")
            return false
        }

        val elapsed: Long = System.currentTimeMillis() - start
        val remaining: Long = (step.confirmWaitMs - elapsed).coerceAtLeast(800L)

        OverlayController.updateStatus(service, "Ожидаем доступность действия…")
        delay(remaining)

        val button: AccessibilityNodeInfo? = searchAllWindows(step.confirmTexts)
            ?: findAnyNodeInAllWindows(step.confirmTexts)

        if (button == null) {
            AppLog.w(TAG, "Step ${step.id}: confirmation button not found")
            return false
        }

        val tapped: Boolean = tapNode(button)
        recycleNode(button)

        if (!tapped) {
            AppLog.w(TAG, "Step ${step.id}: confirmation button click failed")
            return false
        }

        delay(UI_SETTLE_DELAY_MS)
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // Поиск узлов
    // ═══════════════════════════════════════════════════════════════

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            service.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun searchAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (window in service.windows) {
                val root: AccessibilityNodeInfo = window.root ?: continue
                val found: AccessibilityNodeInfo? = findClickableByText(root, texts)

                if (found != null) {
                    return found
                }
            }
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "searchAllWindows failed: ${e.message}")
            null
        }
    }

    private fun findSwitchInAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (window in service.windows) {
                val root: AccessibilityNodeInfo = window.root ?: continue
                val found: AccessibilityNodeInfo? = findSwitchByText(root, texts)

                if (found != null) {
                    return found
                }
            }
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "findSwitchInAllWindows failed: ${e.message}")
            null
        }
    }

    private fun findAnyNodeInAllWindows(texts: List<String>): AccessibilityNodeInfo? {
        return try {
            for (window in service.windows) {
                val root: AccessibilityNodeInfo = window.root ?: continue
                val found: AccessibilityNodeInfo? = findAnyNodeByText(root, texts)

                if (found != null) {
                    return found
                }
            }
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "findAnyNodeInAllWindows failed: ${e.message}")
            null
        }
    }

    private fun findAnyNodeByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        for (text in texts) {
            val nodes: List<AccessibilityNodeInfo> =
                root.findAccessibilityNodeInfosByText(text) ?: continue

            val first: AccessibilityNodeInfo? = nodes.firstOrNull()
            if (first != null) {
                nodes.forEachIndexed { index, node ->
                    if (index != 0) recycleNode(node)
                }
                return first
            }
        }

        return null
    }

    private suspend fun findClickableByTextWithScroll(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        findClickableByText(root, texts)?.let { return it }

        repeat(MAX_SCROLL_ATTEMPTS) {
            if (cancelled) return null

            val currentRoot: AccessibilityNodeInfo = service.rootInActiveWindow
                ?: return null

            val scrollable: AccessibilityNodeInfo? = findScrollableContainer(currentRoot)
            recycleNode(currentRoot)

            if (scrollable == null) return null

            val scrolled: Boolean =
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            recycleNode(scrollable)

            if (!scrolled) return null

            delay(SCROLL_SETTLE_DELAY_MS)

            val newRoot: AccessibilityNodeInfo = service.rootInActiveWindow
                ?: return null

            val found: AccessibilityNodeInfo? = findClickableByText(newRoot, texts)

            if (found != null) {
                return found
            }

            recycleNode(newRoot)
        }

        return null
    }

    private fun findScrollableContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        if (root.isScrollable) return root

        for (i in 0 until root.childCount) {
            val child: AccessibilityNodeInfo = root.getChild(i) ?: continue
            val found: AccessibilityNodeInfo? = findScrollableContainer(child)

            if (found != null) {
                if (found !== child) {
                    recycleNode(child)
                }
                return found
            }

            recycleNode(child)
        }

        return null
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null

        for (text in texts) {
            val nodes: List<AccessibilityNodeInfo> =
                root.findAccessibilityNodeInfosByText(text) ?: continue

            nodes.firstOrNull { it.isClickable }?.let { return it }

            for (node in nodes) {
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

        for (switch in switches) {
            if (findNearbyText(switch, texts) != null) {
                return switch
            }
        }

        for (id in SWITCH_VIEW_IDS) {
            val nodesById: List<AccessibilityNodeInfo> =
                root.findAccessibilityNodeInfosByViewId(id) ?: continue

            for (node in nodesById) {
                if (findNearbyText(node, texts) != null) {
                    return node
                }
            }
        }

        for (text in texts) {
            val nodes: List<AccessibilityNodeInfo> =
                root.findAccessibilityNodeInfosByText(text) ?: continue

            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                var depth = 0

                while (current != null && depth < SWITCH_ANCESTOR_DEPTH) {
                    val className: String = current.className?.toString() ?: ""

                    if (className.contains("Switch") ||
                        className.contains("CheckBox") ||
                        className.contains("Toggle") ||
                        current.isCheckable
                    ) {
                        return current
                    }

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
        val className: String = node.className?.toString() ?: ""

        if (className.contains("Switch") ||
            className.contains("CheckBox") ||
            className.contains("Toggle") ||
            node.isCheckable
        ) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectSwitchLike(child, result)
            }
        }
    }

    private fun findNearbyText(
        node: AccessibilityNodeInfo,
        texts: List<String>
    ): String? {
        var ancestor: AccessibilityNodeInfo? = node.parent

        repeat(NEARBY_ANCESTORS) {
            val current: AccessibilityNodeInfo = ancestor ?: return null
            val collected: String = collectAllText(current)

            for (text in texts) {
                if (collected.contains(text, ignoreCase = true)) {
                    return text
                }
            }

            ancestor = current.parent
        }

        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        val builder = StringBuilder()
        collectTextRecursive(node, builder, 0)
        return builder.toString()
    }

    private fun collectTextRecursive(
        node: AccessibilityNodeInfo?,
        builder: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > TEXT_DEPTH) return

        node.text?.let { builder.append(it).append(' ') }
        node.contentDescription?.let { builder.append(it).append(' ') }

        for (i in 0 until node.childCount) {
            collectTextRecursive(node.getChild(i), builder, depth + 1)
        }
    }

    private fun dp(value: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                @Suppress("DEPRECATION")
                node?.recycle()
            } catch (_: Exception) {
                // Игнорируем: узел мог быть освобождён ранее.
            }
        }
    }
}