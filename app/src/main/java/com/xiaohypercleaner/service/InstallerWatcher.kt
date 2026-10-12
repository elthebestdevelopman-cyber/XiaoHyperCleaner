package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SwitchFinder
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "watcher"

/** Статус ловца для UI. Молчание, когда делать нечего, — [Idle] не показывается. */
sealed interface InstallerWatcherStatus {
    data object Idle : InstallerWatcherStatus

    /** Открываем экран настроек установщика. */
    data object Running : InstallerWatcherStatus

    /** «Получать рекомендации» выключено в контексте установки. */
    data object RecommendationsOff : InstallerWatcherStatus

    /** Экран настроек не открылся/не подтвердился — пункт пропущен, можно повторить. */
    data object Skipped : InstallerWatcherStatus
}

/**
 * Статус ловца живёт в процессе: сервис доступности и активность — один процесс,
 * поэтому StateFlow виден UI без IPC. Состояние сессии — только в памяти:
 * персистится единственный пользовательский флаг `InstallerWatcherEnabled`.
 */
object InstallerWatcherState {
    private val _status = MutableStateFlow<InstallerWatcherStatus>(InstallerWatcherStatus.Idle)
    val status: StateFlow<InstallerWatcherStatus> = _status.asStateFlow()

    fun set(status: InstallerWatcherStatus) {
        _status.value = status
    }
}

/** Снимок окна установщика: всё, что нужно решению, собрано один раз. */
internal data class InstallerObservation(
    val packageName: String,
    val viewIds: Set<String>,
    val screenText: String,
    /** Экран настроек подтверждён: маркеры каталога совпали или найден целевой тумблер. */
    val settingsScreen: Boolean,
    /** Состояние целевого тумблера; null — тумблера на экране нет. */
    val toggleChecked: Boolean?,
    /** Ключ кулдауна — подпись устанавливаемого приложения (в логи не попадает). */
    val appKey: String?,
    val installerPackages: List<String>
)

/** Решение ловца по снимку окна. */
internal sealed interface InstallerDecision {
    data class Ignore(val reason: String) : InstallerDecision

    /** Открыть экран настроек установщика (тап по шестерёнке). */
    data object OpenSettings : InstallerDecision

    /** Снять целевой тумблер на подтверждённом экране настроек. */
    data object TurnOff : InstallerDecision
}

/**
 * Правила ловца окна установки. Задача одна: пока окно установщика живо, дойти до
 * экрана настроек (`SettingsActivity` недоступна снаружи — только через шестерёнку
 * окна установки) и снять «Получать рекомендации».
 *
 * Инварианты (по дампам fresh/installer_settings_v130, installer_confirm_v130,
 * reinstall_1..4):
 * - действуем только в post-install состоянии (`no_virus_layout`, без `app_scan_layout`):
 *   на экране скана в дереве живая `cancel_button` («ОТМЕНА») — отмена ещё возможна;
 * - `ok_button`/`cancel_button`/тексты подтверждения установки не тапаем никогда:
 *   единственные цели — шестерёнка `btn_settings` и целевой тумблер;
 * - Back не используется: шестерёнка ведёт на экран настроек прямым переходом;
 * - только выключаем: тумблер уже выключен — молчим, в UI ничего не пишем.
 */
internal object InstallerWatcherLogic {

    /** Шаг-владелец семантики: маркеры, подписи и пакеты берутся из него. */
    const val STEP_ID = "installer_recommendations"

    const val REASON_DISABLED = "disabled"
    const val REASON_PHASE_RUNNING = "phase_running"
    const val REASON_FOREIGN_PACKAGE = "foreign_package"
    const val REASON_SCAN_IN_PROGRESS = "scan_in_progress"
    const val REASON_CONFIRM_WINDOW = "install_confirm_window"
    const val REASON_NO_DOOR = "no_settings_door"
    const val REASON_SWITCH_NOT_FOUND = "switch_not_found"
    const val REASON_ALREADY_OFF = "already_off"
    const val REASON_COOLDOWN = "cooldown"

