package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import com.xiaohypercleaner.util.AppLog

/**
 * Оркестрация discovery-фазы шага: скан экранов целевого пакета, порядок
 * навигационных интентов и проверка целевого экрана по маркерам.
 *
 * Роль в коммите discovery: захардкоженные вариантные маршруты остаются
 * подсказками-фолбэками, найденные сканером интенты добавляются ПОСЛЕ них —
 * поведение cn_hyperos не меняется, а промах вариантного пути лечится скан-фолбэком.
 */
object ScanOrchestrator {

    private const val TAG = "discover"

    /** План навигации шага: вариантные и найденные сканером интенты. */
    data class NavigationPlan(
        val legacyIntents: List<Intent>,
        val discoveredIntents: List<Intent>,
        val candidates: List<ActivityScanner.ActivityCandidate>,
        val fromCache: Boolean
    ) {
        /** Порядок попыток: вариантные подсказки, затем скан; дубликаты компонентов убраны. */
        fun orderedIntents(): List<Intent> {
            val seen = HashSet<String>()
            val result = ArrayList<Intent>(legacyIntents.size + discoveredIntents.size)
            for (intent in legacyIntents + discoveredIntents) {
                val key = "${intent.component?.flattenToShortString() ?: intent.action ?: ""}"
                if (seen.add(key)) result.add(intent)
            }
            return result
        }
    }

    /**
     * Готовит навигационный план шага: вариантные интенты + скан активностей пакета.
     * Скан выполняется только для шагов-приложений (есть целевой пакет).
     */
    suspend fun planNavigation(
        context: Context,
        pkg: String?,
        legacyIntents: List<Intent>,
        keywords: List<String>,
        cache: ActivityCacheStore? = null,
        incremental: String? = null
    ): NavigationPlan {
        pkg ?: return NavigationPlan(legacyIntents, emptyList(), emptyList(), false)
        val scan = ActivityScanner.scan(context, pkg, keywords, cache, incremental)
        val discovered = buildIntents(pkg, scan.candidates)
        if (discovered.isNotEmpty()) {
            AppLog.i(
                TAG,
                "plan pkg=$pkg legacy=${legacyIntents.size} discovered=${discovered.size} " +
                    "cache=${scan.fromCache}"
            )
        }
        return NavigationPlan(legacyIntents, discovered, scan.candidates, scan.fromCache)
    }

    /** Явные интенты по найденным активностям (внешние приложения — NEW_TASK обязателен). */
    fun buildIntents(
        pkg: String,
        candidates: List<ActivityScanner.ActivityCandidate>
    ): List<Intent> = candidates.map { c ->
        Intent().setClassName(pkg, c.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Ожидание целевого экрана по screen-маркерам (делегат [ComponentVerifier]). */
    suspend fun awaitTargetScreen(
        service: AccessibilityService,
        markers: List<String>,
        timeoutMs: Long = ComponentVerifier.DEFAULT_TIMEOUT_MS,
        minMatches: Int = 1,
        isCancelled: () -> Boolean = { false }
    ): Boolean = ComponentVerifier.awaitScreen(service, markers, timeoutMs, minMatches, isCancelled)

    /** Возврат на домашний экран при потере уверенности (делегат [ComponentVerifier]). */
    suspend fun recoverToHome(service: AccessibilityService): Boolean =
        ComponentVerifier.resetToHome(service)
}