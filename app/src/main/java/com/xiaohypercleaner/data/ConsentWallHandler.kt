package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher

/**
 * Единая точка входа для системных диалогов вместо старого хардкода
 * (`interceptSystemDialogs`): welcome-стены и runtime-permission запросы.
 *
 * Правила (Аддендум B):
 * - welcome-маркеры → тап согласия → ПРОДОЛЖИТЬ шаг (≤ maxIterationsPerStep);
 * - permission-запрос → deny по умолчанию (`consentPolicy`), allow только
 *   для `allowOverrides`/медиа-шагов;
 * - лог: `consent: kind=<welcome|permission> decision=<...> step=<id> text='...'`.
 */
object ConsentWallHandler {

    private const val TAG = "consent"

    /** Медиа-шаги: аудио-разрешение исторически гранилось (поведение не меняем). */
    private val MEDIA_STEPS = setOf("music_sys", "mivideo")

    data class Outcome(val handled: Boolean, val kind: String, val decision: String)

    /** Мост нажатий: реализует SimpleRunner (поиск кликабельного узла по текстам). */
    interface TapBridge {
        suspend fun tapByTexts(texts: List<String>): Boolean
    }

    /**
     * Обрабатывает диалог, если он на экране. Вызывается в начале шага
     * и после каждого навигационного действия.
     */
    suspend fun handleOnce(
        service: AccessibilityService,
        bridge: TapBridge,
        stepId: String,
        stepConsentTexts: List<String> = emptyList(),
        isCancelled: () -> Boolean = { false }
    ): Outcome {
        if (isCancelled()) return Outcome(false, "none", "cancelled")
        val root = service.rootInActiveWindow ?: return Outcome(false, "none", "no_window")
        val screenText = NodeTree.collectText(root)
        recycle(root)

        val welcomeMarkers = SemanticCatalog.welcomeMarkers()
        val permissionMarkers = SemanticCatalog.permissionMarkers()

        if (welcomeMarkers.isNotEmpty() && welcomeMarkers.any { TextMatcher.normalizedContains(screenText, it) }) {
            val actions = (stepConsentTexts + SemanticCatalog.welcomeActions()).distinct()
            val tapped = actions.isNotEmpty() && bridge.tapByTexts(actions)
            log(stepId, "welcome", if (tapped) "accepted" else "no_action", screenText)
            return Outcome(tapped, "welcome", if (tapped) "accept" else "not_found")
        }

        if (permissionMarkers.isNotEmpty() && permissionMarkers.any { TextMatcher.normalizedContains(screenText, it) }) {
            val allow = SemanticCatalog.shouldAllow(stepId) || stepId in MEDIA_STEPS
            val texts = if (allow) SemanticCatalog.allowTexts() else SemanticCatalog.denyTexts()
            val decision = if (allow) "allow" else "deny"
            val tapped = texts.isNotEmpty() && bridge.tapByTexts(texts)
            log(stepId, "permission", if (tapped) decision else "$decision:not_found", screenText)
            return Outcome(tapped, "permission", decision)
        }

        return Outcome(false, "none", "no_dialog")
    }

    /**
     * Обрабатывает диалоги до `maxIterations` раз (каждая итерация — один экран).
     * Возвращает количество обработанных диалогов.
     */
    suspend fun handleUntilSettled(
        service: AccessibilityService,
        bridge: TapBridge,
        stepId: String,
        stepConsentTexts: List<String> = emptyList(),
        maxIterations: Int = SemanticCatalog.maxConsentIterationsPolicy(),
        isCancelled: () -> Boolean = { false }
    ): Int {
        var handled = 0
        val limit = maxIterations.coerceAtLeast(1)
        while (handled < limit) {
            if (isCancelled()) break
            val outcome = handleOnce(service, bridge, stepId, stepConsentTexts, isCancelled)
            if (!outcome.handled) break
            handled++
        }
        return handled
    }

    private fun log(stepId: String, kind: String, decision: String, screenText: String) {
        AppLog.i(
            TAG,
            "consent: kind=$kind decision=$decision step=$stepId text='${screenText.take(80)}'"
        )
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}