    /** Шестерёнка окна установки — вход на экран настроек. */
    const val ID_SETTINGS_DOOR = "btn_settings"

    /** Признак завершённой установки: отменять уже нечего. */
    const val ID_POST_INSTALL = "no_virus_layout"

    /** Идентификаторы экрана скана: установка ещё идёт, отмена осмысленна. */
    private val SCAN_IDS = setOf("app_scan_layout", "progress_text", "progress_bar")

    /** Идентификаторы диалога подтверждения установки. */
    private val CONFIRM_IDS = setOf(
        "install_confirm_question_update", "alertTitle", "button1", "button2"
    )

    /**
     * Дешёвый гейт (до обхода дерева и до решений по окну): выключенный ловец и
     * идущая фаза. `null` — можно смотреть окно.
     */
    fun gateReason(enabled: Boolean, phaseRunning: Boolean): String? = when {
        !enabled -> REASON_DISABLED
        phaseRunning -> REASON_PHASE_RUNNING
        else -> null
    }

    fun decide(observation: InstallerObservation, coolingDown: Boolean): InstallerDecision = when {
        observation.packageName !in observation.installerPackages ->
            InstallerDecision.Ignore(REASON_FOREIGN_PACKAGE)

        observation.settingsScreen -> when {
            observation.toggleChecked == null ->
                InstallerDecision.Ignore(REASON_SWITCH_NOT_FOUND)

            !observation.toggleChecked -> InstallerDecision.Ignore(REASON_ALREADY_OFF)
            coolingDown -> InstallerDecision.Ignore(REASON_COOLDOWN)
            else -> InstallerDecision.TurnOff
        }

        observation.viewIds.any { it in SCAN_IDS } ->
            InstallerDecision.Ignore(REASON_SCAN_IN_PROGRESS)

        observation.viewIds.any { it in CONFIRM_IDS } ->
            InstallerDecision.Ignore(REASON_CONFIRM_WINDOW)

        ID_SETTINGS_DOOR in observation.viewIds && ID_POST_INSTALL in observation.viewIds ->
            InstallerDecision.OpenSettings

        else -> InstallerDecision.Ignore(REASON_NO_DOOR)
    }
}

/**
 * Кулдаун по устанавливаемому приложению (не глобальный): после сброса пользователь
 * ставит приложения подряд, и глобальный кулдаун подавил бы второе-третье
 * срабатывание. Ключ — подпись приложения из окна установки, только в памяти.
 */
internal class InstallerCooldown(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val done = HashMap<String, Long>()

    fun isCoolingDown(key: String?): Boolean {
        key ?: return false
        val at = done[key] ?: return false
        if (now() - at >= ttlMs) {
            done.remove(key)
            return false
        }
        return true
    }

    fun markDone(key: String?) {
        key ?: return
        done[key] = now()
    }

    fun clear() = done.clear()

    companion object {
        /** Окно установки живёт минуты; час — с запасом и без роста карты. */
        const val DEFAULT_TTL_MS = 60 * 60 * 1000L
    }
}

/**
 * Ловец окна установки (триггер A): реагирует на события окна, пока живо окно
 * установщика, и действует только в post-install состоянии.
 *
 * Гейт фазы — свой: ранний выход [AdbEnablerService.onAccessibilityEvent] покрывает
 * только `SimpleRunner.isRunning`, а окно прогресса/откат фазы идут без шага в раннере.
 * Действие одно на окно: тап по шестерёнке, затем тап по целевому тумблеру.
 */
