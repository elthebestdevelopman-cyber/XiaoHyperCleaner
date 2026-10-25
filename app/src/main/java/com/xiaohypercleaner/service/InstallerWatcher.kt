package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.ComponentVerifier
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
 * персистится единственный флаг `InstallerWatcherEnabled` — legacy: UI-тумблер снят
 * (1.0-beta2), значение читается и уважается, но не пишется.
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

    /**
     * Лексикон шага пуст: каталог не загружен к моменту окна установщика. Искать тумблер
     * нечем, а тапать шестерёнку вслепую нельзя — в прогоне rmvo4c03j это дало
     * `switch not found for 'null' (texts=0)` ×60 и две сессии подряд.
     */
    const val REASON_EMPTY_LEXICON = "empty_lexicon"

    /** На реконнекте окна может не быть вовсе — это тоже причина, а не тишина. */
    const val REASON_NO_WINDOW = "no_window"

    /** Цикл на этой сборке уже отработан: шестерёнку не тапаем, тумблер не трогаем. */
    const val REASON_CYCLE_DONE = "cycle_done"

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
     * Лексикон шага пуст: искать тумблер нечем, и тапать шестерёнку вслепую нельзя.
     * Пустой набор подписей давал `switch not found for 'null' (texts=0)` ×60 и две сессии
     * подряд (прогон rmvo4c03j). Чистая функция — тестируется без сервиса.
     */
    fun lexiconEmpty(toggleTexts: List<String>, screenMarkers: List<String>): Boolean =
        toggleTexts.isEmpty() && screenMarkers.isEmpty()

    /**
     * Дешёвый гейт (до обхода дерева и до решений по окну): выключенный ловец и
     * идущая фаза. `null` — можно смотреть окно.
     */
    fun gateReason(enabled: Boolean, phaseRunning: Boolean): String? = when {
        !enabled -> REASON_DISABLED
        phaseRunning -> REASON_PHASE_RUNNING
        else -> null
    }

    fun decide(observation: InstallerObservation, cycleClosed: Boolean): InstallerDecision = when {
        observation.packageName !in observation.installerPackages ->
            InstallerDecision.Ignore(REASON_FOREIGN_PACKAGE)

        // Цикл закрыт — не воюем: пользователь мог включить галочку сам, и выключать её
        // повторно до конца цикла (сброс данных / обновление системы) права нет.
        cycleClosed -> InstallerDecision.Ignore(REASON_CYCLE_DONE)

        observation.settingsScreen -> when {
            // Тумблера на экране нет: состояние НЕ подтверждено — цикл не закрываем.
            observation.toggleChecked == null ->
                InstallerDecision.Ignore(REASON_SWITCH_NOT_FOUND)

            // Подтверждённое «уже выключено» — работа сделана, цикл закрывает вызывающий.
            !observation.toggleChecked -> InstallerDecision.Ignore(REASON_ALREADY_OFF)
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
 * Признак «цикл закрыт»: сохранённый отпечаток сборки совпал с текущим.
 *
 * Кулдаун по подписи приложения удалён как мёртвый механизм: на экране настроек подписи
 * `app_name` нет, поэтому ключ всегда был null (в прогоне rmv6x9ts1 — `keyed=false` в 36
 * из 37 срабатываний) и кулдаун не срабатывал ни разу. Роль «не воевать с пользователем»
 * закрывает флаг цикла; два механизма на одну задачу — ловушка при разборе.
 */
internal fun cycleClosedFor(stored: String?, current: String): Boolean =
    !stored.isNullOrEmpty() && stored == current

/**
 * Отпечаток сборки для флага цикла: обновление системы меняет его — признак «работа
 * сделана» устаревает сам; сброс данных приложения стирает сохранённое значение.
 */
internal fun buildCycleFingerprint(incremental: String?, miuiVersion: String?): String =
    "${incremental.orEmpty()}|${miuiVersion.orEmpty()}"

/**
 * Ловец окна установки (триггер A): реагирует на события окна, пока живо окно
 * установщика, и действует только в post-install состоянии.
 *
 * Гейт фазы — свой: ранний выход [AdbEnablerService.onAccessibilityEvent] покрывает
 * только `SimpleRunner.isRunning`, а окно прогресса/откат фазы идут без шага в раннере.
 * Действие одно на окно: тап по шестерёнке, затем тап по целевому тумблеру.
 *
 * Цикл: одна операция на отпечаток сборки. После подтверждённого снятия (или
 * подтверждённого «уже выключено») цикл закрывается, и до смены отпечатка ловец молчит —
 * иначе пользователь включает галочку, ловец снова её снимает, и это бесконечная война
 * (прогон rmv6x9ts1: 36 снятий за одну установку).
 */
internal class InstallerWatcher(
    private val service: AccessibilityService,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Пакеты шага — тем же путём, что шаг ([SimpleRunner.candidatePackages]). */
    private val installerPackages: () -> List<String>,
    private val enabled: () -> Boolean,
    /** Цикл уже закрыт для текущего отпечатка сборки. */
    private val cycleClosed: () -> Boolean,
    /** Работа сделана — записать отпечаток цикла (персист ведёт вызывающий). */
    private val onCycleDone: () -> Unit,
    private val log: (String) -> Unit = { AppLog.i(TAG, it) },
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    /** Сессия ловца: одна за раз. */
    private var sessionActive = false

    /** Последняя залогированная причина и счётчик повторов: петля обязана быть видна. */
    private var lastSkipSignature: String? = null
    private var skipRepeat: Int = 0

    fun onEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        evaluateWindow(trigger = "event", windowPackage = event.packageName?.toString().orEmpty())
    }

    /**
     * Оценка активного окна на переподключении сервиса доступности: событий после реконнекта
     * нет, а окно установщика может уже стоять на экране (сервис переподключается и после
     * нашей переустановки). Гейты те же, что у события; строка печатается ВСЕГДА — иначе
     * «оценка была и не сработала» неотличима от «оценки не было».
     */
    fun onServiceReconnected() {
        val root = service.rootInActiveWindow
        val windowPackage = root?.packageName?.toString().orEmpty()
        recycleNode(root)
        evaluateWindow(trigger = "reconnect", windowPackage = windowPackage, alwaysLog = true)
    }

    /**
     * Общий путь события и реконнекта: дешёвые гейты (выключен / идёт фаза), гейт закрытого
     * цикла по пакету окна — до обхода дерева, затем снимок окна и решение.
     */
    private fun evaluateWindow(
        trigger: String,
        windowPackage: String,
        alwaysLog: Boolean = false
    ) {
        fun report(reason: String, pkg: String? = windowPackage.ifEmpty { null }) {
            if (alwaysLog) {
                val tail = pkg?.let { " pkg=$it" }.orEmpty()
                log("watcher window on $trigger reason=$reason$tail")
            } else {
                logRepeat(reason, pkg)
            }
        }

        val gate = InstallerWatcherLogic.gateReason(
            enabled(),
            OverlayController.phaseRunning || SimpleRunner.isRunning
        )
        if (gate != null) {
            report(gate)
            return
        }

        val packages = installerPackages()
        // Пакет-гейт ДО обхода дерева: чужое окно (наш собственный диалог, лаунчер, systemui)
        // не обходим вовсе — обход стоил чтений и печатал `row search failed` по подписям
        // НАШЕГО диалога (прогон rmvb6atul). Причина та же, что у решения по снимку.
        if (windowPackage.isNotEmpty() && windowPackage !in packages) {
            report(InstallerWatcherLogic.REASON_FOREIGN_PACKAGE)
            return
        }

        if (windowPackage.isNotEmpty() && cycleClosed()) {
            report(InstallerWatcherLogic.REASON_CYCLE_DONE)
            return
        }

        // Лексикон шага не загружен: искать тумблер нечем, и тапать шестерёнку нельзя —
        // пустой набор подписей давал `switch not found for 'null' (texts=0)` ×60 и две
        // сессии подряд (прогон rmvo4c03j). Каталог обязан быть загружен до ловца.
        val observation = observe()
        if (observation == null) {
            // Путь события молчит по-прежнему (шум в логе), реконнект говорит всегда.
            if (alwaysLog) report(InstallerWatcherLogic.REASON_NO_WINDOW)
            return
        }
        if (InstallerWatcherLogic.lexiconEmpty(
                toggleTexts(),
                SemanticCatalog.screenMarkers(InstallerWatcherLogic.STEP_ID)
            )
        ) {
            report(InstallerWatcherLogic.REASON_EMPTY_LEXICON, observation.packageName)
            return
        }
        val decision = InstallerWatcherLogic.decide(observation, cycleClosed())
        when (decision) {
            is InstallerDecision.Ignore -> report(decision.reason, observation.packageName)

            InstallerDecision.OpenSettings -> {
                if (sessionActive) {
                    // Сессия уже идёт: без строки реконнект выглядел бы как «не оценивал».
                    report(REASON_SESSION_ACTIVE, observation.packageName)
                    return
                }
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
                if (sessionActive) {
                    report(REASON_SESSION_ACTIVE, observation.packageName)
                    return
                }
                sessionActive = true
                scope.launch {
                    try {
                        turnOff(observation)
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
        turnOff(settings)
    }

    /**
     * Снять целевой тумблер: только в сторону false, с двойным подтверждением состояния.
     *
     * Разделение исходов принципиально: `null` (тумблера на экране нет) — состояние НЕ
     * подтверждено, цикл не закрываем; `false` — подтверждённое «уже выключено», работа
     * сделана, цикл закрываем (владелец: воевать не о чем).
     */
    private suspend fun turnOff(observation: InstallerObservation) {
        when (observation.toggleChecked) {
            null -> {
                logRepeat(InstallerWatcherLogic.REASON_SWITCH_NOT_FOUND, observation.packageName)
                InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
                return
            }

            false -> {
                // Уже выключено — молчим в UI, но цикл закрываем: делать больше нечего.
                logRepeat(InstallerWatcherLogic.REASON_ALREADY_OFF, observation.packageName)
                onCycleDone()
                InstallerWatcherState.set(InstallerWatcherStatus.Idle)
                return
            }

            true -> Unit
        }
        val root = service.rootInActiveWindow ?: return
        val switch = try {
            SwitchFinder.findSwitch(root, toggleTexts())
        } finally {
            recycleNode(root)
        }
        if (switch == null) {
            logRepeat(InstallerWatcherLogic.REASON_SWITCH_NOT_FOUND, observation.packageName)
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        val checkedBefore = SwitchFinder.isChecked(switch)
        val clicked = clickNode(switch)
        recycleNode(switch)
        if (!clicked) {
            log("watcher skipped reason=tap_failed pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
            return
        }
        // Тап по тумблеру логируем явно: без этой строки петля была невидима в логе.
        log("watcher tap target=switch checked_before=$checkedBefore pkg=${observation.packageName}")
        if (awaitStableOff(OFF_VERIFY_MS)) {
            onCycleDone()
            leaveInstallerSettings(observation.packageName)
            InstallerWatcherState.set(InstallerWatcherStatus.RecommendationsOff)
            log("watcher done recommendations_off pkg=${observation.packageName}")
        } else {
            log("watcher done reason=verify_timeout pkg=${observation.packageName}")
            InstallerWatcherState.set(InstallerWatcherStatus.Skipped)
        }
    }

    /**
     * Двойное подтверждённое чтение «выключено» подряд: одно чтение ненадёжно — дерево
     * на этом экране отдаёт то true, то false (прогон rmv6x9ts1: 36 снятий подряд).
     */
    private suspend fun awaitStableOff(timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        var confirmed = 0
        while (now() < deadline) {
            val observation = observe()
            if (observation != null) {
                if (OverlayController.phaseRunning || SimpleRunner.isRunning) {
                    log("watcher aborted reason=${InstallerWatcherLogic.REASON_PHASE_RUNNING}")
                    return false
                }
                if (observation.settingsScreen && observation.toggleChecked == false) {
                    confirmed++
                    if (confirmed >= OFF_CONFIRMATIONS) return true
                } else {
                    confirmed = 0
                }
            }
            delay(POLL_MS)
        }
        return false
    }

    /**
     * Возврат на домашний экран после работы: через `up`/Back пользователь попал бы назад
     * в окно установщика с шестерёнкой, откуда ловец снова полез бы (петля «не даёт уйти»).
     * Жест Back не используется — нативный GLOBAL_ACTION_HOME.
     */
    private suspend fun leaveInstallerSettings(pkg: String) {
        val ok = try {
            ComponentVerifier.resetToHome(service)
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            log("watcher home_failed pkg=$pkg")
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

    private fun toggleTexts(): List<String> = SemanticCatalog.itemTexts(InstallerWatcherLogic.STEP_ID)

    /**
     * Повтор одного и того же пропуска печатается со счётчиком: полное молчание дедупа
     * скрывало петлю целиком (прогон rmv6x9ts1 — 36 снятий подряд не было видно в логе).
     * Подписи чужого окна в лог не пишутся (политика R2.1), только пакет и счётчик.
     *
     * Печатается первый пропуск серии и далее каждый [SKIP_LOG_EVERY]-й: на каждое событие
     * окна лог получал ~950 строк одного и того же `reason=phase_running` (прогон rmvb6atul),
     * а счётчик при этом продолжает расти — петля по-прежнему видна.
     */
    private fun logRepeat(reason: String, pkg: String? = null) {
        val signature = "$reason|${pkg.orEmpty()}"
        skipRepeat = if (signature == lastSkipSignature) skipRepeat + 1 else 0
        lastSkipSignature = signature
        if (skipRepeat % SKIP_LOG_EVERY != 0) return
        val tail = if (pkg.isNullOrBlank()) "" else " pkg=$pkg"
        log("watcher skipped reason=$reason repeat=$skipRepeat$tail")
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

        /** Сессия ловца уже идёт — окно оценено, строка пишется только на реконнекте. */
        const val REASON_SESSION_ACTIVE = "session_active"

        /** Один и тот же пропуск печатается не чаще, чем каждый N-й (см. [logRepeat]). */
        const val SKIP_LOG_EVERY = 25

        /** Подтверждений «выключено» подряд, необходимых для вердикта. */
        const val OFF_CONFIRMATIONS = 2
        const val CLICK_ANCESTOR_DEPTH = 4
        const val MAX_NODES = 1200
        const val MAX_DEPTH = 30
    }
}