internal class InstallerWatcher(
    private val service: AccessibilityService,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Пакеты шага — тем же путём, что шаг ([SimpleRunner.candidatePackages]). */
    private val installerPackages: () -> List<String>,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit = { AppLog.i(TAG, it) },
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private val cooldown = InstallerCooldown()

    /** Сессия ловца: одна за раз. */
    private var sessionActive = false

    /** Дедупликация служебных пропусков: окно даёт десятки событий подряд. */
    private var lastSkipSignature: String? = null

    fun onEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val gate = InstallerWatcherLogic.gateReason(
            enabled(),
            OverlayController.phaseRunning || SimpleRunner.isRunning
        )
        if (gate != null) {
            logSkipFast(gate)
            return
        }

        val observation = observe() ?: return
        val decision = InstallerWatcherLogic.decide(
            observation,
            cooldown.isCoolingDown(observation.appKey)
        )
        when (decision) {
            is InstallerDecision.Ignore -> logSkip(observation, decision.reason)

            InstallerDecision.OpenSettings -> {
                if (sessionActive) return
                sessionActive = true
                InstallerWatcherState.set(InstallerWatcherStatus.Running)
                log("watcher open settings pkg=${observation.packageName}")
                scope.launch {
                    try {
                        openSettingsAndTurnOff(observation)
                    } finally {
                        sessionActive = false
                    }
                }
            }

            InstallerDecision.TurnOff -> {
                if (sessionActive) return
                sessionActive = true
                scope.launch {
                    try {
                        turnOff(observation, observation.appKey)
                    } finally {
                        sessionActive = false
                    }
                }
            }
        }
    }

    /** Снимок активного окна. null — окна нет/пакет не определён. */
    private fun observe(): InstallerObservation? {
        val root = service.rootInActiveWindow ?: return null
        try {
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName.isEmpty()) return null
            val screenText = NodeTree.collectText(root)
            val switch = SwitchFinder.findSwitch(root, toggleTexts())
            val markers = SemanticCatalog.screenMarkers(InstallerWatcherLogic.STEP_ID)
            val markerHit = markers.isNotEmpty() &&
                markers.any { TextMatcher.normalizedContains(screenText, it) }
            val checked = switch?.let { SwitchFinder.isChecked(it) }
            recycleNode(switch)
            return InstallerObservation(
                packageName = packageName,
                viewIds = collectViewIds(root),
                screenText = screenText,
                settingsScreen = markerHit || switch != null,
                toggleChecked = checked,
                appKey = appKeyOf(root),
                installerPackages = installerPackages()
            )
        } finally {
            recycleNode(root)
        }
    }
/** Открыть экран настроек шестерёнкой и снять тумблер. Одна сессия, без возврата. */
    private suspend fun openSettingsAndTurnOff(observation: InstallerObservation) {
        if (!clickById(observation, InstallerWatcherLogic.ID_SETTINGS_DOOR)) {
            log("watcher skipped reason=settings_door_not_clickable pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        val settings = awaitWindow(SETTINGS_WAIT_MS) { it.settingsScreen }
        if (settings == null) {
            log("watcher done reason=settings_screen_timeout pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        turnOff(settings, observation.appKey)
    }

    /** Снять целевой тумблер: только в сторону false, с подтверждением состояния. */
    private suspend fun turnOff(observation: InstallerObservation, appKey: String?) {
        if (observation.toggleChecked != true) {
            // Уже выключено — молчим и в UI ничего не пишем.
            log("watcher skipped reason=${InstallerWatcherLogic.REASON_ALREADY_OFF}")
            InstallerWatcherState.set(InstallerWatcherStatus.Idle)
            return
        }
        val root = service.rootInActiveWindow ?: return
        val switch = try {
            SwitchFinder.findSwitch(root, toggleTexts())
        } finally {
            recycleNode(root)
        }
        if (switch == null) {
            log("watcher skipped reason=${InstallerWatcherLogic.REASON_SWITCH_NOT_FOUND}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        val clicked = clickNode(switch)
        recycleNode(switch)
        if (!clicked) {
            log("watcher skipped reason=tap_failed pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        if (awaitWindow(OFF_VERIFY_MS) { it.settingsScreen && it.toggleChecked == false } != null) {
            cooldown.markDone(appKey)
            InstallerWatcherState.set(InstallerWatcherStatus.RecommendationsOff)
            log("watcher done recommendations_off pkg=${observation.packageName} keyed=${appKey != null}")
        } else {
            log("watcher done reason=verify_timeout pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
        }
    }

    /**
     * Ожидание состояния окна с явным пределом: если пользователь отказался от
     * установки («ОТМЕНА»/«Не устанавливать»), ловец обязан завершиться, а не ждать
     * вечно. Фаза, начавшаяся во время ожидания, отменяет сессию.
     */
    private suspend fun awaitWindow(
        timeoutMs: Long,
        predicate: (InstallerObservation) -> Boolean
    ): InstallerObservation? {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            val observation = observe()
            if (observation != null) {
                if (OverlayController.phaseRunning || SimpleRunner.isRunning) {
                    log("watcher aborted reason=${InstallerWatcherLogic.REASON_PHASE_RUNNING}")
                    return null
                }
                if (predicate(observation)) return observation
            }
            delay(POLL_MS)
        }
        return null
    }

    /** Тап строго по id: других целей у ловца нет (ok_button/cancel_button недостижимы). */
    private fun clickById(observation: InstallerObservation, id: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = try {
            findById(root, id)
        } finally {
            recycleNode(root)
        }
        val clicked = clickNode(node)
        recycleNode(node)
        if (clicked) {
            log("watcher tap id=$id pkg=${observation.packageName}")
        }
        return clicked
    }

    /** Тап по узлу или ближайшему кликабельному предку (как в раннере шагов). */
    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= CLICK_ANCESTOR_DEPTH) {
            if (current.isClickable) {
                return runCatching {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            }
            current = current.parent
            depth++
        }
        return runCatching {
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }.getOrDefault(false)
    }

    /** Короткие имена id окна (после «:id/»): по ним гейт отличает состояния установщика. */
    private fun collectViewIds(root: AccessibilityNodeInfo): Set<String> {
        val ids = HashSet<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            if (depth > MAX_DEPTH || visited > MAX_NODES) return
            visited++
            node.viewIdResourceName?.toString()?.substringAfterLast('/')?.let { ids.add(it) }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return ids
    }

    /**
     * Ключ кулдауна: подпись устанавливаемого приложения из окна (`app_name`).
     * Хэш — чтобы подпись не попала в логи и состояние UI.
     */
    private fun appKeyOf(root: AccessibilityNodeInfo): String? {
        val label = findById(root, "app_name")?.text?.toString()?.trim().orEmpty()
        return label.takeIf { it.isNotEmpty() }?.let { Integer.toHexString(it.hashCode()) }
    }

    private fun toggleTexts(): List<String> =
        (SemanticCatalog.itemTexts(InstallerWatcherLogic.STEP_ID) +
            SemanticCatalog.keywords(InstallerWatcherLogic.STEP_ID)).distinct()

    /** Служебный пропуск: подписи чужого окна в лог не пишутся (политика R2.1). */
    private fun logSkip(observation: InstallerObservation, reason: String) {
        val signature = "${observation.packageName}|${observation.viewIds.size}|$reason"
        if (signature == lastSkipSignature) return
        lastSkipSignature = signature
        log("watcher skipped reason=$reason pkg=${observation.packageName}")
    }

    /** Гейт фазы/выключенного ловца: лог без обхода дерева, с дедупликацией. */
    private fun logSkipFast(reason: String) {
        val signature = "gate|$reason"
        if (signature == lastSkipSignature) return
        lastSkipSignature = signature
        log("watcher skipped reason=$reason")
    }

    private fun findById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (found != null || visited > MAX_NODES) return
            visited++
            if (node.viewIdResourceName?.toString()?.endsWith("/$id") == true) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    // Легаси: recycle() deprecated с API 33 (система перерабатывает узлы сама),
    // на Android 10–12 возвращает узел в пул — тот же версионный guard, что в раннере.
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        node ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val POLL_MS = 250L
        const val SETTINGS_WAIT_MS = 8_000L
        const val OFF_VERIFY_MS = 4_000L
        const val CLICK_ANCESTOR_DEPTH = 4
        const val MAX_NODES = 1200
        const val MAX_DEPTH = 30
    }
}