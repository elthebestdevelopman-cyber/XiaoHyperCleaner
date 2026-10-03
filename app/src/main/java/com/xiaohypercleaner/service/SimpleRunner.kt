package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.R
import com.xiaohypercleaner.XiaoHyperApp
import com.xiaohypercleaner.data.ActivityScanner
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.ComponentVerifier
import com.xiaohypercleaner.data.ConsentWallHandler
import com.xiaohypercleaner.data.DirectIntentNavigator
import com.xiaohypercleaner.data.PreferencesManager
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.ScanOrchestrator
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SemanticGate
import com.xiaohypercleaner.data.SimplePlan
import com.xiaohypercleaner.data.SimpleSteps
import com.xiaohypercleaner.data.SwitchFinder
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import com.xiaohypercleaner.util.DiagnosticSnapshotManager
import com.xiaohypercleaner.util.StepDiagnostics
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Автоматизация шагов Simple Mode через Accessibility.
 *
 * ИНТЕГРАЦИЯ (пункты оптимизации 1–6):
 * П.1 — Базовая автоматизация и Accessibility Service.
 * П.2 — Адаптивные тайм-ауты (учет HyperOS и глубины маршрута).
 * П.3 — Полная интеграция с AdaptiveCatalog (мердж текстов, пакетов, путей).
 * П.4 — Структурный поиск ⋮/⚙ (4 уровня: текст, description, позиция, жест).
 * П.5 — Перехват системных диалогов (аудио, приложения по умолчанию).
 * П.6 — Пропуск повторного сброса настроек (переиспользование окна).
 */
class SimpleRunner(private val service: AdbEnablerService) {

    companion object {
        private const val TAG = "SimpleRunner"

        // ═══════════════════════════════════════════════════════════════
        // П.2: Адаптивные таймауты
        // ═══════════════════════════════════════════════════════════════
        private const val BASE_TIMEOUT_MS = 16_000L
        private const val LONG_PATH_TIMEOUT_MS = 22_000L
        private const val VERY_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_TIMEOUT_MS = 20_000L
        private const val APP_LONG_PATH_TIMEOUT_MS = 26_000L
        private const val APP_VERY_LONG_PATH_TIMEOUT_MS = 32_000L
        private const val HYPEROS_MULTIPLIER = 1.3f
        private const val MSA_TIMEOUT_MS = 40_000L
        private const val SECURITY_CLEANER_TIMEOUT_MS = 30_000L

        /**
         * Settings/GMS-маршруты: навигация до целевого тумблера, сам тумблер и
         * подтверждение не укладывались в базовые 16 с — тумблеры фактически
         * выключались, а шаг рапортовал timeout (прогон rmuh2vb1r: ads_personalization,
         * security_sys, google_diagnostics — checked=false в снапшотах).
         */
        private const val SETTINGS_LONG_TIMEOUT_MS = 30_000L

        private val SPECIAL_TIMEOUTS = mapOf(
            "msa" to MSA_TIMEOUT_MS,
            "security_sys" to SECURITY_CLEANER_TIMEOUT_MS,
            "cleaner" to SECURITY_CLEANER_TIMEOUT_MS,
            // Карусель: пять тумблеров (главный + две строки подменю «Политика
            // конфиденциальности» + свайп + мобильные данные) и три перехода между
            // экранами — базового бюджета не хватало (прогон rmuoh815k).
            "carousel" to 30_000L,
            // Перебор кандидатов-папок и активностей установщика: шаги дольше базовых.
            "folder_recommendations" to 34_000L,
            "installer_recommendations" to 26_000L,
            // Mi Music: три уровня drill (☰ → «Настройки» → «Расширенные настройки»), целевые
            // тумблеры лежат в разделе ниже сгиба — к бюджету добавляются прокрутки (rmuef7nbf).
            "music_sys" to 26_000L,
            // Settings/GMS-шаги: тот же бюджет, что у security_sys — успевают дойти до
            // тумблера и подтвердить его состояние.
            "sys_recommendations" to SETTINGS_LONG_TIMEOUT_MS,
            "ads_personalization" to SETTINGS_LONG_TIMEOUT_MS,
            "ux_program" to SETTINGS_LONG_TIMEOUT_MS,
            "google_diagnostics" to SETTINGS_LONG_TIMEOUT_MS,
            "home_suggestions" to SETTINGS_LONG_TIMEOUT_MS,
            "themes" to SETTINGS_LONG_TIMEOUT_MS,
            "mivideo" to SETTINGS_LONG_TIMEOUT_MS,
            "browser_sys" to SETTINGS_LONG_TIMEOUT_MS,
            "carousel" to SETTINGS_LONG_TIMEOUT_MS
        )

        // ═══════════════════════════════════════════════════════════════
        // П.6: Возобновляемые шаги (не требуют сброса настроек)
        // ═══════════════════════════════════════════════════════════════
        private val SETTINGS_RESUMABLE_STEPS = setOf(
            "msa", "sys_recommendations", "ads_personalization", "ux_program",
            "google_diagnostics", "carousel"
        )

        // ═══════════════════════════════════════════════════════════════
        // П.5: Системные диалоги
        // ═══════════════════════════════════════════════════════════════
        // MEDIA-шаги перенесены в ConsentWallHandler (allow аудио-разрешения для music_sys/mivideo).

        // ═══════════════════════════════════════════════════════════════
        // П.4: Структурный поиск ⋮/⚙
        // ═══════════════════════════════════════════════════════════════
        /**
         * Кнопки-меню в шапке экрана. Отдельный список: в списках элементов встречается
         * «Больше меню» (Музыка: `item_menu` у каждого трека) — общий overflow-поиск
         * цеплял строку списка, тапал не туда, и меню шапки не открывалось
         * (прогон rmua2sd7x: music_sys drill_failed на уровне 1).
         */
        private val HEADER_MENU_TEXTS = listOf(
            "Показать меню", "Show menu", "Открыть меню", "Меню", "Menu"
        )

        private val OVERFLOW_TEXTS = listOf(
            "⋮", "Ещё", "Еще", "Больше", "Меню",
            "⚙", "⚙️", "Настройки", "Настройка",
            "More", "More options", "Menu", "Settings",
            "Больше опций", "Дополнительно", "Дополнительные настройки"
        )

        private val OVERFLOW_GESTURE_POINTS = listOf(
            Pair(0.96f, 0.06f), Pair(0.90f, 0.06f),
            Pair(0.96f, 0.12f), Pair(0.90f, 0.12f)
        )

        /** Уровни-меню drill: открываются overflow-поиском (текст или contentDescription). */
        private val MENU_LEVEL_TEXTS = setOf(
            "⋮", "", "⚙️", "Ещё", "Еще", "Больше", "Дополнительно", "More", "Más", "Menu"
        )

        /** Пакет системных настроек (проверка foreground для системных шагов). */
        private const val SETTINGS_PACKAGE = "com.android.settings"
        /** Предельная глубина обхода превью папки лаунчера (icon_container → preview → itemN). */
        private const val FOLDER_PREVIEW_DEPTH = 3

        /** Долгий тап: контекстное меню папки (HyperOS 2/3 → «Изменить папку»). */
        private const val LONG_PRESS_MS = 800L

        /** Пауза после открытия папки: поповер анимируется. */
        private const val FOLDER_OPEN_DELAY_MS = 500L

        /** Ожидание поповера папки после тапа (анимация MIUI до ~0,5 с). */
        private const val FOLDER_POPOVER_WAIT_MS = 2_000L
        private const val FOLDER_POPOVER_POLL_MS = 200L
        /** Сколько раз пробуем нажать название папки в поповере. */
        private const val FOLDER_TITLE_TAPS = 2
        /** Глубина подъёма по предкам при проверке «узел принадлежит иконке рабочего стола». */
        private const val ICON_ANCESTOR_DEPTH = 6

        /** Сколько активностей установщика пробуем, прежде чем признать шаг неприменимым. */
        private const val MAX_INSTALLER_CANDIDATES = 5

        /**
         * Сколько папок рабочего стола проверяем за шаг. Папка с рекомендациями может
         * быть не первой по дереву (POCO: первой стоит «Инструменты»), поэтому шаг
         * проверяет ВСЕ папки, а не сдаётся после первой (прогон владельца 2026-09-28).
         */
        private const val MAX_FOLDER_PROBES = 6

        /**
         * Сколько папок проверяем структурно, если НИ ОДНО имя не совпало с подсказками
         * каталога: у пользователя бывает 20 папок, и перебор всех — недопустимо дорогой
         * путь. Не нашли среди них — честное «не найдено автоматически».
         */
        private const val MAX_FOLDER_STRUCTURAL_PROBES = 3

        /** Резерв бюджета шага: ниже него новые папки не пробуем. */
        private const val FOLDER_BUDGET_RESERVE_MS = 4_000L

        /** Пакеты-лаунчеры MIUI: папки рабочего стола есть только в них. */
        private val LAUNCHER_PACKAGES = listOf(
            "com.miui.home", "com.mi.android.globallauncher", "com.miui.launcher", "com.mi.global.home"
        )

        /** Префикс шагов управления уведомлениями приложений. */
        private const val NOTIF_PREFIX = "notif_"

        /** Признаки активности настроек установщика (активности установки не входят). */
        private val INSTALLER_SETTINGS_KEYWORDS =
            listOf("settings", "preference", "recommend", "advanced", "scan")

        /** Тексты подтверждения установки: по ним не тапаем никогда. */
        private val INSTALL_CONFIRM_TEXTS =
            listOf("установить", "установка", "install", "安装", "instalar", "instalir")

        /** msa: максимум ожидания включённой кнопки отзыва, поллинг и пауза на сам отзыв. */
        private const val MSA_REVOKE_WAIT_MAX_MS = 11_000L
        private const val MSA_CONFIRM_POLL_MS = 350L

        /** Отсчётный хвост кнопки MIUI: «(9 с)», «(9s)», «(9)» — кнопка ещё неактивна. */
        private val COUNTDOWN_LABEL_REGEX = Regex("\\(\\s*\\d+\\s*(с|s)?\\s*\\)")
        /** Потолок ожидания фактического отзыва после тапа подтверждения. */
        private const val MSA_REVOKE_SETTLE_MAX_MS = 8_000L

        /** Фолбэк-действие варианта: очистка данных + отклонение приветствия. */
        private const val FALLBACK_ACTION_CLEAR_DATA = "clear_data_decline"

        /** Точка входа варианта: маршрут через Настройки, а не через приложение. */
        private const val ENTRY_SETTINGS = "settings"

        /** Причины, при которых применяется фолбэк-действие варианта. */
        private val FALLBACK_REASONS =
            setOf("drill_failed", "switch_not_found", "low_confidence", "verify_failed")

        // ═══════════════════════════════════════════════════════════════
        // Общие константы
        // ═══════════════════════════════════════════════════════════════
        // Ускорение прогона (владелец: «быстрее открывать экраны, быстрее листать»):
        // паузы после навигации сокращены, ожидания остались опросными и растут сами.
        private const val UI_SETTLE_DELAY_MS = 450L
        /** Пауза после снятия отметки с чекбокса персонализации (до проверки факта). */
        private const val UNCHECK_SETTLE_DELAY_MS = 250L
        private const val APP_LAUNCH_DELAY_MS = 1200L
        private const val CONTENT_WAIT_MS = 1600L

        /**
         * Готовность приложения после запуска: холодный старт MIUI-приложений
         * (GetApps — WebView-магазин) не укладывается в фиксированные 2 c, из-за
         * чего раннер сжигал бюджет на «App not ready» по всем кандидатам и
         * стартовал бурение на сплэше (прогон rmu8qhjhi).
         */
        private const val APP_READY_WAIT_MS = 4_000L

        /** Готовность экрана приложения перед бурением: подписи первого уровня маршрута. */
        private const val APP_SCREEN_WAIT_MS = 4_000L
        private const val APP_READY_POLL_MS = 250L
        /** Повторное чтение состояния тумблера: MIUI применяет его с задержкой. */
        private const val SWITCH_VERIFY_ATTEMPTS = 3
        private const val SWITCH_VERIFY_RETRY_DELAY_MS = 600L
        /** Ожидание кнопки-отказа после главного тумблера (Карусель: «Нет, спасибо»). */
        private const val TOGGLE_DECLINE_WAIT_MS = 2500L
        private const val CONFIRM_RETRY_MS = 1500L

        /**
         * Попыток подтверждения диалога: основная + один повтор. Дальше — честный провал
         * шага (`confirm_not_closed`): подтверждение, которое не нажалось, оставляет диалог
         * открытым и ломает следующий шаг (прогон rmulhb4yq, downloads).
         */
        private const val CONFIRM_ATTEMPTS = 2
        private const val SWITCH_FALLBACK_SCROLLS = 4

        /**
         * Шаг неприменим на этом устройстве: экрана/строк шага здесь нет вовсе.
         * Отдельный исход (skip), а не FAIL — иначе отчёт врёт (POCO Launcher).
         */
        internal const val NOT_APPLICABLE = "not_applicable"

    /**
     * Экран маршрута не подтвердился после intent-шага и одного relaunch (D1, браузер):
     * честный skip в ведре UNRESOLVED — тумблер на чужом экране не ищем.
     */
    internal const val ROUTE_SCREEN_UNCONFIRMED = "route_screen_unconfirmed"

        /**
         * Настройка лаунчера, которой на прошивке нет вовсе (POCO: в настройках
         * рабочего стола нет ни «Показывать предложения», ни рекомендаций).
         */
        internal const val LAUNCHER_ABSENT = "launcher_setting_absent"

        /**
         * Поповер папки открылся, но её редактор/секция рекомендаций не открылись ни
         * тапом по названию, ни контекстным меню: это промах автоматизации, а не
         * «нет на устройстве» (ведро UNRESOLVED в отчёте).
         */
        internal const val FOLDER_EDITOR_NOT_OPENED = "folder_editor_not_opened"

        /** Папки проверены все, секции рекомендаций нет ни в одной. */
        internal const val FOLDER_SWITCH_ABSENT = "folder_switch_absent"

        /**
         * Тумблер выключен самой прошивкой (`enabled=false` у строки и её
         * кликабельного предка): настройки на устройстве нет — ведро «нет на
         * устройстве», а не промах (Ленты виджетов «Показывать уведомления»,
         * дамп `appvault_notif_disabled`).
         */
        internal const val SWITCH_DISABLED_BY_ROM = "switch_disabled"

        /** У установщика нет экспортированных активностей настроек на этой прошивке. */
        internal const val INSTALLER_SETTINGS_ABSENT = "installer_settings_not_found"

        /** Все кандидаты настроек установщика отказали в старте (Permission Denial). */
        internal const val INSTALLER_SETTINGS_DENIED = "installer_settings_denied"

        /**
         * Ведро причины пропуска шага. Статический помощник: его зовёт AdbEnablerService
         * при выборе текста отчёта, экземпляр раннера для этого не нужен.
         *
         * Маппинг reason → ведро (прогон rmumuqr53: навигационные провалы уезжали в
         * «нет на устройстве», и отчёт обвинял устройство вместо автоматизации):
         * - [SkipKind.LAUNCHER_ABSENT] — `launcher_setting_absent`: настройки рабочего
         *   стола, которой на прошивке нет (home_suggestions и подобные шаги лаунчера);
         * - [SkipKind.NOT_ON_DEVICE] — ТОЛЬКО признаки отсутствия функции на устройстве:
         *   `app_not_installed`, `folder_switch_absent`, `switch_disabled`,
         *   `installer_settings_not_found`, `installer_settings_denied`;
         * - [SkipKind.UNRESOLVED] — всё остальное: навигационные провалы
         *   (`not_applicable`, `drill_failed`, `screen_markers_absent`,
         *   `foreign_screen`, провалившийся route), `low_confidence`,
         *   `switch_not_found`, `verify_failed`, неизвестная причина и null.
         */
        internal fun classifySkip(reason: String?): SkipKind = when (reason) {
            LAUNCHER_ABSENT -> SkipKind.LAUNCHER_ABSENT
            "app_not_installed", FOLDER_SWITCH_ABSENT, SWITCH_DISABLED_BY_ROM,
            INSTALLER_SETTINGS_ABSENT, INSTALLER_SETTINGS_DENIED -> SkipKind.NOT_ON_DEVICE
            else -> SkipKind.UNRESOLVED
        }


        /** Поллинг проверки входного экрана (notif_*: подпись приложения). */
        private const val ENTRY_POLL_MS = 200L

        /** Variation selector эмодзи: «⚙» и «⚙️» — один и тот же уровень-меню. */
        private const val EMOJI_VARIATION_SELECTOR = "\uFE0F"

    /** Scroll-until-found для уровней drill: до 4 прокруток на уровень. */
    private const val DRILL_SCROLL_TRIES = 4

    /**
     * Scroll-until-found для строки-цели тумблера: строка живёт ниже сгиба
     * (Cleaner «Получать рекомендации» y≈2118 на экране 2400; App Vault
     * «Рекомендации приложений» за списком карточек), четырёх прокруток не хватало —
     * шаг уходил в low_confidence/keyword_mismatch (прогон rmulhb4yq).
     */
    private const val ROW_SCROLL_ATTEMPTS = 8

    /** Прокруток подряд без сдвига экрана, после которых «упёрлись» и прекращаем. */
    private const val ROW_SCROLL_STALL_LIMIT = 2

    /** Сколько совпавших узлов уровня пробуем, прежде чем признать уровень непройденным. */
    private const val DRILL_LEVEL_ATTEMPTS = 3

    /** Ожидание маркеров экрана после intent-шага маршрута (D1: браузер). */
    private const val ROUTE_SCREEN_WAIT_MS = 2_500L

    /** Ожидание маркеров после единственного relaunch (компонента → запасное действие). */
    private const val ROUTE_SCREEN_RETRY_WAIT_MS = 1_500L

    /** Поллинг подтверждения экрана маршрута. */
    private const val ROUTE_SCREEN_POLL_MS = 250L

    /** Повторы уровня drill после закрытия всплывшего диалога (permission целевого приложения). */
    private const val DRILL_CONSENT_RETRIES = 2
        private const val FRESH_DEVICE_DISMISS_LIMIT = 3

        /** Гейт оверлея: ожидание восстановления окна (Аддендум A4). */
        private const val OVERLAY_GATE_WAIT_MS = 2000L
        private const val OVERLAY_GATE_POLL_MS = 100L

        /**
         * Ожидание ПОДТВЕРЖДЕНИЯ passthrough-окна перед инъекцией жеста.
         * Запрос уходит в OverlayService через `startService` (асинхронно): жест,
         * отправленный сразу, попадает в ещё-перехватывающее окно — прогон rmumuqr53:
         * `tap: … via=node` → `touch intercepted x=972 y=2182` → пассивный канал
         * открылся на 60 мс позже, тап пропал, ретрай ударил по соседнему узлу.
         */
        private const val OVERLAY_PASSTHROUGH_WAIT_MS = 500L
        private const val OVERLAY_PASSTHROUGH_POLL_MS = 25L

        /** Повторы ТОГО ЖЕ узла уровня drill до перехода к следующему кандидату. */
        private const val DRILL_NODE_RETRIES = 2

        /**
         * Окно пропуска касаний оверлеем вокруг нашей инъекции (dispatchGesture):
         * покрывает settle сервиса, сам жест и обработку. Отсчёт идёт от постановки
         * запроса, поэтому берём с запасом (S4: ±500 мс вокруг жеста).
         */
        private const val OVERLAY_PASSTHROUGH_WINDOW_MS = 900L

        /**
         * Резерв бюджета шага на тап+verify тумблера: тапать «в последний момент»
         * бессмысленно — verify не успевает, шаг падает `timeout` без причины в логе.
         */
        private const val TOGGLE_BUDGET_RESERVE_MS = 4000L

        /** Сколько ждать появления диалога подтверждения (confirmTexts) после тумблера. */
        /** Пауза после тапа подтверждения: проверяем, что диалог действительно закрылся. */
        private const val CONFIRM_SETTLE_MS = 400L

        private val SYSTEM_DIALOG_SKIPS = listOf(
            "Пропустить", "Пропустить настройку", "Не сейчас", "Закрыть", "Отозвать", "Отмена"
        )

        @Volatile
        var isRunning: Boolean = false; private set
        @Volatile
        var currentStepId: String? = null; private set
        @Volatile
        var lastFailureReason: String? = null; private set
    }

    data class Result(val success: Boolean, val reason: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: kotlinx.coroutines.Job? = null

    @Volatile
    private var cancelled: Boolean = false

    /**
     * Последний провал уровня drill случился потому, что строки уровня НЕТ на экране
     * (в отличие от «нашли, но тап не дал эффекта»). Признак неприменимости app-шага.
     */
    // internal — для тестируемости (SimpleRunnerDrillTest).
    internal var lastDrillLevelNotFound: Boolean = false
    // lateinit: профиль устанавливается в run(). Прежний eager-detect был мёртвым:
    // результат перезаписывался в run() и никогда не читался, а на mock-сервисе
    // ронял конструктор (NPE на resources.configuration).
    private lateinit var romProfile: RomProfile

    // П.6: Флаг переиспользования окна настроек
    private var canResumeSettings: Boolean = false

    /** Дедлайн бюджета текущего шага: по нему считается остаток перед тумблером. */
    private var stepDeadlineMs: Long = 0L

    /** Остаток бюджета шага (Long.MAX_VALUE, если дедлайн не задан — unit-тесты). */
    private fun remainingBudgetMs(): Long =
        if (stepDeadlineMs <= 0L) Long.MAX_VALUE else stepDeadlineMs - System.currentTimeMillis()

    /** Подписи приложений для проверки входа notif_*-шагов (кэш на прогон). */
    private val appLabelCache = HashMap<String, String>()

    // ─── Fresh-device state ───────────────────────────────────────────────
    private var freshDeviceDismisses = 0
    private var freshDeviceActive = false
    private var mutedForFreshDevice = false
    private var originalVolume: Int = -1

    /** Громкость, снятая на время шага с `muteMediaOnLaunch` (Mi Video: автоплей промо). */
    private var mutedForStep: Int = -1

    private fun muteMediaVolume(): Int {
        val audio = service.getSystemService(AudioManager::class.java) ?: return -1
        val prev = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Лог всегда, включая «глушить нечего»: без него отсутствие строки `was=N`
        // неотличимо от «шаг вообще не глушил» — ровно так выглядел прогон rmuojptft
        // (`media volume restored to 0` без строки mute).
        if (prev > 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            AppLog.i(TAG, "media muted (was=$prev, ringer=${audio.ringerMode})")
        } else {
            AppLog.i(TAG, "media mute skipped (already silent, ringer=${audio.ringerMode})")
        }
        return prev
    }

    /** Возврат громкости с фактическим подтверждением: лог пишет, что вышло на деле. */
    private fun restoreMediaVolume(prev: Int) {
        if (prev < 0) return
        val audio = service.getSystemService(AudioManager::class.java) ?: return
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        AppLog.i(TAG, "media volume restored to $prev (actual=$now)")
    }

    internal fun markFreshDevice() {
        if (freshDeviceActive) return
        freshDeviceActive = true
        freshDeviceDismisses = 0
        originalVolume = muteMediaVolume()
        mutedForFreshDevice = originalVolume > 0
    }

    /**
     * Выход шага (в том числе cancel/timeout/исключение — вызывается из finally прогона).
     *
     * Порядок восстановления важен: сначала ШАГОВАЯ громкость, затем снапшот свежего
     * устройства. Раньше [restoreStepMute] обнулял `originalVolume`, и ветка возврата
     * fresh-device становилась мёртвой: громкость, поднятая владельцем до прогона,
     * оставалась заглушённой (прогон rmuojptft: `media volume restored to 0`).
     */
    internal fun cleanupFreshDevice() {
        restoreStepMute()
        if (!freshDeviceActive) return
        if (mutedForFreshDevice) restoreMediaVolume(originalVolume)
        freshDeviceActive = false
        mutedForFreshDevice = false
        originalVolume = -1
    }

    /**
     * Шаги с `muteMediaOnLaunch` (Mi Video) открываются с автопроигрыванием промо-ролика:
     * медиа-звук глушится на время шага и возвращается в [restoreStepMute].
     */
    internal fun muteForStepIfNeeded(step: SimpleSteps.Step) {
        restoreStepMute()
        if (step.launchPackage == null || !SemanticCatalog.muteMediaOnLaunch(step.id)) return
        val prev = muteMediaVolume()
        if (prev < 0) return
        mutedForStep = prev
        AppLog.i(TAG, "media muted for step ${step.id} (muteMediaOnLaunch, was=$prev)")
    }

    /**
     * Возврат шаговой громкости. Состояние свежего устройства здесь НЕ трогаем:
     * `freshDeviceDismisses`/`originalVolume` принадлежат [cleanupFreshDevice], и их
     * сброс отсюда делал возврат громкости мёртвой веткой (прогон rmuojptft).
     */
    internal fun restoreStepMute() {
        if (mutedForStep < 0) return
        val was = mutedForStep
        mutedForStep = -1
        restoreMediaVolume(was)
    }

    private fun freshDeviceDismiss(text: String): Boolean {
        if (!freshDeviceActive || freshDeviceDismisses >= FRESH_DEVICE_DISMISS_LIMIT) return false
        freshDeviceDismisses++
        AppLog.i(
            TAG,
            "fresh-device dismiss ($freshDeviceDismisses/$FRESH_DEVICE_DISMISS_LIMIT): '$text'"
        )
        return true
    }

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Гейт целостности оверлея: в обычном прогоне Простого режима окно прогресса
     * обязано быть на экране, в канале отката ([AdbEnablerService.reverseSimpleToggles])
     * его нет вовсе — окно прогресса показывает MainActivity, а не раннер, и прежний
     * безусловный гейт фейлил каждый шаг отката `overlay_lost` (прогоны rmuebpgnr,
     * rmueihkd3: `reverseSimpleToggles done: 0/11`, «Не удалось подключиться для
     * восстановления»). Задаётся на время прогона параметром [run].
     */
    // internal — для тестируемости (SimpleRunnerOverlayGateTest).
    internal var overlayGateRequired: Boolean = true

    /**
     * Канал отката ([overlayGateRequired] = false): окна прогресса нет, и уход на рабочий
     * стол запрещён — приложение-инициатор обязано остаться видимым (MainActivity в
     * foreground), иначе MIUI блокирует запуск приложения шага как фоновый и откат
     * упирается в `App not ready … fg=com.mi.android.globallauncher` (прогон rmuftmq29:
     * reverse `music_sys` → timeout). В обычном прогоне рабочий стол нужен: оверлей
     * остаётся верхним окном и навигация начинается с известной точки.
     */
    // internal — для тестируемости (SimpleRunnerOverlayGateTest).
    internal fun needsHomeBeforeAppLaunch(): Boolean = overlayGateRequired

    /**
     * [requireOverlay] = false запускает шаг вне Простого режима (канал отката):
     * навигация выполняется без проверки окна прогресса и без ухода на рабочий стол.
     */
    fun run(
        step: SimpleSteps.Step,
        profile: RomProfile,
        requireOverlay: Boolean = true,
        callback: (Result) -> Unit
    ) {
        cancel()
        cancelled = false
        overlayGateRequired = requireOverlay
        isRunning = true
        currentStepId = step.id
        lastFailureReason = null
        romProfile = profile
        // Семантика шага (keywords/markers/consent) должна быть загружена.
        SemanticCatalog.ensureLoaded(service)
        // Выбираем вариант каталога по fingerprint (global_ru / cn_hyperos).
        AdaptiveCatalog.selectVariant(service, profile)

        val timeout = computeTimeout(step, profile)
        AppLog.i(TAG, "Executing step: ${step.id} (timeout ${timeout}ms)")
        // Дедлайн нужен фазе тумблера: перед тапом проверяем остаток бюджета (S2).
        stepDeadlineMs = System.currentTimeMillis() + timeout

        job = scope.launch {
            val start = System.currentTimeMillis()
            // Размер плана (префильтр) в диагностике: total на оверлее = размер плана.
            val planTotal = SimplePlan.total().takeIf { it > 0 } ?: SimpleSteps.ALL.size
            StepDiagnostics.stepStart(step.id, 0, planTotal, null, profile)

            val result = try {
                val r = withTimeoutOrNull(timeout) { runInternal(step, profile) } ?: Result(
                    false,
                    "timeout"
                )

                // P5: перед провалом — повторная проверка состояния. Действие уже
                // отправлено, а подтверждение могло не успеть (MIUI применяет настройку
                // с задержкой, диалог закрывается позже): шаг отчитывался
                // verify_failed/timeout, хотя на устройстве всё применилось.
                val lateVerified = !r.success &&
                    r.reason in LATE_VERIFY_REASONS &&
                    lateVerify(step)
                val effective = if (lateVerified) Result(true, "verified_late") else r

                val root = service.rootInActiveWindow
                StepDiagnostics.stepResult(
                    step.id,
                    effective.success,
                    effective.reason ?: if (effective.success) "ok" else "unknown",
                    System.currentTimeMillis() - start,
                    root,
                    service
                )

                if (!effective.success) {
                    val failureReason = effective.reason ?: "unknown"
                    DiagnosticSnapshotManager.captureAndSaveSnapshot(
                        service,
                        step.id,
                        failureReason,
                        root,
                        profile,
                        root?.packageName?.toString()
                    )
                    DiagnosticSnapshotManager.captureScreenshot(service, step.id)
                }
                recycleNode(root)
                effective
            } catch (e: Exception) {
                AppLog.e(TAG, "Step ${step.id} failed: ${e.message}", e)
                lastFailureReason = "error"
                Result(false, "error")
            } finally {
                cleanupFreshDevice()
                stepDeadlineMs = 0L
                isRunning = false
                currentStepId = null
            }
            callback(result)
        }
    }

    // П.2: Расчёт адаптивного таймаута
    private fun computeTimeout(step: SimpleSteps.Step, profile: RomProfile): Long {
        SPECIAL_TIMEOUTS[step.id]?.let { return it }
        val isApp = step.launchPackage != null
        val drillDepth = step.drillPath.size

        val base = when {
            isApp && drillDepth >= 4 -> APP_VERY_LONG_PATH_TIMEOUT_MS
            isApp && drillDepth == 3 -> APP_LONG_PATH_TIMEOUT_MS
            isApp -> APP_TIMEOUT_MS
            drillDepth >= 5 -> VERY_LONG_PATH_TIMEOUT_MS
            drillDepth == 4 -> LONG_PATH_TIMEOUT_MS
            else -> BASE_TIMEOUT_MS
        }

        val timeout = if (profile.hyperOsHint) (base * HYPEROS_MULTIPLIER).toLong() else base
        return timeout.coerceIn(12_000L, 45_000L)
    }

    /**
     * Причины, при которых действие уже отправлено, а подтверждение не успело: перед
     * провалом шаг проверяет состояние ещё раз и, если оно совпало, отчитывается
     * `verified_late` (P5). Ошибки навигации, отсутствие узла и открытый диалог сюда не
     * входят: там состояние читать нечем, и поздняя проверка дала бы ложный успех.
     */
    private val LATE_VERIFY_REASONS = setOf(
        "timeout",
        "verify_failed",
        "tap_failed"
    )

    /** Фактическое состояние тумблера шага прямо сейчас (null — узла нет). */
    private fun readSwitchState(step: SimpleSteps.Step): Boolean? {
        val root = service.rootInActiveWindow ?: return null
        val node = findSwitchByText(root, searchTextsFor(step))
        val state = node?.let { SwitchFinder.isChecked(it) }
        recycleNode(node); recycleNode(root)
        return state
    }

    /**
     * Поздняя проверка состояния перед объявлением провала: экран подтверждаем
     * маркерами шага (иначе «состояние совпало» можно прочитать на чужом экране),
     * состояние — фактическим чтением тумблера.
     */
    internal suspend fun lateVerify(step: SimpleSteps.Step): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        val markers = SemanticCatalog.screenMarkers(step.id)
        val markersOk = markers.isEmpty() ||
            markers.any { TextMatcher.normalizedContains(screenText, it) }
        if (!markersOk) {
            recycleNode(root)
            AppLog.i(TAG, "late verify: markers absent step=${step.id} — провал остаётся")
            return false
        }
        val node = findSwitchByText(root, searchTextsFor(step))
        val actual = node?.let { SwitchFinder.isChecked(it) }
        recycleNode(node); recycleNode(root)
        val ok = actual != null && actual == step.targetChecked
        AppLog.i(
            TAG,
            "late verify: step=${step.id} checked_after=$actual target=${step.targetChecked} ok=$ok"
        )
        StepDiagnostics.note(
            step.id,
            "VERDICT",
            "verified_late=$ok checked_after=$actual target=${step.targetChecked} markers=ok"
        )
        return ok
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        isRunning = false
        currentStepId = null
        AppLog.i(TAG, "Runner cancellation requested")
    }

    // ─── Internal execution ───────────────────────────────────────────────
    private suspend fun runInternal(stepParam: SimpleSteps.Step, profile: RomProfile): Result {
        // Семантический launchPackage (каталог) дополняет legacy-структуру шага.
        var step = applySemanticLaunchPackage(stepParam)
        if (cancelled) return Result(false, "cancelled")

        // Гейт целостности оверлея (Аддендум A4): навигация без окна прогресса запрещена.
        if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")

        // Папки рабочего стола: вход — лаунчер MIUI, маршрут Настроек не используется.
        if (step.actionType == SimpleSteps.ActionType.HOME_FOLDER_TOGGLE) {
            return toggleHomeFolderSuggestions(step)
        }

        // Настройки установщика: активность настроек проверки, установка APK не запускается.
        if (step.actionType == SimpleSteps.ActionType.INSTALLER_SETTINGS_TOGGLE) {
            return toggleInstallerRecommendations(step)
        }

        // RouteScript: явный сценарий маршрута, проверенный руками на этой прошивке, —
        // БЫСТРАЯ ПОДСКАЗКА (3–5 с), а не замена навигации. Дошёл до целевого экрана —
        // тумблер ищет общий хвост шага (гейт уверенности + checked_before + verify);
        // не дошёл — шаг продолжает авто-навигацией (intents → drill → сканер):
        // вариантные пути обязаны оставаться подсказками-фолбэками (master-task v2).
        val routeScript = SemanticCatalog.route(step.id)
        if (routeScript.isNotEmpty()) {
            AppLog.i(TAG, "route script for ${step.id}: ${routeScript.size} шаг(ов) — подсказка, не замена навигации")
            StepDiagnostics.note(step.id, "ROUTE", "script=${routeScript.size}")
            if (executeRouteScript(step, routeScript)) {
                runPreMainExtras(step)
                return findAndToggleSwitch(step)
            }
            AppLog.w(TAG, "route ${step.id} failed — fallback to auto-nav")
            StepDiagnostics.note(step.id, "ROUTE", "fallback_to_auto_nav")
        }

        // Резолвинг пакета: вариантный каталог + семантическая таблица (visibility-aware).
        // Маршрут варианта может идти через Настройки (appvault_*: Рабочий стол → Лента
        // виджетов) — тогда приложение не запускаем и пакет не резолвим.
        // notif_*: точка входа — системный экран уведомлений приложения
        // (ACTION_APP_NOTIFICATION_SETTINGS). Discovery-скан приложения здесь вреден:
        // его явная компонента открывала ленту App Vault вместо уведомлений (rmu8lzcu9).
        val notifStep = step.id.startsWith("notif_")
        // Пакет-цель notif_*-шага: подпись приложения на экране уведомлений отличает
        // целевой экран от чужого (MIUI 13: неоткрывшийся интент оставлял шаг на
        // «Заблокированном экране», где ключевые слова шага тоже встречаются).
        val notifTarget = if (notifStep) resolveNotifTarget(step) else null
        val settingsEntry = SemanticCatalog.entry(step.id) == ENTRY_SETTINGS || notifStep
        if (notifStep) {
            AppLog.i(TAG, "notif step ${step.id}: маршрут через экран уведомлений Настроек")
            step = step.copy(launchPackage = null)
        } else if (settingsEntry) {
            AppLog.i(TAG, "variant entry=settings for ${step.id} — маршрут через Настройки")
            step = step.copy(launchPackage = null)
        }
        val resolvedPkg = if (settingsEntry) {
            null
        } else {
            AdaptiveCatalog.resolveInstalledPackageForGroup(service, step.id, profile)
                ?: AdaptiveCatalog.packagesForStep(service, step.id, candidatePackages(step), profile)
                    .firstOrNull { isInstalled(it) }
        }

        // Для app-шагов фактический целевой пакет важнее статического launchPackage:
        // иначе GetApps/App Vault уходят на несуществующие market/personalassistant.
        if (step.launchPackage != null && resolvedPkg != null && resolvedPkg != step.launchPackage) {
            AppLog.i(TAG, "launch package for ${step.id}: ${step.launchPackage} -> $resolvedPkg")
            step = step.copy(launchPackage = resolvedPkg)
        }

        // П.6: Умный сброс настроек
        if (step.launchPackage == null) {
            if (!canResumeSettings) resetSettingsToRoot()
        } else if (needsHomeBeforeAppLaunch()) {
            resetToHome()
            delay(300)
        }

        // П.3: Открытие экрана через DirectIntentNavigator + discovery-скан активностей
        val legacyIntents = DirectIntentNavigator.buildIntentsForStep(service, step, resolvedPkg, profile)
        // Stage 3: после каждого интента проверяем экран по merged-маркерам и,
        // если открылся не тот экран, пробуем следующий интент.
        val verifyTexts = searchTextsFor(step)
        // Для шагов-приложений целевой экран достигается бурением, поэтому
        // проверка на этапе интента не нужна (иначе Settings-фолбэк уводит из приложения).
        // CLEAR_DATA_DECLINE идёт в App Info (APPLICATION_DETAILS_SETTINGS), а не в
        // приложение: discovery-скан приложения здесь запрещён (он открывал главный экран).
        // Вариант ОС переопределяет тип шага (Проводник: CLEAR_DATA_DECLINE → тумблер):
        // тогда нужен экран приложения, а не «Сведения о приложении», иначе шаг уходил
        // в App Info и падал (прогон rmu8lzcu9, filemanager).
        val variantToggle =
            SemanticCatalog.variantControl(step.id) == SemanticCatalog.ActionType.TOGGLE
        val isAppStep = step.launchPackage != null &&
            (step.actionType != SimpleSteps.ActionType.CLEAR_DATA_DECLINE || variantToggle)
        // Discovery: явная компонента из candidates первична, неявный LAUNCHER — фолбэк.
        val plan = ScanOrchestrator.planNavigation(
            context = service,
            pkg = resolvedPkg.takeIf { isAppStep },
            legacyIntents = legacyIntents,
            keywords = verifyTexts + step.id.split('_'),
            cache = prefs
        )
        // Причина нуля скана видна в StepDiag: тихая деградация запрещена.
        StepDiagnostics.note(
            step.id,
            "SCAN",
            "reason=${plan.scanReason} candidates=${plan.candidates.size} cached=${plan.fromCache}"
        )
        val intents = plan.orderedIntents()
        // Для шагов-приложений первым идёт launcher-интент от PackageManager: только
        // он открывает приложение стабильно (GetApps: внутренние активности сканера
        // окно не поднимают, и 4 попытки × 6 c съедали бюджет шага — прогон rmua0pt7i,
        // getapps → timeout). Остальная цепочка остаётся фолбэком.
        val pmLauncher = if (isAppStep) {
            resolvedPkg?.let { pkg ->
                runCatching { service.packageManager.getLaunchIntentForPackage(pkg) }
                    .getOrNull()
                    ?.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
            }
        } else {
            null
        }
        val attemptIntents = if (pmLauncher != null) listOf(pmLauncher) + intents else intents
        var screenOpened = false
        for (intent in attemptIntents) {
            if (cancelled) return Result(false, "cancelled")
            try {
                service.startActivity(intent)
                screenOpened = true
                if (isAppStep) {
                    // Для app-шагов: ждём целевой пакет в foreground с непустым
                    // деревом. Жёсткие 2 c не покрывали холодный старт MIUI.
                    if (awaitForegroundApp(resolvedPkg, APP_READY_WAIT_MS)) {
                        AppLog.i(TAG, "App launched: $resolvedPkg, tree=true")
                        break
                    }
                    screenOpened = false
                } else if (awaitEntryScreen(step, notifTarget, verifyTexts, CONTENT_WAIT_MS)) {
                    break
                } else if (onTargetScreen(step)) {
                    // S6: цель уже на экране (строки + маркеры шага совпали) — следующий
                    // интент не нужен, drill тоже (sys_recommendations стоял на цели).
                    AppLog.i(TAG, "intent unconfirmed but target screen already reached: ${step.id}")
                    break
                } else {
                    AppLog.w(TAG, "Экран не подтверждён после интента, пробуем следующий")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "DirectIntentNavigator failed: ${e.message}")
            }
        }

        if (!screenOpened) {
            for (intent in step.intents) {
                if (cancelled) return Result(false, "cancelled")
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    service.startActivity(intent)
                    screenOpened = true
                    break
                } catch (e: Exception) {
                    AppLog.w(TAG, "step.intents fallback failed: ${e.message}")
                }
            }
        }

        // Последний рубеж для app-шагов без CATEGORY_LAUNCHER (Безопасность, Загрузки,
        // GetApps): launcher-интент у PackageManager — он не зависит от того, нашёл ли
        // его queryIntentActivities. Без этого шаги объявлялись no_screen_opened.
        if (!screenOpened) {
            val pkg = step.launchPackage
            if (pkg != null) {
                val launch = runCatching { service.packageManager.getLaunchIntentForPackage(pkg) }
                    .getOrNull()
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { service.startActivity(launch) }
                        .onSuccess {
                            AppLog.i(TAG, "launcher intent from PackageManager for $pkg")
                            screenOpened = true
                        }
                        .onFailure { AppLog.w(TAG, "pm launcher intent failed for $pkg: ${it.message}") }
                } else {
                    AppLog.w(TAG, "no launcher intent for $pkg — шаг без точки входа")
                }
            }
        }

        if (!screenOpened) return Result(false, "no_screen_opened")

        delay(if (step.launchPackage != null) APP_LAUNCH_DELAY_MS else UI_SETTLE_DELAY_MS)
        // Mi Video (muteMediaOnLaunch): промо-ролик стартует со звуком — глушим до
        // запуска, возвращаем громкость в конце шага (restoreStepMute).
        muteForStepIfNeeded(step)
        // Закрытие видеорекламы при запуске приложения (Mi Music, GetApps и др.).
        // Реклама блокирует доступ к настройкам — ищем крестик или кнопку "Пропустить".
        if (step.launchPackage != null) {
            ConsentWallHandler.dismissVideoAdsUntilSettled(service, step.id)
        }
        // Единая точка входа для системных диалогов (welcome/permission/dismiss, Аддендум B)
        val consentHandled = handleConsentWalls(step)
        if (consentHandled > 0 && !isForegroundTarget(step, resolvedPkg)) {
            // Стена согласия могла увести с целевого экрана: один relaunch, дальше — без догадок.
            AppLog.w(TAG, "consent accepted but fg not target for ${step.id} — relaunch once")
            if (relaunchOnce(intents)) handleConsentWalls(step)
        }

        // Навигация по маршруту: авторитетный drillPath варианта ОС, иначе legacy + подсказки каталога.
        // skipDrill каталога означает «интент уже открыл целевой экран» — бурение не нужно.
        val mergedDrillPathBase = if (AdaptiveCatalog.isDrillSkipped(service, step.id)) {
            AppLog.i(TAG, "drill skipped by catalog flag id=${step.id}")
            emptyList()
        } else {
            semanticDrillPath(
                step,
                AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
            )
        }
        // S6: на старте drill цель могла быть уже открыта (в т.ч. интентом выше) —
        // бурение уровня 1 тогда лишнее и ломает уже открытый экран.
        var mergedDrillPath = mergedDrillPathBase
        if (mergedDrillPath.isNotEmpty() && onTargetScreen(step)) {
            AppLog.i(TAG, "drill skipped: already on target screen for ${step.id}")
            StepDiagnostics.note(step.id, "DRILL", "skipped_already_on_target")
            mergedDrillPath = emptyList()
        }
        // Интенты MIUI умеют открывать не тот подэкран (msa: APP_PERM_EDITOR ведёт в
        // «Конфиденциальность» → «Разрешения»). Если после интентов мы не на корне
        // Настроек и цель шага не видна — возвращаемся в корень: drill пойдёт от
        // известной точки, а не с середины чужого экрана (прогон rmu8lzcu9).
        // S5: settings-шаг без подтверждённого интента продолжает drill С ТЕКУЩЕГО экрана.
        // Re-anchor в корень Настроек — только если мы вне Настроек И целевых строк нет
        // вовсе. App-шаги (launchPackage != null) в корень не возвращаются никогда.
        if (step.launchPackage == null && mergedDrillPath.isNotEmpty() && !onTargetScreen(step)) {
            // Интент мог открыть ЦЕЛЕВОЕ приложение шага (sys_recommendations:
            // AppManagerMainActivity в SecurityCenter). Возврат в корень Настроек в этом
            // случае уничтожал уже открытый маршрут: шаг уходил в основные Настройки и
            // ничего там не находил (прогон rmuk2wjx2, шаг 2 — «интент сработал, робот
            // ушёл в Настройки»). Признак «мы в приложении интента» — пакет текущего
            // окна равен пакету первого явного интента шага.
            val intendedPkg = intents.firstOrNull { it.component != null }?.component?.packageName
            val inIntendedApp = intendedPkg != null && activePackage() == intendedPkg
            val inSettings = activePackage() == SETTINGS_PACKAGE || isSettingsRoot()
            val targetRows = verifyTexts + SemanticCatalog.screenMarkers(step.id)
            if (inSettings || inIntendedApp || screenHasAny(targetRows)) {
                AppLog.i(
                    TAG,
                    "intent unconfirmed — continuing drill from current screen (${step.id})" +
                        if (inIntendedApp) " [entry app $intendedPkg]" else ""
                )
            } else {
                AppLog.i(
                    TAG,
                    "settings step ${step.id}: left Settings, no target rows — re-anchor to root"
                )
                canResumeSettings = false
                resetSettingsToRoot()
            }
        }
        // Resume: если интент уже открыл нужный экран, начинаем с текущего уровня
        // (совпадение с целью шага отменяет бурение вовсе).
        val startLevel = if (mergedDrillPath.isEmpty()) 0 else resumeDrillIndex(step, mergedDrillPath)
        // Экран приложения готов к бурению только после загрузки его UI (GetApps:
        // сплэш «Официальный магазин от Xiaomi» → магазин с нижней навигацией).
        // Resume (startLevel>0) и шаги Настроек/CLEAR_DATA (App Info) не ждут:
        // экран уже подтверждён интентом либо приложение шага не открывается.
        if (isAppStep && mergedDrillPath.isNotEmpty()) {
            awaitAppScreenReady(step, mergedDrillPath, APP_SCREEN_WAIT_MS, startLevel)
        }
        var drillFailure: String? = null
        if (mergedDrillPath.isNotEmpty() && startLevel < mergedDrillPath.size) {
            if (startLevel > 0) AppLog.i(TAG, "drill: resume at level $startLevel for ${step.id}")
            if (step.preDrillWaitMs > 0) delay(step.preDrillWaitMs)
            for (levelIndex in startLevel until mergedDrillPath.size) {
                if (cancelled) return Result(false, "cancelled")
                // Навигационное действие — только при целостном оверлее.
                if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")
                var levelOk = drillIntoLevel(step, levelIndex, mergedDrillPath[levelIndex])
                if (!levelOk) {
                    // Поверх навигации мог встать диалог (runtime-permission целевого
                    // приложения: Mi Браузер уперся в «Разрешить доступ к фото…»,
                    // прогон rmua2sd7x): закрываем его и повторяем уровень.
                    for (retry in 1..DRILL_CONSENT_RETRIES) {
                        var progressed = handleConsentWalls(step) > 0
                        // MIUI-interstitial (Темы/GetApps: 2ГИС) кнопки закрытия в дереве
                        // не имеет — классификатор диалогов его не ведёт, закрываем BACK.
                        if (!progressed) {
                            progressed = ConsentWallHandler.dismissVideoAdsUntilSettled(
                                service, step.id
                            ) > 0
                        }
                        if (!progressed) break
                        delay(UI_SETTLE_DELAY_MS)
                        levelOk = drillIntoLevel(step, levelIndex, mergedDrillPath[levelIndex])
                        if (levelOk) break
                    }
                }
                if (!levelOk) {
                    drillFailure = "drill_failed"
                    break
                }
                delay(UI_SETTLE_DELAY_MS)
                // Диалоги могут появиться после любого навигационного действия.
                handleConsentWalls(step)
                // Целевой экран может быть достигнут раньше конца маршрута: Музыка —
                // ☰ → «Настройки» открывает экран сразу с тумблерами, а уровень
                // «Расширенные настройки» на этой версии отсутствует (дамп owner_03:
                // заголовок «Аккаунт и настройки»), прежний код падал drill_failed.
                if (onTargetScreen(step)) {
                    AppLog.i(TAG, "drill: target screen reached at level $levelIndex for ${step.id}")
                    StepDiagnostics.note(step.id, "DRILL", "target_reached level=$levelIndex")
                    break
                }
            }
        } else if (mergedDrillPath.isNotEmpty()) {
            AppLog.i(TAG, "drill skipped: already on target screen for ${step.id}")
        }

        // CLEAR_DATA_DECLINE — отдельный сценарий: очистка данных приложения
        // и отклонение приветственного экрана (Проводник и подобные).
        // CONTROL варианта ОС переопределяет legacy-тип шага (Проводник: основной путь —
        // тумблер «Получать рекомендации», CLEAR_DATA остаётся фолбэком варианта).
        if (step.actionType == SimpleSteps.ActionType.CLEAR_DATA_DECLINE &&
            SemanticCatalog.variantControl(step.id) != SemanticCatalog.ActionType.TOGGLE
        ) {
            return executeClearDataDecline(step)
        }

        // notif_*: экран уведомлений приложения подтверждается ДО тумблера. На чужом экране
        // (неоткрывшийся интент) ключевые слова шага встречаются в другом месте, и по
        // ошибке тумблится, например, «Показывать уведомления полностью» экрана блокировки.
        if (notifStep && !verifyNotifEntry(step, notifTarget, verifyTexts)) {
            return Result(false, NOT_APPLICABLE)
        }

        val result = if (drillFailure != null) {
            if (isForeignScreenForSettingsStep(step, resolvedPkg)) {
                AppLog.w(TAG, "step ${step.id}: экран другого приложения — шаг неприменим")
                StepDiagnostics.note(
                    step.id, "APPLICABILITY",
                    "foreign_screen fg=${activePackage() ?: "-"} route=${mergedDrillPath.size}"
                )
                Result(false, NOT_APPLICABLE)
            } else if (lastDrillLevelNotFound &&
                // Экран занят рекламой (Темы: интерстишл 2ГИС) — это не «экрана нет»:
                // честный drill_failed вместо ложного not_applicable.
                !ConsentWallHandler.isAdScreenNow(service) &&
                if (isAppStep) {
                    activePackage().equals(resolvedPkg, ignoreCase = true)
                } else {
                    // Settings-шаг: уровень маршрута отсутствует на экране Настроек И
                    // целевых строк шага нет вовсе (google_diagnostics: раздела
                    // «Использование и диагностика» на этой прошивке нет) — честное
                    // «неприменимо» вместо FAIL.
                    !onTargetScreen(step) && !screenHasAny(verifyTexts)
                }
            ) {
                // Приложение шага открыто, но строки последнего уровня маршрута на его
                // экранах нет вовсе (GetApps 20.4.5: в «Настройках» нет раздела
                // «Конфиденциальность») — настройки на этой версии нет: честное
                // «неприменимо» вместо FAIL (инвариант: отчёт не должен врать).
                AppLog.w(TAG, "step ${step.id}: уровень маршрута отсутствует у цели — шаг неприменим")
                StepDiagnostics.note(
                    step.id, "APPLICABILITY",
                    "drill_level_absent fg=${activePackage() ?: "-"} route=${mergedDrillPath.size}"
                )
                Result(false, NOT_APPLICABLE)
            } else {
                Result(false, drillFailure)
            }
        } else {
            // Цели варианта, обязанные отработать ДО главного тумблера: на MIUI 13 строка
            // «Обновлять через мобильный интернет» исчезает, как только карусель выключена
            // (дамп carousel_optout_wait), и после главного тумблера её уже нет.
            runPreMainExtras(step)
            findAndToggleSwitch(step)
        }
        // Фолбэк варианта (Проводник: основной путь через меню не найден → CLEAR_DATA_DECLINE).
        if (!result.success && result.reason in FALLBACK_REASONS) {
            fallbackAfterFailure(step)?.let { fallback ->
                canResumeSettings = false
                return fallback
            }
        }

        // П.6: Помечаем, что следующий шаг может переиспользовать окно
        canResumeSettings = result.success && step.id in SETTINGS_RESUMABLE_STEPS
        return result
    }

    // ─── Drill navigation ─────────────────────────────────────────────────
    /**
     * Сценарий CLEAR_DATA_DECLINE: очистить данные приложения и отклонить
     * приветственный экран при следующем запуске (Проводник и подобные).
     *
     * Основной результат определяется успехом очистки данных; отклонение
     * приветствия — best-effort и не влияет на статус шага.
     */
    // internal — для тестируемости (SimpleRunnerClearDataTest).
    internal suspend fun executeClearDataDecline(step: SimpleSteps.Step): Result {
        // Явно ждём экран сведений о приложении: на медленных устройствах кнопка
        // очистки может не успеть отрисоваться к моменту APP_LAUNCH_DELAY_MS.
        awaitScreen(
            listOf(
                "Очистить данные", "Clear data",
                "Очистить хранилище", "Clear storage",
                "Очистить", "Clear"
            ),
            timeoutMs = CONTENT_WAIT_MS
        )
        val root = service.rootInActiveWindow
        if (root == null) {
            AppLog.w(TAG, "CLEAR_DATA: нет активного окна (${step.id})")
            return Result(false, "no_active_window")
        }
        // 1. Кнопка очистки данных: сначала специфичная, затем общая.
        val specificClear = listOf(
            "Очистить данные", "Clear data",
            "Очистить хранилище", "Clear storage"
        )
        val genericClear = listOf("Очистить", "Clear")
        var clearNode = findClickableByText(root, specificClear)
            ?: findClickableByText(root, genericClear)

        // MIUI: на странице «О приложении» кнопок очистки может не быть — они
        // скрыты за пунктом «Память» (id am_storage_view на дампе прогона).
        if (clearNode == null) {
            recycleNode(root)
            val storageNode = findClickableByText(
                texts = listOf("Память", "Storage", "Хранилище", "Очистить")
            )
            if (storageNode != null) {
                val tappedStorage = tapNode(storageNode)
                recycleNode(storageNode)
                AppLog.i(TAG, "CLEAR_DATA: кнопка очистки не найдена, открываю «Память» ($tappedStorage)")
                if (tappedStorage) {
                    delay(UI_SETTLE_DELAY_MS)
                    val inner = service.rootInActiveWindow
                    if (inner != null) {
                        clearNode = findClickableByText(inner, specificClear)
                            ?: findClickableByText(inner, genericClear)
                        recycleNode(inner)
                    }
                }
            }
        } else {
            recycleNode(root)
        }
        if (clearNode == null) {
            // Фолбэк «очистить данные → Отмена на приветствии» — легаси-приём, а НЕ цель
            // шага: если недоступен и он (MIUI прячет кнопку очистки за «Память» или не
            // показывает вовсе), это не провал автоматизации — шаг честно неприменим
            // (прогон rmuikdldc: filemanager выдавал FAIL clear_button_not_found).
            AppLog.w(TAG, "CLEAR_DATA: кнопка очистки не найдена — fallback unavailable (${step.id})")
            StepDiagnostics.note(step.id, "APPLICABILITY", "clear_data_fallback_absent")
            return Result(false, NOT_APPLICABLE)
        }
        val tappedClear = tapNode(clearNode)
        recycleNode(clearNode)
        if (!tappedClear) return Result(false, "clear_button_tap_failed")
        delay(UI_SETTLE_DELAY_MS)

        // 2. Подтверждение очистки (возможен промежуточный экран + диалог).
        val confirmMarkers = (
            AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts) +
                listOf("Очистить все данные", "Clear all data", "Очистить", "Clear", "OK")
            ).distinct()
        var confirmed = false
        repeat(2) {
            if (tapButtonByMarkers(confirmMarkers, CONFIRM_RETRY_MS)) {
                confirmed = true
                delay(UI_SETTLE_DELAY_MS)
            }
        }
        if (!confirmed) AppLog.w(TAG, "CLEAR_DATA: подтверждение не найдено (${step.id})")

        // 3. Best-effort: отклонить приветственный экран после сброса данных.
        declineWelcomeScreen(step)

        return Result(true, if (confirmed) "clear_data_done" else "clear_data_tapped")
    }

    /**
     * Best-effort: перезапускает приложение и отклоняет приветственный/промо-экран
     * кнопками «Отмена»/«Пропустить»/«Не сейчас». Не влияет на результат шага.
     */
    private suspend fun declineWelcomeScreen(step: SimpleSteps.Step) {
        val pkg = step.launchPackage ?: return
        if (!isInstalled(pkg)) return
        delay(400)
        try {
            service.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "CLEAR_DATA: запуск $pkg не удался: ${e.message}")
            return
        }
        val declineMarkers = listOf(
            "Отмена", "Cancel", "Пропустить", "Skip", "Не сейчас", "Not now"
        )
        if (!tapButtonByMarkers(declineMarkers, CONTENT_WAIT_MS * 2)) {
            AppLog.w(TAG, "CLEAR_DATA: приветственный экран не отклонён (${step.id})")
        }
    }

    /**
     * Ждёт появления кликабельного узла с одним из маркеров и нажимает его.
     * В отличие от tapSystemDialogButton, не блокирует «OK»/«Отмена» —
     * в сценарии CLEAR_DATA_DECLINE эти кнопки являются целевыми.
     */
    private suspend fun tapButtonByMarkers(markers: List<String>, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cancelled) return false
            val node = findClickableByText(texts = markers)
            if (node != null) {
                val tapped = tapNode(node)
                recycleNode(node)
                return tapped
            }
            delay(250)
        }
        return false
    }

    /**
     * Навигация по одному уровню маршрута с ПРОВЕРКОЙ результата.
     *
     * Проверка идёт по «эффективному» маршруту (вариант ОС или legacy) и только по
     * пригодным для поиска текстам: прежний `firstOrNull()` брал первый элемент
     * уровня, а у уровней-меню («⚙️/Настройки») им оказывался иконочный или пустой
     * текст — `awaitScreen` гарантированно падал, и шаг завершался `drill_failed`
     * без единой строки в логе (прогон rmu8qhjhi: browser_sys, mivideo, security_sys,
     * cleaner, downloads).
     */
    // internal — для тестируемости (SimpleRunnerDrillTest).
    internal suspend fun drillIntoLevel(
        step: SimpleSteps.Step,
        levelIndex: Int,
        levelTexts: List<String>,
        path: List<List<String>> = emptyList()
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)

        val effectivePath = path.ifEmpty {
            AdaptiveCatalog.mergeDrillPath(service, step.id, step.drillPath)
        }
        val nextTexts = nextLevelVerificationTexts(effectivePath, levelIndex + 1)

        // Уровень-меню (/⚙/«Ещё»/«Дополнительно») открывается структурным поиском
        // overflow, в том числе по contentDescription, и ПРОВЕРЯЕТСЯ по пунктам меню:
        // прежний возврат true сразу после тапа продолжал маршрут с закрытого меню.
        if (isMenuLevel(levelTexts)) {
            var tapPoint: Pair<Int, Int>? = null
            if (!findAndTapOverflow(levelTexts, { x, y -> tapPoint = x to y })) return false
            if (nextTexts.isEmpty() || awaitScreen(nextTexts)) return true
            // Уровень-меню открывает список, а следующий пункт бывает НИЖЕ сгиба (Mi Браузер:
            // «Дополнительные настройки» лежат в разделе «Прочее» под лентой настроек — прогон
            // rmuef7nbf: awaitScreen пункт не видел, drill уходил в слепые тапы по позиции,
            // которые открыли чужое приложение). Прокрутку пробуем только на экране со
            // списком: на поповере прокручивать нечего.
            if (hasScrollableScreen() && scrollUntilScreenHasAny(nextTexts)) return true
            // MIUI не всегда реагирует на ACTION_CLICK: шестерёнка Mi Браузера открывает
            // настройки только тапом по центру узла (проверено на устройстве), а слепой тап
            // угла уходил в голосовой ввод (прогоны rmuef7nbf, rmuei6lp7).
            tapPoint?.let { (x, y) ->
                if (tapAt(x, y)) {
                    delay(UI_SETTLE_DELAY_MS)
                    if (nextTexts.isEmpty() || awaitScreen(nextTexts)) return true
                    if (hasScrollableScreen() && scrollUntilScreenHasAny(nextTexts)) return true
                }
            }
            val packageBeforeGesture = activePackage()
            if (performOverflowGesture(packageBeforeGesture) && awaitScreen(nextTexts)) return true
            AppLog.w(TAG, "drill: меню открыто, но '${nextTexts.first()}' не видно")
            return false
        }

        // Уже пройденный уровень: подпись уровня и следующий уровень видны одновременно
        // (sys_recommendations: экран «Приложения» открыт интентом — «Приложения» в
        // заголовке, «Все приложения» строкой ниже; повторный тап по одноимённому ряду
        // уводит на другой экран и ломает маршрут).
        // Только для ПЕРВОГО уровня: дальше подпись уровня может совпасть с пунктом уже
        // открытого меню — downloads: «Настройки» внутри overflow-меню ⋮ считалось
        // пройденным, тап не делался и шаг падал switch_not_found (прогон rmubgvwm5).
        if (levelIndex == 0 && nextTexts.isNotEmpty() &&
            screenHasAny(levelTexts) && screenHasAny(nextTexts)
        ) {
            AppLog.i(TAG, "drill: уровень '${levelTexts.firstOrNull()}' уже пройден")
            return true
        }

        val candidates = levelCandidates(levelTexts)

        if (candidates.isEmpty()) {
            // Уровень отсутствует на экране вовсе (GetApps: раздела «Конфиденциальность»
            // в нативных настройках 20.4.5 нет) — признак неприменимости, а не сбоя тапа.
            lastDrillLevelNotFound = true
            AppLog.w(
                TAG,
                "Drill level '${levelTexts.firstOrNull()}' not found, screen=[${screenText.take(120)}]"
            )
            return false
        }
        lastDrillLevelNotFound = false

        // Перебор совпавших узлов уровня: на экране бывает НЕСКОЛЬКО подписей «Настройки»,
        // и первая ведёт не туда (GetApps: профиль → нативные настройки магазина вместо
        // «гайки» с экраном «Конфиденциальность» → шаг падал drill_failed, прогон rmua2sd7x).
        var attempt = 0
        for (candidate in candidates) {
            attempt++
            if (attempt > 1 && !returnToDrillBase(screenText)) break
            val rect = Rect().also { candidate.getBoundsInScreen(it) }
            val nodeId = candidate.viewIdResourceName ?: ""
            val nodeBounds = "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
            // Один и тот же узел пробуется повторами (те же bounds/id), и только после
            // исчерпания повторов берётся следующий кандидат: иначе «ретрай» уходит на
            // соседний элемент — прогон rmumuqr53: повтор ударил по action_tabs и открыл
            // диалог «Закрытие всех вкладок» вместо настроек.
            var landed = false
            for (retry in 0..DRILL_NODE_RETRIES) {
                if (cancelled) return false
                if (retry > 0) {
                    AppLog.i(
                        TAG,
                        "drill: retry same node $retry/$DRILL_NODE_RETRIES id=$nodeId bounds=$nodeBounds"
                    )
                    StepDiagnostics.note(step.id, "DRILL", "retry_same_node id=$nodeId retry=$retry")
                }
                // База для проверки «экран сменился» — текст НЕПОСРЕДСТВЕННО перед тапом:
                // screenText снят до прокруток поиска кандидатов, и один только скролл давал
                // ложное «уровень пройден» (ux_program: прокрутили главный список Настроек
                // вместо тапа, шаг ушёл искать тумблер и падал switch_not_found, прогон rmubgvwm5).
                val beforeTapText = currentScreenText()
                val tapped = tapNode(candidate)
                if (tapped && levelLanded(nextTexts, beforeTapText)) {
                    landed = true
                    break
                }
                if (tapAt(rect.centerX(), rect.centerY())) {
                    delay(UI_SETTLE_DELAY_MS)
                    if (levelLanded(nextTexts, beforeTapText)) {
                        landed = true
                        break
                    }
                }
            }
            recycleNode(candidate)
            if (landed) return true
        }
        AppLog.w(
            TAG,
            "drill: '${levelTexts.firstOrNull()}' not passed after $attempt attempt(s) " +
                "(screen=[${screenText.take(80)}])"
        )
        return false
    }

    /** Уровень пройден: виден следующий уровень либо экран фактически сменился. */
    private suspend fun levelLanded(nextTexts: List<String>, baseScreenText: String): Boolean =
        if (nextTexts.isNotEmpty()) {
            // Переход засчитывается и по смене экрана: тексты следующего уровня бывают
            // видны только после полной отрисовки/скролла, и шаг ошибочно сообщал
            // «not passed after 1 attempt» при фактически открытом экране
            // (sys_recommendations, прогон rmubgvwm5).
            awaitScreen(nextTexts) || screenChangedSince(baseScreenText)
        } else {
            screenChangedSince(baseScreenText)
        }

    /**
     * Кандидаты-узлы уровня: первый — обычным путём (scroll-until-found и
     * горизонтальный скролл), затем остальные совпавшие кликабельные подписи.
     */
    private suspend fun levelCandidates(levelTexts: List<String>): List<AccessibilityNodeInfo> {
        val primary = findClickableByTextWithScroll(
            levelTexts,
            attempts = DRILL_SCROLL_TRIES,
            logLabel = levelTexts.firstOrNull { it.isNotBlank() }
        ) ?: findAfterHorizontalScroll(levelTexts)
        val root = service.rootInActiveWindow ?: return listOfNotNull(primary)
        val all = NodeTree.findAllInTree(root = root, predicate = { node ->
            node.isClickable && NodeTree.matchesAny(node, levelTexts)
        })
        recycleNode(root)
        val result = ArrayList<AccessibilityNodeInfo>(DRILL_LEVEL_ATTEMPTS)
        if (primary != null) result.add(primary)
        for (node in all) {
            if (result.size >= DRILL_LEVEL_ATTEMPTS) { recycleNode(node); continue }
            if (result.none { it === node || it == node }) result.add(node) else recycleNode(node)
        }
        return result
    }

    /** Возврат на исходный экран уровня после неудачной попытки альтернативного узла. */
    private suspend fun returnToDrillBase(baseScreenText: String): Boolean {
        if (!screenChangedSince(baseScreenText)) return true
        AppLog.i(TAG, "drill: возврат назад после альтернативного узла уровня")
        pressBack()
        delay(UI_SETTLE_DELAY_MS)
        return !screenChangedSince(baseScreenText)
    }

    private suspend fun pressBack() {
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    /** Экран изменился с момента снятия подписи [before] (для подтверждения уровня). */
    private fun screenChangedSince(before: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val now = collectAllText(root)
        recycleNode(root)
        return now.isNotBlank() && now != before
    }

    /** Узел целиком внутри рабочего окна: тап по краю/под навбаром эффекта не даёт. */
    private fun isFullyVisible(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val root = service.rootInActiveWindow ?: return false
        val window = Rect().also { root.getBoundsInScreen(it) }
        recycleNode(root)
        return window.width() > 0 && window.height() > 0 && window.contains(rect)
    }

    /**
     * Тексты уровня, которые годятся как маркер экрана: иконочные (⋮/⚙️) и пустые
     * отбрасываются — иначе проверка ждёт текст, которого на экране не бывает.
     */
    internal fun nextLevelVerificationTexts(path: List<List<String>>, fromIndex: Int): List<String> =
        path.getOrNull(fromIndex).orEmpty().filter { isVerifiableText(it) }

    /** Текст годится как маркер: содержит буквы/цифры (не иконочный глиф без подписи). */
    internal fun isVerifiableText(text: String): Boolean =
        text.trim().replace(EMOJI_VARIATION_SELECTOR, "").any { it.isLetterOrDigit() }

    /** Уровень-меню: подпись overflow-кнопки MIUI (⋮/⚙/«Ещё»/«Дополнительно») или иконка. */
    internal fun isMenuLevel(levelTexts: List<String>): Boolean =
        levelTexts.any { text ->
            val t = text.trim().replace(EMOJI_VARIATION_SELECTOR, "")
            t.isEmpty() ||
                MENU_LEVEL_TEXTS.any { it.replace(EMOJI_VARIATION_SELECTOR, "") == t }
        }

    /** Текст уровня — только символ-иконка (шестерёнка/три точки/гамбургер), без слов. */
    private fun isGlyphOnly(text: String): Boolean {
        val t = text.replace(EMOJI_VARIATION_SELECTOR, "").trim()
        return t.isNotEmpty() && t.none { it.isLetterOrDigit() || it.isWhitespace() }
    }

    /** Есть ли хоть один из текстов где-нибудь на текущем экране. */
    private fun screenHasAny(texts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)
        return texts.any { text ->
            text.isNotBlank() && TextMatcher.normalizedContains(screenText, text)
        }
    }

    // ─── Готовность приложения (после запуска и перед бурением) ───────────

    /**
     * Ждёт появления целевого пакета в foreground с непустым деревом.
     *
     * Единственное состояние, в котором приложение шага считается готовым к
     * навигации; иначе раннер пробует следующий интент. Прежние жёсткие 2 c
     * объявляли «App not ready» даже поднимающемуся приложению (GetApps: холодный
     * старт ~4 c), и остаток бюджета уходил на перебор кандидатов.
     */
    private suspend fun awaitForegroundApp(targetPkg: String?, timeoutMs: Long): Boolean {
        if (targetPkg == null) return false
        val attempts = (timeoutMs / APP_READY_POLL_MS).toInt().coerceAtLeast(1)
        var lastFg: String? = null
        var lastTree = false
        repeat(attempts) { attempt ->
            if (cancelled) return false
            val root = service.rootInActiveWindow
            val fg = root?.packageName?.toString()
            val hasTree = root != null && root.childCount > 0
            recycleNode(root)
            if (fg == targetPkg && hasTree) return true
            lastFg = fg
            lastTree = hasTree
            if (attempt < attempts - 1) delay(APP_READY_POLL_MS)
        }
        AppLog.w(
            TAG,
            "App not ready after ${timeoutMs}ms: fg=$lastFg target=$targetPkg tree=$lastTree, retrying"
        )
        return false
    }

    /**
     * Ждёт готовности экрана приложения по подписям первого уровня маршрута.
     *
     * Опрос, а не слепая пауза: как только уровень виден, бурение идёт сразу.
     * Исчерпание бюджета не фатально — бурение продолжается как раньше (честный
     * `drill_failed`), но факт ожидания виден в логе и диагностике.
     * Resume (startLevel>0) ожидания не требует: экран уже подтверждён.
     *
     * Возврат на главную страницу через BACK пробовали (Mi Браузер открывается
     * контентной лентой без нижней навигации, прогон rmueihkd3) и **отвергли** по
     * устройству: на Mi Browser BACK открывает диалог «Очистить историю перед
     * выходом?», уровня не даёт и оставляет шаг в модальном состоянии (прогон
     * rmufufh56). «Профиль» на ленте недостижим — шаг остаётся честным
     * `not_applicable`; нужен маршрут через UI браузера (разведка экрана), а не BACK.
     */
    internal suspend fun awaitAppScreenReady(
        step: SimpleSteps.Step,
        path: List<List<String>>,
        timeoutMs: Long,
        startLevel: Int = 0
    ): Boolean {
        if (startLevel > 0 || path.isEmpty()) return true
        val levelTexts = nextLevelVerificationTexts(path, startLevel)
        if (levelTexts.isEmpty()) return true
        if (awaitScreenTexts(levelTexts, timeoutMs)) return true
        AppLog.w(
            TAG,
            "app entry: '${levelTexts.first()}' not visible after ${timeoutMs}ms — drilling anyway"
        )
        StepDiagnostics.note(step.id, "ENTRY", "reason=timeout level=${levelTexts.first()}")
        return false
    }

    /** Опрос подписей уровня: время до появления видно в логе (готовность экрана приложения). */
    private suspend fun awaitScreenTexts(texts: List<String>, timeoutMs: Long): Boolean {
        val attempts = (timeoutMs / APP_READY_POLL_MS).toInt().coerceAtLeast(1)
        repeat(attempts) { attempt ->
            if (cancelled) return false
            if (screenHasAny(texts)) {
                AppLog.i(
                    TAG,
                    "app entry: level '${texts.first()}' visible after ~${attempt * APP_READY_POLL_MS}ms"
                )
                return true
            }
            if (attempt < attempts - 1) delay(APP_READY_POLL_MS)
        }
        return false
    }

    /** Повторный поиск узла после горизонтальной прокрутки: вкладка может быть за краем. */
    private suspend fun findAfterHorizontalScroll(texts: List<String>): AccessibilityNodeInfo? {
        if (!scrollRightOnce()) return null
        return findClickableByText(texts = texts)
    }

    /**
     * Индекс, с которого продолжать бурение:
     * - экран уже совпал с целью шага → путь исчерпан (бурение не нужно);
     * - экран совпал с текстами уровня N → начинаем с N (resume после сбоя);
     * - иначе — с начала.
     */
    internal fun resumeDrillIndex(step: SimpleSteps.Step, path: List<List<String>>): Int {
        val root = service.rootInActiveWindow ?: return 0
        // Заголовок тулбара — не уровень маршрута: прежний сбор текста ловил
        // «Конфиденциальность» из action_bar и resume уходил в середину пути
        // (ux_program, прогон rmu8lzcu9).
        val screenText = collectBodyText(root)
        recycleNode(root)
        if (screenText.isBlank()) return 0

        if (onTargetScreen(step)) return path.size

        for (levelIndex in path.indices) {
            if (path[levelIndex].any { TextMatcher.normalizedContains(screenText, it) }) return levelIndex
        }
        return 0
    }

    /** Экран уже совпал с целью шага: keyword-match И screenMarkers (как в resume). */
    private fun onTargetScreen(step: SimpleSteps.Step): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectBodyText(root)
        recycleNode(root)
        val targets = searchTextsFor(step)
        if (targets.isEmpty() || !targets.any { TextMatcher.normalizedContains(screenText, it) }) {
            return false
        }
        val markers = SemanticCatalog.screenMarkers(step.id)
        return markers.isEmpty() || markers.any { TextMatcher.normalizedContains(screenText, it) }
    }

    /**
     * Подтверждение с задержкой (msa): кнопка «Отозвать»/«ОК» становится активной
     * только после отсчёта (~10 c). Ждём включённую кнопку, тапаем, даём системе
     * 2–3 c на фактический отзыв и подтверждаем результат. Без подтверждения — fail.
     */
    internal suspend fun confirmDelayedRevoke(
        step: SimpleSteps.Step,
        confirmTexts: List<String>,
        switchTexts: List<String>
    ): Boolean {
        if (confirmTexts.isEmpty()) return false
        val waitMs = SemanticCatalog.confirmWaitMs(step.id, step.confirmWaitMs)
            .takeIf { it > 0L }?.coerceAtMost(MSA_REVOKE_WAIT_MAX_MS) ?: MSA_REVOKE_WAIT_MAX_MS
        val tapped = withTimeoutOrNull(waitMs) {
            var countdownLogged = false
            while (!cancelled) {
                val root = service.rootInActiveWindow
                val node = root?.let { findDialogConfirmButton(it, confirmTexts) }
                if (root != null) recycleNode(root)
                if (node != null) {
                    val label = buttonLabel(node)
                    if (isCountdownLabel(label)) {
                        // MIUI держит «Отозвать (N с)» неактивной до конца отсчёта:
                        // тап в это время не нажимает кнопку (прогон rmua0pt7i).
                        if (!countdownLogged) {
                            AppLog.i(TAG, "msa: waiting for revoke countdown '$label'")
                            countdownLogged = true
                        }
                    } else {
                        val ok = tapNode(node)
                        recycleNode(node)
                        if (ok) return@withTimeoutOrNull true
                    }
                }
                delay(MSA_CONFIRM_POLL_MS)
            }
            false
        } ?: false

        if (!tapped) {
            // Кнопки подтверждения нет вовсе: MIUI 13 отзывает доступ прямо по чекбоксу
            // строки («Доступ к личным данным») — подтверждаем по состоянию тумблера,
            // но только если тумблер реально найден (иначе экран чужой).
            val stateRoot = service.rootInActiveWindow
            val stateNode = stateRoot?.let { findSwitchByText(it, switchTexts) }
            val stateOk = stateNode != null &&
                SwitchFinder.isChecked(stateNode) == step.targetChecked
            recycleNode(stateNode); recycleNode(stateRoot)
            if (stateOk) {
                AppLog.i(TAG, "msa: нет кнопки подтверждения, тумблер в целевом состоянии")
                return true
            }
            AppLog.w(TAG, "msa: revoke button not enabled within ${waitMs}ms (step=${step.id})")
            return false
        }
        AppLog.i(TAG, "msa: revoke tapped, waiting up to ${MSA_REVOKE_SETTLE_MAX_MS}ms")

        // Отзыв не мгновенный, а тап по отсчётной кнопке MIUI иногда не срабатывает с
        // первого раза: на чистом устройстве (прогон rmuk1h2al) диалог остался открыт
        // через 2.5 с (`dialogGone=false`). Опрашиваем диалог, при упорном диалоге
        // повторяем тап, факт отзыва принимаем по состоянию тумблера ЛИБО по закрытию
        // диалога на целевом экране — на MIUI 13 состояние sliding_button читается не всегда.
        var dialogGone = false
        var retried = false
        val deadline = System.currentTimeMillis() + MSA_REVOKE_SETTLE_MAX_MS
        while (!cancelled && System.currentTimeMillis() < deadline) {
            delay(MSA_CONFIRM_POLL_MS)
            val confirmation = service.rootInActiveWindow
            val confirmText = ComponentVerifier.screenText(confirmation)
            val dialogNode = confirmation?.let { findDialogConfirmButton(it, confirmTexts) }
            val dialogVisible = confirmTexts.any { TextMatcher.normalizedContains(confirmText, it) }
            if (!dialogVisible) {
                dialogGone = true
                dialogNode?.let { recycleNode(it) }
                if (confirmation != null) recycleNode(confirmation)
                break
            }
            if (!retried && dialogNode != null && !isCountdownLabel(buttonLabel(dialogNode))) {
                retried = true
                AppLog.i(TAG, "msa: диалог не закрылся — повторный тап подтверждения")
                tapNode(dialogNode)
            }
            dialogNode?.let { recycleNode(it) }
            if (confirmation != null) recycleNode(confirmation)
        }
        val switchOk = verifySwitchState(step, switchTexts)
        val screenRoot = service.rootInActiveWindow
        val screenText = ComponentVerifier.screenText(screenRoot)
        if (screenRoot != null) recycleNode(screenRoot)
        val markers = SemanticCatalog.screenMarkers(step.id)
        val onTargetScreen = markers.isEmpty() ||
            markers.any { TextMatcher.normalizedContains(screenText, it) }
        val confirmed = switchOk || (dialogGone && onTargetScreen)
        if (confirmed) {
            AppLog.i(TAG, "msa: revoke confirmed step=${step.id}")
        } else {
            AppLog.w(TAG, "msa: revoke NOT confirmed step=${step.id} dialogGone=$dialogGone")
        }
        return confirmed
    }

    /**
     * Дополнительная цель варианта (второй экран/второй тумблер): «Назад» (back раз),
     * drillPath, затем тумблер или кнопка-действие.
     */
    private suspend fun executeExtraTarget(
        step: SimpleSteps.Step,
        target: SemanticCatalog.ExtraTarget
    ): Boolean {
        repeat(target.back) {
            if (cancelled) return false
            if (!awaitOverlayReadyOrPause()) return false
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        for ((levelIndex, levelTexts) in target.drillPath.withIndex()) {
            if (cancelled) return false
            if (!awaitOverlayReadyOrPause()) return false
            if (!drillIntoLevel(step, levelIndex, levelTexts, target.drillPath)) {
                // Необязательная цель (подменю есть не на всех сборках): её
                // непроходимый уровень не валит шаг.
                if (target.optional) {
                    AppLog.i(
                        TAG,
                        "optional target drill skipped step=${step.id} level=${levelIndex + 1}"
                    )
                    StepDiagnostics.note(
                        step.id, "EXTRA", "optional_drill_skipped level=${levelIndex + 1}"
                    )
                    return true
                }
                return false
            }
            delay(UI_SETTLE_DELAY_MS)
            handleConsentWalls(step)
        }
        if (target.itemTexts.isEmpty()) return target.optional
        // Строк-целей в одной цели может быть несколько (Карусель: «Реклама на Экране
        // блокировки» + «Включить персонализированные услуги» в том же подменю) —
        // каждая проверяется и гасится отдельно, со своим логом результата.
        var allRowsOk = true
        for (text in target.itemTexts) {
            if (cancelled) return false
            if (!toggleExtraRow(step, target, text)) allRowsOk = false
        }
        if (!allRowsOk && target.control == SemanticCatalog.ActionType.TAP_CONFIRM) {
            // Экран без тумблера: кнопка-действие (Google «Реклама»).
            return tapActionButton(step, target.itemTexts).success
        }
        return allRowsOk
    }

    /**
     * Одна строка-цель: состояние читается до тапа, тапаем только при несовпадении,
     * результат пишется в лог (`extra target toggled|already_off|absent step=… text='…'`).
     * Исчезнувшая строка не считается сбоем: на повторном прогоне зависимая строка
     * пропадает вместе с главным тумблером.
     */
    private suspend fun toggleExtraRow(
        step: SimpleSteps.Step,
        target: SemanticCatalog.ExtraTarget,
        text: String
    ): Boolean {
        handleConsentWalls(step)
        val root = service.rootInActiveWindow ?: return false
        recycleNode(root)
        // Строку ищем прокруткой: она может стоять на кромке экрана (см. KDoc
        // [findSwitchByTextWithScroll]).
        val switch = findSwitchByTextWithScroll(listOf(text), logLabel = "${step.id}:$text")
        if (switch == null) {
            // «Строки нет» ≠ «цель выполнена»: браузерный шаг отчитывался `toggled`,
            // когда прокрутка не нашла тумблер, а он остался включённым (прогон
            // rmuod5cmm). Законное исчезновение — только при уже выключенном главном
            // тумблере: тогда зависимая строка пропадает вместе с функцией.
            if (mainSwitchIsOff(step)) {
                AppLog.i(TAG, "extra target absent_main_off step=${step.id} text='$text'")
                StepDiagnostics.note(step.id, "EXTRA", "absent text=$text main_off=true")
                return true
            }
            if (target.optional) {
                AppLog.i(TAG, "extra target optional_absent step=${step.id} text='$text'")
                StepDiagnostics.note(step.id, "EXTRA", "absent_optional text=$text")
                return true
            }
            AppLog.w(TAG, "extra target absent step=${step.id} text='$text' main_off=false")
            StepDiagnostics.note(step.id, "EXTRA", "absent text=$text main_off=false")
            return false
        }
        val checked = SwitchFinder.isChecked(switch)
        // Цель может требовать включения, поэтому целевое состояние берём у цели,
        // а не у шага.
        val needed = checked != target.targetChecked
        if (!needed) {
            recycleNode(switch); recycleNode(root)
            AppLog.i(TAG, "extra target already_off step=${step.id} text='$text'")
            return true
        }
        val tapped = tapNode(switch)
        recycleNode(switch); recycleNode(root)
        if (!tapped) {
            AppLog.w(TAG, "extra target tap failed step=${step.id} text='$text'")
            return false
        }
        delay(600)
        // У цели бывает свой диалог: отказ («Отмена» на «Добавить в выбранные фото?»)
        // ИЛИ подтверждение («Выключить карусель экрана блокировки?» → «Подтвердить»,
        // дамп car_dlg2.xml). Взаимоисключающе: на диалоге подтверждения «Отмена»
        // отменила бы саму цель, поэтому confirmTexts цели отключает decline-путь.
        if (target.confirmTexts.isNotEmpty()) {
            if (tapConfirmIfNeeded(step, target.confirmTexts) == ConfirmOutcome.FAILED) {
                AppLog.w(TAG, "extra target confirm failed step=${step.id} text='$text'")
                return false
            }
        } else if (target.drillPath.isNotEmpty()) {
            // Вложенный экран (подменю «Политика конфиденциальности»): диалог-заглушка
            // («Добавить в выбранные фото?») принадлежит строкам КОРНЕВОГО экрана, а
            // ожидание по 2.5 с на строку съедало бюджет шага карусели с пятью
            // тумблерами (прогон rmuoh815k: 20 с не хватало).
            AppLog.i(TAG, "extra target nested: decline wait skipped step=${step.id} text='$text'")
        } else {
            // Диалог-заглушка после тапа цели (Карусель обоев: «Отмена»).
            tapToggleDeclineIfNeeded(step)
        }
        // Без подтверждения фактического состояния цель «выполнено» не объявляет.
        if (!verifyExtraTargetState(target, listOf(text))) {
            AppLog.w(TAG, "extra target not verified step=${step.id} text='$text'")
            return false
        }
        AppLog.i(TAG, "extra target toggled step=${step.id} text='$text'")
        return true
    }

    /**
     * Фолбэк варианта при неудаче drill/switch/verify (например Проводник:
     * основной путь через меню не найден → CLEAR_DATA_DECLINE).
     */
    private suspend fun fallbackAfterFailure(step: SimpleSteps.Step): Result? {
        val action = SemanticCatalog.fallbackAction(step.id) ?: return null
        if (action != FALLBACK_ACTION_CLEAR_DATA) return null
        AppLog.i(TAG, "variant fallback action=$action step=${step.id}")
        return executeClearDataDecline(step)
    }

    /** Целевой экран в foreground: приложение шага или Настройки для системных шагов. */
    private fun isForegroundTarget(step: SimpleSteps.Step, resolvedPkg: String?): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val fg = root.packageName?.toString()
        recycleNode(root)
        val expected = resolvedPkg ?: step.launchPackage
        return if (expected != null) fg == expected else fg == SETTINGS_PACKAGE
    }

    /** Один relaunch интентов, если согласие увело с целевого экрана. */
    private suspend fun relaunchOnce(intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (cancelled) return false
            try {
                service.startActivity(intent)
                delay(CONTENT_WAIT_MS)
                return true
            } catch (e: Exception) {
                AppLog.w(TAG, "relaunch after consent failed: ${e.message}")
            }
        }
        return false
    }

    // ── Применимость шага: вход notif_* и чужой экран ────────────────────

    /**
     * Пакет-цель notif_*-шага: подпись приложения на экране уведомлений — признак
     * того, что открыт именно целевой экран, а не похожий чужой.
     */
    internal fun resolveNotifTarget(step: SimpleSteps.Step): String? {
        val candidates =
            (listOfNotNull(SemanticCatalog.launchPackage(step.id)) + candidatePackages(step))
                .distinct()
        return candidates.firstOrNull { isInstalled(it) } ?: candidates.firstOrNull()
    }

    /**
     * Вход notif_*-шага подтверждён: открыт экран уведомлений целевого приложения.
     * Если нет — повторяем собственный интент шага (EXTRA_APP_PACKAGE = цель): это
     * тот же интент, что MIUI открывает по «Уведомления» в сведениях о приложении.
     */
    private suspend fun verifyNotifEntry(
        step: SimpleSteps.Step,
        notifTarget: String?,
        keywords: List<String>
    ): Boolean {
        if (isAppNotificationScreen(notifTarget, keywords)) return true
        AppLog.w(TAG, "notif entry: экран '${step.id}' не подтверждён — повтор интента")
        StepDiagnostics.note(step.id, "NOTIF", "entry_retry pkg=${notifTarget ?: "-"}")
        if (!retryNotifIntent(step, notifTarget)) {
            StepDiagnostics.note(
                step.id, "NOTIF", "entry_intent_unavailable pkg=${notifTarget ?: "-"}"
            )
            return false
        }
        val ok = awaitEntryScreen(step, notifTarget, keywords, CONTENT_WAIT_MS)
        if (!ok) {
            StepDiagnostics.note(
                step.id, "NOTIF", "entry_not_verified pkg=${notifTarget ?: "-"}"
            )
        }
        return ok
    }

    /** Интент шага для конкретного пакета (EXTRA_APP_PACKAGE) — повтор входа notif_*. */
    private fun retryNotifIntent(step: SimpleSteps.Step, notifTarget: String?): Boolean {
        notifTarget ?: return false
        val intent = step.intents.firstOrNull {
            it.getStringExtra(Settings.EXTRA_APP_PACKAGE) == notifTarget
        } ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(intent)
            true
        }.onFailure { AppLog.w(TAG, "notif entry retry failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Экран уведомлений приложения: есть маркеры шага И подпись целевого приложения.
     * Вторая проверка обязательна — на «Заблокированном экране» MIUI ключевые слова
     * шага тоже встречаются («Показывать уведомления полностью»), а подписи
     * приложения там нет (прогон rmu8qhjhi: notif_appvault, notif_getapps).
     */
    internal fun isAppNotificationScreen(pkg: String?, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val root = service.rootInActiveWindow ?: return false
        val screenText = collectAllText(root)
        recycleNode(root)
        if (keywords.none { TextMatcher.normalizedContains(screenText, it) }) return false
        val label = appLabel(pkg)
        return label.isBlank() || TextMatcher.normalizedContains(screenText, label)
    }

    /** Подпись приложения (кэш в пределах прогона): «Темы», «GetApps», «Лента виджетов». */
    private fun appLabel(pkg: String?): String {
        pkg ?: return ""
        appLabelCache[pkg]?.let { return it }
        val label = runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull().orEmpty()
        appLabelCache[pkg] = label
        return label
    }

    /**
     * Проверка входного экрана: для notif_* — экран уведомлений приложения, для
     * остальных — любой из маркеров. Неподтверждённый вход дальше не разбирается.
     */
    private suspend fun awaitEntryScreen(
        step: SimpleSteps.Step,
        notifTarget: String?,
        markers: List<String>,
        timeoutMs: Long
    ): Boolean {
        val start = System.currentTimeMillis()
        while (true) {
            if (cancelled) return false
            if (step.id.startsWith(NOTIF_PREFIX)) {
                if (isAppNotificationScreen(notifTarget, markers)) return true
            } else if (screenHasAny(markers)) {
                return true
            }
            if (System.currentTimeMillis() - start >= timeoutMs) return false
            delay(ENTRY_POLL_MS)
        }
    }

    /**
     * Шаг с маршрутом через Настройки оказался в чужом приложении (лаунчер вместо
     * Настроек) и целевых строк там нет: экрана шага на этом устройстве не существует.
     * Возвращаем `not_applicable` (skip), а не FAIL — отчёт не должен врать
     * (home_suggestions/appvault_* на POCO Launcher, прогон rmu8qhjhi).
     */
    internal fun isForeignScreenForSettingsStep(
        step: SimpleSteps.Step,
        resolvedPkg: String?
    ): Boolean {
        if (resolvedPkg != null || step.launchPackage != null) return false
        val fg = activePackage() ?: return false
        if (fg == SETTINGS_PACKAGE) return false
        val targets = searchTextsFor(step) + SemanticCatalog.itemTexts(step.id)
        return targets.isNotEmpty() && !screenHasAny(targets)
    }

    /**
     * Ведро причины пропуска: от него зависит текст отчёта. Смешивать «настройки нет
     * на устройстве», «её нет в лаунчере» и «робот не нашёл» нельзя — отчёт врал бы.
     */
    enum class SkipKind { NOT_ON_DEVICE, LAUNCHER_ABSENT, UNRESOLVED }

    /** Шаг настраивает лаунчер: его строки живут в настройках рабочего стола. */
    private fun isLauncherSettingsStep(step: SimpleSteps.Step): Boolean =
        step.id == "home_suggestions"

    // ─── Switch finding and toggling ──────────────────────────────────────
    private suspend fun findAndToggleSwitch(step: SimpleSteps.Step): Result {
        val mergedSearchTexts = searchTextsFor(step)
        if (mergedSearchTexts.isEmpty()) return Result(false, "no_switch")

        if (!awaitScreen(mergedSearchTexts)) {
            // Экран шага не открылся: если маркеров целевого экрана тоже нет, это не провал
            // автоматизации, а отсутствие настройки на прошивке (ux_program: пункта
            // «Программа улучшения качества» нет вовсе — прогон rmubgvwm5, отчёт врал FAIL).
            // Целевые строки могли оказаться ниже сгиба (Mi Music: тумблеры рекламы живут
            // в разделе «Дополнительные настройки» экрана «Расширенные настройки» — прогон
            // rmueebsvu, шаг зря объявлялся not_applicable), поэтому сначала прокручиваем.
            val markers = SemanticCatalog.screenMarkers(step.id)
            if (!scrollUntilScreenHasAny(mergedSearchTexts + markers)) {
                if (markers.isEmpty() || screenHasAny(markers)) {
                    return Result(false, "switch_not_found")
                }
                AppLog.w(TAG, "step ${step.id}: экран шага не открылся, маркеров нет — шаг неприменим")
                StepDiagnostics.note(step.id, "APPLICABILITY", "screen_markers_absent")
                // Настройки лаунчера подаём отдельной причиной: отчёт скажет «настройки нет
                // в лаунчере», а не «нет на устройстве» и не «робот не нашёл».
                val absentReason =
                    if (isLauncherSettingsStep(step)) LAUNCHER_ABSENT else NOT_APPLICABLE
                return Result(false, absentReason)
            }
        }

        var currentRoot: AccessibilityNodeInfo? =
            service.rootInActiveWindow ?: return Result(false, "no_root_window")
        var switchNode: AccessibilityNodeInfo? = findSwitchByText(currentRoot, mergedSearchTexts)

        // Fallback: строка-цель может жить ниже сгиба даже когда экран шага открыт
        // (Cleaner: «Получать рекомендации» у нижней кромки; App Vault: за списком
        // карточек). Крутим до появления строки, но каждую прокрутку проверяем на
        // фактический сдвиг экрана: без сдвига список закончился или жест не дошёл —
        // тогда честный `switch_not_found` вместо сжигания бюджета шага.
        // Root не ресайклим, если нашли ноду (иначе IllegalStateException у switchNode).
        if (switchNode == null && mergedSearchTexts.isNotEmpty()) {
            recycleNode(currentRoot)
            currentRoot = null
            var stalled = 0
            for (attempt in 0 until ROW_SCROLL_ATTEMPTS) {
                if (cancelled) return Result(false, "cancelled")
                AppLog.i(
                    TAG,
                    "switch: scroll attempt ${attempt + 1}/$ROW_SCROLL_ATTEMPTS " +
                        "for '${mergedSearchTexts.firstOrNull()}'"
                )
                val changed = scrollDownVerified()
                val root = service.rootInActiveWindow ?: return Result(false, "no_root_window")
                switchNode = findSwitchByText(root, mergedSearchTexts)
                if (switchNode != null) {
                    currentRoot = root
                    break
                }
                recycleNode(root)
                stalled = if (changed) 0 else stalled + 1
                if (stalled >= ROW_SCROLL_STALL_LIMIT) {
                    AppLog.w(
                        TAG,
                        "switch: scroll stalled after ${attempt + 1} attempt(s) " +
                            "for '${mergedSearchTexts.firstOrNull()}'"
                    )
                    StepDiagnostics.note(
                        step.id,
                        "SCROLL",
                        "stalled attempts=${attempt + 1} text=" + mergedSearchTexts.firstOrNull()
                    )
                    break
                }
            }
        }

        val tapTexts = tapFallbackTextsFor(step)
        // Гейт уверенности: keyword-match И переключатель (или tap-fallback) И screenMarkers.
        val screenRoot = currentRoot ?: service.rootInActiveWindow
        val screenText = ComponentVerifier.screenText(screenRoot)
        if (currentRoot == null) recycleNode(screenRoot)
        val decision = SemanticGate.decide(
            keywords = mergedSearchTexts,
            screenText = screenText,
            screenMarkers = SemanticCatalog.screenMarkers(step.id),
            switchFound = switchNode != null,
            hasTapFallback = tapTexts.isNotEmpty()
        )
        SemanticGate.log(step.id, decision)
        if (!decision.act) {
            recycleNode(switchNode)
            recycleNode(currentRoot)
            // Никаких угадываний: пропускаем шаг, ложные нажатия недопустимы.
            return Result(false, "low_confidence")
        }

        if (switchNode == null) {
            recycleNode(currentRoot)
            // Variant-aware фолбэк: на экранах без тумблера (Global: Google «Реклама»
            // с кнопкой «Удалить рекламный идентификатор») тапаем кнопку-действие.
            if (tapTexts.isNotEmpty()) return tapActionButton(step, tapTexts)
            return Result(false, "switch_not_found")
        }

        // Тумблер мог найтись ЗА нижней границей экрана (Mi Видео: строка «Онлайн-
        // рекомендации» в самом низу, bounds уходят под навбар) — тап по невидимой
        // области не переключает, шаг падал `verify_failed` (прогон rmua2sd7x).
        // Ненайденный тумблер обработан гейтом/фолбэком выше — здесь он не null.
        var targetSwitch: AccessibilityNodeInfo = switchNode
        for (attempt in 0 until SWITCH_FALLBACK_SCROLLS) {
            if (isFullyVisible(targetSwitch)) break
            if (cancelled) {
                recycleNode(targetSwitch); recycleNode(currentRoot)
                return Result(false, "cancelled")
            }
            AppLog.i(
                TAG,
                "switch: scroll to off-screen row attempt ${attempt + 1}/$SWITCH_FALLBACK_SCROLLS " +
                    "for '${mergedSearchTexts.firstOrNull()}'"
            )
            scrollDownOnce()
            val scrolledRoot = service.rootInActiveWindow ?: break
            val again = findSwitchByText(scrolledRoot, mergedSearchTexts)
            recycleNode(scrolledRoot)
            if (again != null && again !== targetSwitch) {
                recycleNode(targetSwitch)
                targetSwitch = again
            }
        }

        // Состояние читается у АКТУАЛЬНОГО узла (после прокрутки это может быть
        // другой экземпляр той же строки).
        val hit = SwitchFinder.describe(targetSwitch, mergedSearchTexts.first())
        val isChecked = hit.checkedBefore
        val text = hit.label
        val desc = hit.desc
        val bounds = hit.bounds

        if (isChecked == step.targetChecked) {
            // Доказательство вердикта: подпись узла, его рамка и состояние маркеров экрана.
            // Без этого «уже выключено» неотличимо от ложного успеха (прогон rmuk44un7).
            val markers = SemanticCatalog.screenMarkers(step.id)
            val markerState = when {
                markers.isEmpty() -> "none"
                currentRoot == null -> "unknown"
                else -> {
                    val screenText = ComponentVerifier.screenText(currentRoot)
                    if (markers.any { TextMatcher.normalizedContains(screenText, it) }) "ok" else "absent"
                }
            }
            StepDiagnostics.note(
                step.id, "VERDICT",
                "already_" + (if (step.targetChecked) "done" else "off") +
                    " checked=" + isChecked +
                    " label=" + text + " bounds=[" + bounds.left + "," + bounds.top + "," +
                    bounds.right + "," + bounds.bottom + "] markers=" + markerState
            )
            recycleNode(targetSwitch); recycleNode(currentRoot)
            // Дополнительные строки варианта НЕ зависят от главного тумблера: у Mi Music
            // главный («Показывать рекламу») часто уже выключен, а «Персональные
            // рекомендации» — нет; прежний ранний return оставлял их включёнными.
            runExtraTargets(step, SemanticCatalog.extraTargetsAfterMain(step.id))
            return Result(true, if (step.targetChecked) "already_done" else "already_off")
        }

        // checked_before фиксируется в снапшоте отката в момент тумблера (блок 6).
        // ROM может ЗАБЛОКИРОВАТЬ тумблер (MIUI: «Показывать уведомления» Ленты
        // виджетов — `enabled=false` у строки, дамп appvault_notif_disabled): тапать
        // бессмысленно, шаг не выполнить — честный skip вместо verify_failed.
        val tapRow = clickableAncestorOrSelf(targetSwitch) ?: targetSwitch
        val toggleEnabled = targetSwitch.isEnabled || tapRow.isEnabled
        if (tapRow !== targetSwitch) recycleNode(tapRow)
        if (!toggleEnabled) {
            AppLog.w(TAG, "switch disabled by rom for ${step.id} — switch_disabled")
            StepDiagnostics.note(step.id, "APPLICABILITY", "switch_disabled")
            recycleNode(targetSwitch); recycleNode(currentRoot)
            return Result(false, SWITCH_DISABLED_BY_ROM)
        }
        recordCheckedBefore(step.id, isChecked)

        // S2: резерв бюджета на тап+verify. Если остатка мало — не тапаем вслепую:
        // шаг падает с причиной budget_exhausted вместо глухого timeout.
        if (remainingBudgetMs() < TOGGLE_BUDGET_RESERVE_MS) {
            AppLog.w(
                TAG,
                "budget exhausted before toggle: step=${step.id} remaining=${remainingBudgetMs()}ms"
            )
            StepDiagnostics.note(
                step.id, "TOGGLE", "budget_exhausted remaining=${remainingBudgetMs()}ms"
            )
            recycleNode(targetSwitch); recycleNode(currentRoot)
            return Result(false, "budget_exhausted")
        }

        if (!tapNode(targetSwitch)) {
            recycleNode(targetSwitch); recycleNode(currentRoot)
            return Result(false, "tap_failed")
        }
        recycleNode(targetSwitch); recycleNode(currentRoot)

        delay(600)
        // Диалог-заглушка MIUI сразу после тапа (Карусель обоев: «Нет, спасибо» / «Хорошо»):
        // отказ — часть шага, иначе тумблер остаётся включённым и шаг падает verify_failed.
        tapToggleDeclineIfNeeded(step)
        postToggleConfirm(step, mergedSearchTexts)?.let { return it }

        // П.3: Дополнительные переключатели. В списке лежат переводы одной и той же строки
        // на все локали: поиск тумблера по отсутствующей на экране подписи — это полный
        // обход дерева на каждую локаль (Mi Music: 5 бесполезных проходов ≈ 4 с из бюджета
        // шага, прогон rmuef7nbf — шаг завершился timeout). Фильтруем по тексту экрана.
        val mergedAdditionalToggles =
            AdaptiveCatalog.mergeAdditionalToggles(service, step.id, step.additionalToggles)
        if (mergedAdditionalToggles.isNotEmpty()) {
            val screenText = currentScreenText()
            for (toggleText in mergedAdditionalToggles) {
                if (cancelled) break
                if (!TextMatcher.normalizedContains(screenText, toggleText)) continue
                val addRoot = service.rootInActiveWindow ?: continue
                val addNode = findSwitchByText(addRoot, listOf(toggleText))
                if (addNode != null && SwitchFinder.isChecked(addNode) != step.targetChecked) tapNode(addNode)
                recycleNode(addNode); recycleNode(addRoot)
                delay(400)
            }
        }

        // Вариант каталога может требовать второй экран (Браузер: «Показывать рекламу»
        // → назад → «Персональные рекомендации»).
        runExtraTargets(step, SemanticCatalog.extraTargetsAfterMain(step.id))

        // Диалоги-заглушки после тумблера (Карусель: «Нет, спасибо»).
        handleConsentWalls(step)

        // Вердикт с обоими состояниями: checked_before взят в момент тумблера (он же уходит
        // в снапшот отката), checked_after — фактическое чтение после тапа (null = строка
        // исчезла вместе с функцией, что для этих экранов норма).
        val afterState = readSwitchState(step)
        StepDiagnostics.note(
            step.id,
            "VERDICT",
            "toggled checked_before=$isChecked checked_after=$afterState" +
                " label='$text' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]" +
                " markers=ok"
        )
        AppLog.i(
            TAG,
            "toggled: text='$text' desc='$desc' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] step=${step.id}"
        )
        
        return Result(true, "toggled")
    }

    /**
     * Пост-тап подтверждение переключения. Для DELAYED_CONFIRM (msa) диалог отсчёта
     * перекрывает экран, тумблер исчезает из дерева, а правило честности verify
     * («узла нет» при видимом confirm-тексте = провал) роняло шаг `verify_failed`
     * ещё ДО попытки отозвать. Поэтому для msa сначала подтверждаем отзыв, и только
     * его провал — `revoke_not_confirmed` (прогон rmupuud3s). Для остальных шагов
     * порядок прежний: подтверждение → verify (+один retry) → финальное подтверждение.
     * Возвращает null, если шаг можно продолжать (extra targets).
     */
    internal suspend fun postToggleConfirm(
        step: SimpleSteps.Step,
        texts: List<String>
    ): Result? {
        if (SemanticCatalog.sequenceKind(step.id) == SemanticCatalog.SequenceKind.DELAYED_CONFIRM) {
            // msa: кнопка отзыва активна только после отсчёта; verify внутри самого
            // подтверждения (по состоянию тумблера ИЛИ по закрытию диалога).
            if (!confirmDelayedRevoke(step, confirmTextsFor(step), texts)) {
                return Result(false, "revoke_not_confirmed")
            }
            return null
        }
        // Диалог подтверждения появляется сразу после тапа («Отключение Ленты виджетов:
        // Вы не сможете использовать Ленту виджетов… Отключить её?» — дамп owner_06).
        // Подтверждаем ДО проверки состояния, иначе verify не видит переключения и шаг
        // уходит в retry → verify_failed.
        if (tapConfirmIfNeeded(step) == ConfirmOutcome.FAILED) {
            return Result(false, "confirm_not_closed")
        }
        if (!verifySwitchState(step, texts)) {
            val retryRoot = service.rootInActiveWindow ?: return Result(false, "no_root_window")
            val retryNode = findSwitchByText(retryRoot, texts)
            if (retryNode != null) tapNode(retryNode)
            recycleNode(retryNode); recycleNode(retryRoot)
            delay(600)
            tapToggleDeclineIfNeeded(step)
            if (tapConfirmIfNeeded(step) == ConfirmOutcome.FAILED) {
                return Result(false, "confirm_not_closed")
            }
            if (!verifySwitchState(step, texts)) return Result(false, "verify_failed")
        }
        // Незакрытый после тапа диалог — провал шага, а не «почти получилось»:
        // открытый диалог уносит следующий шаг в чужой экран.
        if (tapConfirmIfNeeded(step) == ConfirmOutcome.FAILED) {
            return Result(false, "confirm_not_closed")
        }
        return null
    }

    /**
     * Фолбэк для экранов без переключателя: тапает кликабельную кнопку-действие
     * (например, «Удалить рекламный идентификатор» на Google-экране «Реклама»)
     * и обрабатывает подтверждение. Вызывается только когда тумблер не найден
     * и настроены tapFallbackTexts.
     */
    private suspend fun tapActionButton(step: SimpleSteps.Step, tapTexts: List<String>): Result {
        val node = findClickableByTextWithScroll(tapTexts)
            ?: return Result(false, "switch_not_found")
        if (!tapNode(node)) {
            recycleNode(node)
            return Result(false, "tap_failed")
        }
        recycleNode(node)
        delay(600)
        if (tapConfirmIfNeeded(step) == ConfirmOutcome.FAILED) {
            return Result(false, "confirm_not_closed")
        }
        return Result(true, "tapped_fallback")
    }

    /**
     * Дополнительные цели варианта в порядке каталога. Сбой цели не меняет вердикт шага,
     * но обязан быть виден в логе и диагностике: молчаливого «всё ок» не бывает.
     */
    private suspend fun runExtraTargets(
        step: SimpleSteps.Step,
        targets: List<SemanticCatalog.ExtraTarget>
    ) {
        for (target in targets) {
            if (cancelled) return
            if (!executeExtraTarget(step, target)) {
                AppLog.w(
                    TAG,
                    "extra target failed step=" + step.id + " text=" + target.itemTexts.firstOrNull()
                )
                StepDiagnostics.note(
                    step.id,
                    "EXTRA",
                    "target_failed text=" + target.itemTexts.firstOrNull()
                )
            }
        }
    }

    /**
     * Фактическое состояние дополнительной цели после тапа. Строка, исчезнувшая с экрана
     * (карусель выключена — зависимая строка пропала), считается достигнутой целью.
     */
    private suspend fun verifyExtraTargetState(
        target: SemanticCatalog.ExtraTarget,
        texts: List<String> = target.itemTexts,
        /** Строка была найдена до тапа: её исчезновение после тапа = цель достигнута. */
        foundBeforeTap: Boolean = true
    ): Boolean {
        repeat(SWITCH_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SWITCH_VERIFY_RETRY_DELAY_MS)
            val root = service.rootInActiveWindow ?: return false
            // Только настоящий переключатель: узел-омоним `android:id/title`
            // (браузер «Безопасность») не тумблер, и его состояние не считается.
            val node = findSwitchByText(root, texts)?.takeIf { isSwitchLike(it) }
            val state = node?.let { SwitchFinder.isChecked(it) }
            recycleNode(node); recycleNode(root)
            if (state == target.targetChecked) return true
            // Пропавшая строка = цель достигнута ТОЛЬКО если она была до тапа; иначе
            // это снова «не нашли, а отчитались успехом» (прогон rmuod5cmm).
            if (state == null && foundBeforeTap) return true
        }
        return false
    }

    /**
     * Кнопка-отказа диалога, который оболочка показывает ПОСЛЕ главного тумблера
     * (Карусель обоев: «Нет, спасибо»). Тапается только кнопка из
     * [SemanticCatalog.toggleDeclineTexts]: «Хорошо» не нажимаем никогда.
     */
    private suspend fun tapToggleDeclineIfNeeded(step: SimpleSteps.Step) {
        val texts = SemanticCatalog.toggleDeclineTexts(step.id)
        if (texts.isEmpty()) return
        val deadline = System.currentTimeMillis() +
            minOf(TOGGLE_DECLINE_WAIT_MS, maxOf(0L, remainingBudgetMs() - 1000L))
        while (!cancelled) {
            val root = service.rootInActiveWindow
            val node = root?.let { findClickableByText(it, texts) }
            if (root != null) recycleNode(root)
            if (node != null) {
                val label = buttonLabel(node)
                val tapped = tapNode(node)
                recycleNode(node)
                AppLog.i(TAG, "toggle decline: tapped " + label + " ok=" + tapped + " step=" + step.id)
                if (tapped) {
                    delay(CONFIRM_SETTLE_MS)
                    return
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                AppLog.i(TAG, "toggle decline: dialog not found for " + step.id)
                return
            }
            delay(CONFIRM_RETRY_MS)
        }
    }

    /**
     * Исход обработки диалога подтверждения. Смешивать «диалога не было» и «диалог
     * не закрылся» нельзя: второе означает провал шага ([Result] с
     * `confirm_not_closed`), открытый диалог уносит следующий шаг в чужой экран.
     */
    internal enum class ConfirmOutcome { ABSENT, CLOSED, FAILED }

    /**
     * Тапает кнопку подтверждения диалога, если он появился (confirmTexts шага).
     *
     * Правила:
     * - кнопка ищется по СВОЕЙ подписи ([findDialogConfirmButton]: точное совпадение
     *   подписи узла, затем button-роль) — заголовок диалога («Отключить
     *   рекомендации?») кнопкой не считается;
     * - узел без собственной подписи (контейнер диалога) НЕ тапается: слепой тап по его
     *   центру диалог не закрывает, зато даёт ложное «подтверждено» (прогон rmulhb4yq:
     *   `confirm: tapped 'null'` ×2) — пишем `confirm: label unresolved`;
     * - не вышло с первого раза — ровно один повтор, затем честный провал;
     * - диалог, оставшийся открытым после тапа, — тоже провал.
     */
    private suspend fun tapConfirmIfNeeded(step: SimpleSteps.Step): ConfirmOutcome =
        tapConfirmIfNeeded(step, confirmTextsFor(step))

    /**
     * То же, но с явным набором кнопок подтверждения: диалог бывает у ОТДЕЛЬНОЙ цели
     * шага («Проведите вправо по Экрану блокировки» → «Выключить карусель экрана
     * блокировки?» → «Подтвердить»), и его кнопки не совпадают с confirmTexts шага.
     */
    private suspend fun tapConfirmIfNeeded(
        step: SimpleSteps.Step,
        texts: List<String>
    ): ConfirmOutcome {
        // У DELAYED_CONFIRM (msa) свой путь: кнопка активируется только после отсчёта,
        // здесь она не кликабельна и только жгла бы повторы.
        if (SemanticCatalog.sequenceKind(step.id) == SemanticCatalog.SequenceKind.DELAYED_CONFIRM) {
            return ConfirmOutcome.ABSENT
        }
        val mergedConfirmTexts = texts.filter { it.isNotBlank() }.distinct()
        if (mergedConfirmTexts.isEmpty()) return ConfirmOutcome.ABSENT
        val waitMs = SemanticCatalog.confirmWaitMs(step.id, step.confirmWaitMs)
        if (waitMs > 0) delay(waitMs)
        for (attempt in 1..CONFIRM_ATTEMPTS) {
            if (cancelled) return ConfirmOutcome.ABSENT
            val confirmRoot = service.rootInActiveWindow
            val confirmNode = findDialogConfirmButton(confirmRoot, mergedConfirmTexts)
            if (confirmRoot != null) recycleNode(confirmRoot)
            if (confirmNode == null) {
                // Диалога нет вовсе — это не провал: у большинства шагов подтверждения
                // не бывает. Ждём только на первой попытке (диалог мог отрисоваться позже).
                if (attempt < CONFIRM_ATTEMPTS) {
                    delay(CONFIRM_RETRY_MS)
                    continue
                }
                AppLog.i(TAG, "confirm: dialog not found step=${step.id}")
                return ConfirmOutcome.ABSENT
            }
            val label = buttonLabel(confirmNode)
            if (label == null) {
                recycleNode(confirmNode)
                AppLog.w(TAG, "confirm: label unresolved step=${step.id} attempt=$attempt")
                StepDiagnostics.note(step.id, "CONFIRM", "label_unresolved attempt=$attempt")
                if (attempt < CONFIRM_ATTEMPTS) {
                    delay(CONFIRM_RETRY_MS)
                    continue
                }
                return ConfirmOutcome.FAILED
            }
            val tapped = tapNode(confirmNode)
            recycleNode(confirmNode)
            AppLog.i(TAG, "confirm: tapped '$label' ok=$tapped step=${step.id}")
            if (!tapped) {
                if (attempt < CONFIRM_ATTEMPTS) {
                    delay(CONFIRM_RETRY_MS)
                    continue
                }
                return ConfirmOutcome.FAILED
            }
            delay(CONFIRM_SETTLE_MS)
            val afterRoot = service.rootInActiveWindow
            val stillOpen = findDialogConfirmButton(afterRoot, mergedConfirmTexts)
            if (afterRoot != null) recycleNode(afterRoot)
            if (stillOpen == null) return ConfirmOutcome.CLOSED
            recycleNode(stillOpen)
            AppLog.w(TAG, "confirm: dialog still open after tap step=${step.id}")
            StepDiagnostics.note(step.id, "CONFIRM", "still_open after_tap attempt=$attempt")
            if (attempt < CONFIRM_ATTEMPTS) delay(CONFIRM_RETRY_MS)
        }
        return ConfirmOutcome.FAILED
    }

    private suspend fun verifySwitchState(step: SimpleSteps.Step, texts: List<String>): Boolean {
        // MIUI применяет состояние не мгновенно (App Vault: тумблер отрисовался
        // включённым ещё мгновение после тапа — шаг рапортовал verify_failed, прогон
        // rmuh2vb1r): читаем состояние повторно, без дополнительных тапов.
        repeat(SWITCH_VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SWITCH_VERIFY_RETRY_DELAY_MS)
            val root = service.rootInActiveWindow
            if (root == null) {
                // Окно недоступно (переход активности): попытку повторяем, а не объявляем
                // состояние совпавшим — «нет данных» это не «выключено».
                AppLog.w(TAG, "verify: no active window attempt=${attempt + 1} step=${step.id}")
                return@repeat
            }
            val switchNode = findSwitchByText(root, texts)
            val actual = switchNode?.let { SwitchFinder.isChecked(it) }
            // Тумблер исчезает из дерева, когда настройка применена: App Vault после
            // подтверждения «Отключить» оставляет строку, а CheckBox убирает (дамп
            // av_recheck.xml — в дереве ни CheckBox, ни Switch). Поэтому «узла нет» —
            // успех ТОЛЬКО без диалога подтверждения шага на экране; иначе это
            // перекрытый диалогом экран, и прежний `?: true` давал ложный toggled
            // (прогон rmuod5cmm: диалог погасили отказом, тумблер остался включён).
            val result = when {
                actual != null -> actual == step.targetChecked
                else -> {
                    val confirmVisible = confirmTextsFor(step).any { text ->
                        TextMatcher.normalizedContains(NodeTree.collectText(root), text)
                    }
                    val vanished = !confirmVisible
                    AppLog.i(
                        TAG,
                        "verify: switch node gone row_vanished=$vanished step=${step.id}"
                    )
                    vanished
                }
            }
            AppLog.i(
                TAG,
                "verify: state ${if (attempt == 0) "before" else "after"} " +
                    "attempt=${attempt + 1} actual=$actual target=${step.targetChecked} " +
                    "found=${switchNode != null} step=${step.id}"
            )
            recycleNode(switchNode); recycleNode(root)
            if (result) return true
        }
        return false
    }

    // ═════════════════════════════════════════════════════════════════════
    // П.4: Структурный поиск ⋮/⚙ (4 уровня)
    // ═════════════════════════════════════════════════════════════════════
    /**
     * Открывает уровень-меню (⋮/⚙/☰). Возвращает true, если тап отправлен;
     * [onTapped] получает центр нажатого узла — вызывающий повторяет тап координатой,
     * если MIUI проигнорировал ACTION_CLICK (шестерёнка Mi Браузера открывала настройки
     * только тапом по центру: ручная проверка (846,197) на устройстве).
     */
    private suspend fun findAndTapOverflow(
        texts: List<String> = OVERFLOW_TEXTS,
        onTapped: (Int, Int) -> Unit = { _, _ -> }
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false

        suspend fun tap(node: AccessibilityNodeInfo): Boolean {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val tapped = tapNode(node)
            if (tapped) onTapped(rect.centerX(), rect.centerY())
            return tapped
        }

        // Шапка в приоритете: «Показать меню» (Музыка) важнее «Больше меню» у строки
        // списка, иначе тап уходит в контекстное меню трека и уровень не открывается.
        findHeaderMenu(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        // Точное совпадение подписи уровня — до поиска по вхождению: шестерёнка Mi Браузера
        // описана ровно «Настройки», а поиск по вхождению цеплял первое похожее слово
        // (строка ленты/поисковая строка) и тап уходил в голосовой ввод вместо настроек
        // (прогон rmuehut5w: drill_failed в com.google.android.tts).
        findExactClickableByText(root, texts)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        // Символьная иконка уровня-меню («⚙», «⋮», «☰») — точнее слова: на экране профиля
        // Mi Браузера «Настройки» встречается и в ленте, тап уходил не в меню настроек
        // (прогон rmuef7nbf: drill ушёл мимо настроек браузера).
        findClickableByText(root, texts.filter { isGlyphOnly(it) })?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findClickableByText(root, texts)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByContentDescription(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        findOverflowByPosition(root)?.let {
            val tapped = tap(it); recycleNode(it); recycleNode(root); return tapped
        }
        recycleNode(root)
        return performOverflowGesture()
    }

    /** Кнопка меню в шапке: кликабельный узел с описанием вида «Показать меню». */
    private fun findHeaderMenu(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        NodeTree.findInTree(root) { node ->
            val id = node.viewIdResourceName.orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (id.endsWith("item_menu")) return@findInTree false
            desc.isNotBlank() && HEADER_MENU_TEXTS.any { TextMatcher.normalizedContains(desc, it) }
        }

    private fun findOverflowByContentDescription(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.isNotBlank() && OVERFLOW_TEXTS.any { TextMatcher.normalizedContains(desc, it) }) {
                result.add(node); return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result.firstOrNull()
    }

    private fun findOverflowByPosition(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = service.resources.displayMetrics
        val xMin = (dm.widthPixels * 0.85f).toInt()
        val yMax = (dm.heightPixels * 0.15f).toInt()
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            if (node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.centerX() >= xMin && rect.centerY() <= yMax) candidates.add(node)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return candidates.maxByOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.centerY() * 1000 - r.centerX()
        }
    }

    /**
     * Слепой тап по позиции в правом верхнем углу. [stayInPackage] — пакет экрана до тапа:
     * если тап открыл чужое приложение (Mi Браузер → голосовой ввод Google TTS, прогон
     * rmuef7nbf), уровень не продолжается на чужом экране — шаг честно уходит в drill_failed
     * вместо ложной работы в другом приложении.
     */
    private suspend fun performOverflowGesture(stayInPackage: String? = null): Boolean {
        val dm = service.resources.displayMetrics
        for ((fx, fy) in OVERFLOW_GESTURE_POINTS) {
            if (cancelled) return false
            if (tapAt((dm.widthPixels * fx).toInt(), (dm.heightPixels * fy).toInt())) {
                delay(UI_SETTLE_DELAY_MS)
                if (stayInPackage != null) {
                    val now = activePackage()
                    if (now != null && !now.equals(stayInPackage, ignoreCase = true)) {
                        AppLog.w(TAG, "overflow gesture: экран сменился на '$now' — уровень прерван")
                        return false
                    }
                }
                return true
            }
        }
        return false
    }

    /** Тап с временно снятым поглощением тапов оверлеем (см. [withOverlayPassthrough]). */
    private suspend fun tapAt(x: Int, y: Int): Boolean = withOverlayPassthrough { tapAtRaw(x, y) }

    /**
     * Инъекция жеста при снятом поглощении тапов оверлеем.
     *
     * Оверлей Простого режима поглощает касания по всей площади (инвариант: пользователь
     * не прерывает автоматизацию), но это поглощение перехватывает и НАШИ жесты
     * ([AccessibilityService.dispatchGesture]): долгий тап по папке рабочего стола не
     * доходил до лаунчера («folder: editor not opened»), а тап в зоне кнопки «Отмена»
     * панели останавливал прогон целиком — `OverlaySvc: automation cancelled by user`,
     * прогон rmuh2vb1r умер на шаге 27/28 без действий владельца.
     *
     * На время жеста окно делаем не-перехватывающим (FLAG_NOT_TOUCHABLE) и сразу
     * возвращаем поглощение: вне моментов инъекции окно блокирует пользователя.
     */
    internal suspend fun withOverlayPassthrough(
        gestureMs: Long = OVERLAY_PASSTHROUGH_WINDOW_MS,
        block: suspend () -> Boolean
    ): Boolean {
        // Сторож ±500 мс вокруг инъекции (S4): пока жест идёт, оверлей игнорирует
        // нажатия своих кнопок — случайный тап по «Отменить оптимизацию» отменял прогон
        // целиком (прогон rmuh2vb1r, шаг 27/28). Сама отмена теперь ещё и подтверждается.
        OverlayController.armGestureGuard(gestureMs)
        // Пропуск касаний включается НЕ сменой флага окна, а временным окном в
        // touch-listener'е: на MIUI 13 смена FLAG_NOT_TOUCHABLE схлопывает
        // ACCESSIBILITY_OVERLAY в 0x0 и больше его не восстановить (прогон rmuih76mh).
        OverlayController.setPassthrough(service, OVERLAY_PASSTHROUGH_WINDOW_MS)
        // Запрос доезжает до сервиса асинхронно (`startService`): жест, отданный сразу,
        // попадает в ещё-перехватывающее окно и пропадает (прогон rmumuqr53:
        // `tap … via=node` → `touch intercepted x=972 y=2182`, окно открылось на 60 мс
        // позже, а ретрай ударил по соседнему узлу). Ждём подтверждение ПЕРЕД инъекцией.
        awaitPassthrough()
        return block()
    }

    /**
     * Ждёт подтверждения passthrough-окна (сервис зовёт [OverlayController.markPassthrough]).
     * Если оверлей не прикреплён (тесты, Про-режим), ждать нечего — инжектим сразу.
     */
    private suspend fun awaitPassthrough(timeoutMs: Long = OVERLAY_PASSTHROUGH_WAIT_MS) {
        if (!OverlayController.isAttached) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (OverlayController.isPassthroughActive()) return
            delay(OVERLAY_PASSTHROUGH_POLL_MS)
        }
        AppLog.w(TAG, "overlay passthrough not confirmed within ${timeoutMs}ms — injecting anyway")
    }

    private suspend fun tapAtRaw(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return suspendCancellableCoroutine { cont ->
            // Жест не отправлен — сразу false, иначе шаг висит до общего таймаута
            // (callback в этом случае никогда не вызывается).
            val dispatched =
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Папки рабочего стола: «Рекомендуемое сегодня» внутри папки лаунчера
    // ════════════════════════════════════════════════════════════════════

    /**
     * Рекомендации в папках рабочего стола: открываем папку лаунчера, затем её
     * редактор (тап по названию, иначе долгий тап → «Изменить папку») и выключаем
     * «Рекомендуемое сегодня». Имя папки задаёт пользователь, поэтому папка
     * ищется структурно, а тумблер — по семантике каталога и гейту уверенности.
     */
    /**
     * Рекомендации в папках рабочего стола. Порядок работы:
     * 1) если владелец уже открыл папку или её редактор — работаем в текущем окне
     *    (resetToHome не делаем: помощь пользователя не выбрасываем);
     * 2) иначе идём на рабочий стол и проверяем ПАПКИ по очереди;
     * 3) «секции рекомендаций нет» в одной папке не завершает шаг — проверяем остальные,
     *    и только пройдя все, отдаём честное «настройки нет на устройстве».
     */
    internal suspend fun toggleHomeFolderSuggestions(step: SimpleSteps.Step): Result {
        val toggleTexts = SemanticCatalog.itemTexts(step.id)
        val editorMarkers = SemanticCatalog.screenMarkers(step.id)
        val menuTexts = SemanticCatalog.overflowMenuLabels(step.id)

        // Владелец мог открыть папку вручную: сначала пробуем его окно, а не рабочий стол.
        val openState = currentFolderState(toggleTexts)
        if (openState != FolderState.NONE) {
            AppLog.i(TAG, "folder: открытое окно лаунчера ($openState) — работаем в нём")
            StepDiagnostics.note(step.id, "FOLDER", "resume=" + openState)
            if (openState == FolderState.POPOVER) {
                currentFolderTitleLabel()?.let { label ->
                    if (tapFolderTitle(label)) delay(UI_SETTLE_DELAY_MS)
                }
            }
            toggleWithGate(step, toggleTexts, editorMarkers)?.let { result ->
                currentFolderTitleLabel()?.let { name -> rememberFolderName(step.id, name) }
                return result
            }
            AppLog.i(TAG, "folder: в открытом окне секции рекомендаций нет")
        }

        resetToHome()
        delay(UI_SETTLE_DELAY_MS)

        // Подсказки имён: сначала запомненное на этом устройстве, затем каталог (слова
        // локали + имя страны региона). Полного перебора папок нет: у владельца бывает
        // 20 папок, поэтому сначала идут только «похожие на нужную».
        val hints = (
            listOfNotNull(loadFolderHints()[folderHintKey(step.id)]) +
                SemanticCatalog.folderNameHints(step.id, runCatching { romProfile.regionCode }.getOrNull())
            ).distinct()
        val candidates = homeFolderCandidates()
            .sortedByDescending { node -> if (matchesFolderHint(folderLabel(node), hints)) 1 else 0 }
        val hintMatch = candidates.any { matchesFolderHint(folderLabel(it), hints) }
        val probeLimit = if (hints.isEmpty() || hintMatch) MAX_FOLDER_PROBES else MAX_FOLDER_STRUCTURAL_PROBES
        StepDiagnostics.note(
            step.id, "FOLDER",
            "candidates=" + candidates.size + " hints=" + hints.size +
                " hintMatch=" + hintMatch + " limit=" + probeLimit
        )
        AppLog.i(
            TAG,
            "folder: candidates=" + candidates.size + " hints=" + hints.size +
                " hintMatch=" + hintMatch + " step=" + step.id
        )
        if (candidates.isEmpty()) return Result(false, "folder_not_found")

        var probed = 0
        var foldersChecked = 0
        var editorMissed = false
        for (candidate in candidates) {
            if (probed >= probeLimit) break
            if (remainingBudgetMs() < FOLDER_BUDGET_RESERVE_MS) {
                AppLog.w(TAG, "folder: бюджет шага на исходе, папки не проверяем дальше")
                break
            }
            probed++
            if (cancelled) return Result(false, "cancelled")
            if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")

            val label = folderLabel(candidate)
            val rect = Rect()
            candidate.getBoundsInScreen(rect)
            StepDiagnostics.note(
                step.id, "FOLDER",
                "probe=" + probed + " name=" + label +
                    " hint=" + matchesFolderHint(label, hints) + " class=" + candidate.className
            )
            val tapped = tapNode(candidate)
            recycleNode(candidate)
            if (!tapped) {
                AppLog.w(TAG, "folder: tap failed for " + label)
                continue
            }
            delay(FOLDER_OPEN_DELAY_MS)

            // Иконка приложения внешне похожа на папку: признак папки — лаунчер остался
            // в фокусе (тап по приложению сменил бы пакет).
            val foreground = activePackage()
            if (foreground != null && !isLauncherPackage(foreground)) {
                StepDiagnostics.note(
                    step.id, "FOLDER",
                    "probe=" + probed + " name=" + label + " not_a_folder fg=" + foreground
                )
                AppLog.i(TAG, "folder: " + label + " не папка (fg=" + foreground + ")")
                resetToHome()
                delay(UI_SETTLE_DELAY_MS)
                continue
            }
            AppLog.i(TAG, "folder: popover opened for " + label)
            foldersChecked++

            val editorOpened = openFolderEditor(
                folderLabel = label,
                folderX = rect.centerX().toFloat(),
                folderY = rect.centerY().toFloat(),
                menuTexts = menuTexts,
                editorMarkers = editorMarkers,
                toggleTexts = toggleTexts
            )
            if (!editorOpened) {
                // У ЭТОЙ папки секция/редактор не открылись: первая по дереву папка
                // может быть не той (владелец: робот открывает не те папки) — проверяем
                // следующую, а не объявляем шаг неприменимым.
                AppLog.w(TAG, "folder: editor not opened for " + label + " — следующая папка")
                StepDiagnostics.note(step.id, "FOLDER", "editor_not_opened name=" + label)
                editorMissed = true
                leaveFolderEditor()
                continue
            }
            val result = toggleWithGate(step, toggleTexts, editorMarkers)
            leaveFolderEditor()
            if (result != null) {
                // Секция рекомендаций подтвердила папку: запоминаем имя, чтобы больше не
                // перебирать папки и не зависеть от переименований на прошивке.
                rememberFolderName(step.id, label)
                return result
            }
            AppLog.i(TAG, "folder: в папке " + label + " секции рекомендаций нет — следующая")
            StepDiagnostics.note(step.id, "FOLDER", "no_suggestions name=" + label)
        }

        if (foldersChecked > 0 && !editorMissed) {
            // Проверили все папки: секции рекомендаций нет ни в одной — состояние
            // устройства (выключено вместе с msa/персонализацией), а не промах.
            AppLog.i(TAG, "folder: секции рекомендаций нет в " + foldersChecked + " папках")
            StepDiagnostics.note(
                step.id, "APPLICABILITY", "folder_switch_absent folders=" + foldersChecked
            )
            return Result(false, FOLDER_SWITCH_ABSENT)
        }
        // Папки есть, но ни одну не довели до секции: честный промах автоматизации
        // (ведро «не нашёл» в отчёте), а не «нет на устройстве».
        StepDiagnostics.note(
            step.id, "APPLICABILITY", "folder_editor_not_opened folders=" + foldersChecked
        )
        return Result(false, FOLDER_EDITOR_NOT_OPENED)
    }

    /**
     * Кандидаты-иконки рабочего стола: подпись + структура иконки лаунчера
     * (`icon_container`/`icon_title`) либо класс/описание папки. Папка внешне не
     * отличима от приложения, поэтому кандидаты упорядочены «похожие на папку» →
     * остальные, а фактический признак проверяется в рутине (фокус остался лаунчером).
     */
    internal fun homeFolderCandidates(): List<AccessibilityNodeInfo> =
        // Только папки: иконка приложения секции рекомендаций не содержит, а пробы по
        // ним жгли бюджет шага (прогон rmua2sd7x: probes 1-6 = Проводник, Заметки…).
        scanHomeRoot { isFolderCandidate(it) }

    /** Иконка рабочего стола: кликабельный узел с подписью и структурой иконки. */
    internal fun isHomeIconNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable || folderLabel(node).isBlank()) return false
        if (isFolderCandidate(node)) return true
        return NodeTree.findInTree(node) { child ->
            val id = child.viewIdResourceName ?: ""
            id.endsWith("icon_container") || id.endsWith("icon_title")
        } != null
    }

    /** Пакет в фокусе: отличает поповер папки от запущенного приложения. */
    private fun activePackage(): String? {
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        recycleNode(root)
        return pkg
    }

    private fun isLauncherPackage(pkg: String): Boolean =
        LAUNCHER_PACKAGES.any { it.equals(pkg, ignoreCase = true) }

    private fun scanHomeRoot(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val found = NodeTree.findAllInTree(root, predicate)
        recycleNode(root)
        return found
    }

    /** Папка лаунчера: класс/описание содержат folder, у узла есть подпись. */
    internal fun isFolderCandidate(node: AccessibilityNodeInfo): Boolean {
        if (folderLabel(node).isBlank()) return false
        if (isFolderGridCandidate(node)) return true
        val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (cls.contains("folder")) return true
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return desc.contains("folder") || desc.contains("папка")
    }

    /**
     * Превью папки лаунчера POCO/MIUI: контейнер `preview_icons_container` либо
     * несколько элементов `itemN` внутри иконки. У иконки приложения превью-сетки нет
     * (`icon_icon` + `cover`), поэтому признак различает их без класса/описания.
     *
     * Дамп рабочего стола POCO Launcher: папка с рекомендациями — обычный
     * кликабельный `FrameLayout desc='Russia'` без слова «folder» в классе и описании,
     * из-за чего она не попадала в первые пробы (`homeFolderCandidates` сортирует
     * «похожие на папку» вперёд) и шаг `folder_recommendations` тратил лимит
     * `MAX_FOLDER_PROBES` на обычные иконки (прогон rmua2sd7x: probes 1–6 = Проводник,
     * Заметки, Календарь, ShareMe, Погода, Безопасность; папка так и не проверена).
     */
    internal fun isFolderGridCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        if (NodeTree.findInTree(node) { child ->
                child.viewIdResourceName?.endsWith("preview_icons_container") == true
            } != null
        ) {
            return true
        }
        var items = 0
        var icons = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > FOLDER_PREVIEW_DEPTH) return
            val id = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (id.startsWith("item")) items++
            val cls = n.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (cls.contains("imageview") && !n.contentDescription.isNullOrBlank()) icons++
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return items >= 2 || icons >= 2
    }

    /** Подпись папки: текст узла, иначе описание (имя задаёт пользователь). */
    private fun folderLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty().ifEmpty {
            node.contentDescription?.toString()?.trim().orEmpty()
        }

    /**
     * Открытие редактора папки: тап по названию в поповере, иначе долгий тап по
     * иконке папки и «Изменить папку» (HyperOS 2/3).
     */
    private suspend fun openFolderEditor(
        folderLabel: String,
        folderX: Float,
        folderY: Float,
        menuTexts: List<String>,
        editorMarkers: List<String>,
        toggleTexts: List<String>
    ): Boolean {
        // Ждём поповер: без ожидания тап заголовка уходил в иконку рабочего стола ПОЗАДИ
        // поповера (анимация MIUI), редактор не открывался и шаг шёл искать другие папки
        // (прогон rmulg783z).
        if (awaitFolderPopover(FOLDER_POPOVER_WAIT_MS)) {
            repeat(FOLDER_TITLE_TAPS) { attempt ->
                if (cancelled) return false
                if (!tapFolderTitle(folderLabel)) return@repeat
                delay(UI_SETTLE_DELAY_MS)
                if (isFolderEditorVisible(editorMarkers, toggleTexts)) return true
                AppLog.w(
                    TAG,
                    "folder: заголовок нажат, редактор не подтверждён (попытка " + (attempt + 1) + ")"
                )
            }
        } else {
            AppLog.w(TAG, "folder: поповер папки не появился за " + FOLDER_POPOVER_WAIT_MS + "ms")
        }
        if (menuTexts.isEmpty()) return false
        if (!awaitOverlayReadyOrPause()) return false
        // Контекстное меню папки живёт только на рабочем столе: возвращаемся.
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
        longPressAt(folderX, folderY)
        delay(FOLDER_OPEN_DELAY_MS)
        val root = service.rootInActiveWindow ?: return false
        val item = findClickableByText(root, menuTexts)
        if (item == null) {
            recycleNode(root)
            return false
        }
        val tapped = tapNode(item)
        recycleNode(item); recycleNode(root)
        delay(FOLDER_OPEN_DELAY_MS)
        return tapped && isFolderEditorVisible(editorMarkers, toggleTexts)
    }

    /**
     * Название папки ВНУТРИ поповера. Сначала — узел `id=title` (настоящий заголовок
     * открытой папки), и только потом мягкий поиск по подписи с исключением иконок
     * рабочего стола: они лежат в том же дереве позади поповера и несут ту же подпись
     * (`icon_title`), поэтому прежний код тапал именно иконку — редактор не открывался,
     * и шаг уходил искать другие папки (прогон rmulg783z).
     */
    private suspend fun tapFolderTitle(label: String): Boolean {
        findFolderTitleNode()?.let { node ->
            val tapped = tapNode(node)
            recycleNode(node)
            if (tapped) return true
        }
        val root = service.rootInActiveWindow ?: return false
        val candidates = NodeTree.findAllInTree(root, predicate = { node ->
            !node.text.isNullOrBlank() &&
                clickableAncestorOrSelf(node) != null &&
                !belongsToDesktopIcon(node)
        })
        recycleNode(root)
        val named = if (label.isNotBlank()) {
            candidates.firstOrNull { TextMatcher.normalizedContains(it.text?.toString(), label) }
        } else {
            null
        }
        val chosen = named ?: candidates.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.centerY()
        }
        candidates.forEach { if (it !== chosen) recycleNode(it) }
        chosen ?: return false
        val title = clickableAncestorOrSelf(chosen) ?: chosen
        val tapped = tapNode(title)
        recycleNode(chosen)
        if (title !== chosen) recycleNode(title)
        return tapped
    }

    /**
     * Имя папки совпало с подсказкой (каталог или обучение): сравнение мягкое, по локали.
     * Имя — только подсказка для порядка проверки: истина — наличие секции рекомендаций
     * внутри папки, поэтому переименование на прошивке не ломает поиск.
     */
    internal fun matchesFolderHint(label: String, hints: List<String>): Boolean {
        if (label.isBlank() || hints.isEmpty()) return false
        return hints.any { TextMatcher.normalizedContains(label, it) }
    }

    /**
     * Ключ обучения: шаг + регион + локаль. Имя папки задаёт прошивка региона, поэтому
     * после смены региона/локали запомненное имя не применяется (и перезапишется новым).
     */
    private fun folderHintKey(stepId: String): String {
        val region = runCatching { romProfile.regionCode }.getOrDefault("")
        return stepId + "|" + region + "|" + Locale.getDefault().language
    }

    /** Запомненные имена папок: читаются из DataStore один раз за прогон. */
    private var folderHintsCache: MutableMap<String, String>? = null

    private suspend fun loadFolderHints(): Map<String, String> {
        folderHintsCache?.let { return it }
        val store = prefs ?: return emptyMap()
        val json = runCatching { store.getFolderNameHints() }.getOrNull().orEmpty()
        if (json.isBlank()) return emptyMap()
        val cache = LinkedHashMap<String, String>()
        runCatching {
            val obj = org.json.JSONObject(json)
            obj.keys().forEach { key ->
                obj.optString(key).takeIf { it.isNotBlank() }?.let { cache[key] = it }
            }
        }.onFailure { AppLog.w(TAG, "folder hints parse failed: " + it.message) }
        folderHintsCache = cache
        return cache
    }

    /** Запоминает папку, в которой реально подтверждена секция рекомендаций. */
    private suspend fun rememberFolderName(stepId: String, label: String) {
        if (label.isBlank()) return
        val store = prefs ?: return
        val key = folderHintKey(stepId)
        val cache = folderHintsCache ?: loadFolderHints().toMutableMap().also { folderHintsCache = it }
        if (cache[key] == label) return
        cache[key] = label
        val json = cache.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            org.json.JSONObject.quote(k) + ":" + org.json.JSONObject.quote(v)
        }
        runCatching { store.saveFolderNameHints(json) }
            .onFailure { AppLog.w(TAG, "rememberFolderName failed: " + it.message) }
        StepDiagnostics.note(stepId, "FOLDER", "learned name=" + label)
    }

    /** Что сейчас открыто поверх рабочего стола: поповер папки, её редактор или ничего. */
    private enum class FolderState { NONE, POPOVER, EDITOR }

    /**
     * Состояние лаунчера: EDITOR — виден тумблер рекомендаций или поле переименования,
     * POPOVER — открыт поповер папки (есть её название `id=title`), NONE — рабочий стол
     * или чужое приложение. Нужно, чтобы не выбрасывать владельца с открытой папки на
     * рабочий стол (resetToHome) и работать в его окне.
     */
    private fun currentFolderState(toggleTexts: List<String>): FolderState {
        val root = service.rootInActiveWindow ?: return FolderState.NONE
        val pkg = root.packageName?.toString()
        if (pkg == null || !isLauncherPackage(pkg)) {
            recycleNode(root)
            return FolderState.NONE
        }
        val row = findSwitchByText(root, toggleTexts)
        val editor = NodeTree.findInTree(root) { n ->
            n.viewIdResourceName?.endsWith("rename_edit") == true
        }
        val title = NodeTree.findInTree(root) { n -> isFolderTitleNode(n) }
        recycleNode(row); recycleNode(editor); recycleNode(title)
        recycleNode(root)
        return when {
            row != null || editor != null -> FolderState.EDITOR
            title != null -> FolderState.POPOVER
            else -> FolderState.NONE
        }
    }

    /** Название папки в открытом поповере (узел `id=title` лаунчера). */
    private fun currentFolderTitleLabel(): String? {
        val root = service.rootInActiveWindow ?: return null
        val node = NodeTree.findInTree(root) { n -> isFolderTitleNode(n) }
        val label = node?.text?.toString()?.trim()
        recycleNode(node)
        recycleNode(root)
        return label?.takeIf { it.isNotEmpty() }
    }

    /**
     * Экран редактора папки. Признаки: поле переименования `rename_edit`, найденный
     * тумблер рекомендаций либо маркеры каталога. Маркеры принимаем ТОЛЬКО когда заголовка
     * поповера уже нет (в редакторе его место занимает `rename_edit`): иначе фоновые тексты
     * давали ложное «редактор открыт», и шаг объявлял, что секции рекомендаций нет
     * (прогон rmulg783z).
     */
    private fun isFolderEditorVisible(markers: List<String>, toggleTexts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        val renamed = NodeTree.findInTree(root) { node ->
            node.viewIdResourceName?.endsWith("rename_edit") == true
        }
        val byRename = renamed != null
        recycleNode(renamed)
        val switchNode = findSwitchByText(root, toggleTexts)
        val bySwitch = switchNode != null
        recycleNode(switchNode)
        val titleNode = NodeTree.findInTree(root) { node -> isFolderTitleNode(node) }
        val titleGone = titleNode == null
        recycleNode(titleNode)
        recycleNode(root)
        val byMarkers = markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(text, it) }
        return byRename || bySwitch || (byMarkers && titleGone)
    }

    /** Заголовок открытого поповера: узел `id=title` с непустым текстом. Иконки рабочего
     *  стола позади поповера такого id не имеют (`icon_title`), поэтому признак отличает
     *  настоящий заголовок папки от её иконки. */
    internal fun findFolderTitleNode(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val node = NodeTree.findInTree(root) { n -> isFolderTitleNode(n) }
        recycleNode(root)
        return node
    }

    /**
     * Заголовок папки лаунчера: ресурс ИМЕННО лаунчера `...:id/title` с непустым текстом.
     * Проверка только по суффиксу id находила ЧУЖИЕ заголовки: на рабочем столе есть виджеты
     * приложений с тем же суффиксом (`com.android.chrome:id/title` — виджет поиска), и тап
     * уходил в виджет вместо редактора папки (прогон rmulgzmdg).
     */
    internal fun isFolderTitleNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.viewIdResourceName.orEmpty().endsWith("/title")) return false
        if (node.text.isNullOrBlank()) return false
        val pkg = node.packageName?.toString() ?: return false
        return isLauncherPackage(pkg)
    }

    /** Ждёт появления поповера папки: признак — заголовок `id=title`. */
    private suspend fun awaitFolderPopover(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            val title = findFolderTitleNode()
            if (title != null) {
                recycleNode(title)
                return true
            }
            delay(FOLDER_POPOVER_POLL_MS)
        }
        return false
    }

    /** Узел принадлежит иконке рабочего стола (позади поповера), а не самому поповеру. */
    internal fun belongsToDesktopIcon(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < ICON_ANCESTOR_DEPTH) {
            val id = cur.viewIdResourceName?.substringAfterLast("/").orEmpty()
            if (id == "icon_container" || id == "icon_title_container") return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /**
     * Тумблер на целевом экране (редактор папки, настройки установщика): гейт
     * уверенности (keywords + маркеры + тумблер), checked_before в снапшот отката,
     * валидация после тапа. `null` — тумблера нет (пробуем следующую цель), иначе
     * результат шага.
     */
    private suspend fun toggleWithGate(
        step: SimpleSteps.Step,
        toggleTexts: List<String>,
        editorMarkers: List<String>
    ): Result? {
        val root = service.rootInActiveWindow ?: return null
        val screenText = collectAllText(root)
        val switchNode = findSwitchByText(root, toggleTexts)
        val decision = SemanticGate.decide(
            keywords = toggleTexts,
            screenText = screenText,
            screenMarkers = editorMarkers,
            switchFound = switchNode != null,
            hasTapFallback = false
        )
        SemanticGate.log(step.id, decision)
        if (switchNode == null) {
            recycleNode(root)
            return null
        }
        if (!decision.act) {
            AppLog.w(TAG, "folder: low confidence in editor (${decision.detail}) — не тумблим")
            recycleNode(switchNode); recycleNode(root)
            return null
        }
        val checkedBefore = SwitchFinder.isChecked(switchNode)
        recordCheckedBefore(step.id, checkedBefore)
        if (checkedBefore == step.targetChecked) {
            AppLog.i(TAG, "folder: '${toggleTexts.firstOrNull()}' already off — nothing to toggle")
            recycleNode(switchNode); recycleNode(root)
            return Result(true, "already_off")
        }
        val tapped = tapNode(switchNode)
        recycleNode(switchNode); recycleNode(root)
        if (!tapped) return Result(false, "tap_failed")
        delay(600)
        if (!verifySwitchState(step, toggleTexts)) {
            AppLog.w(TAG, "folder: switch state not verified after tap (step=${step.id})")
            return Result(false, "verify_failed")
        }
        AppLog.i(TAG, "folder: toggled '${toggleTexts.firstOrNull()}' step=${step.id}")
        return Result(true, "toggled")
    }

    /** Закрываем редактор папки (Back ×2) и возвращаемся на рабочий стол. */
    private suspend fun leaveFolderEditor() {
        repeat(2) {
            if (cancelled) return
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
    }

    /** Долгий тап по координатам (контекстное меню папки на HyperOS 2/3). */
    private suspend fun longPressAt(x: Float, y: Float): Boolean =
        performGesture(x, y, x, y, LONG_PRESS_MS)

    // ════════════════════════════════════════════════════════════════════
    // Установщик приложений: настройки проверки (APK не устанавливаем)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Проверка приложений при установке: открываем активность настроек установщика
     * (та же, что даёт шестерёнка в окне проверки) и выключаем «Получать
     * рекомендации». Установка APK не запускается: ни один текст-подтверждение
     * установки не тапается (guard [isInstallConfirmText]), после шага — HOME.
     */
    internal suspend fun toggleInstallerRecommendations(step: SimpleSteps.Step): Result {
        val toggleTexts = SemanticCatalog.itemTexts(step.id)
        val settingsMarkers = SemanticCatalog.screenMarkers(step.id)
        // Страховка: подписи тумблера из каталога не должны выглядеть как «Установить».
        if (toggleTexts.any { isInstallConfirmText(it) }) {
            AppLog.w(TAG, "installer: catalog label looks like an install confirmation — step skipped")
            return Result(false, "catalog_label_looks_like_install")
        }
        val candidates = installerSettingsCandidates(candidatePackages(step))
        StepDiagnostics.note(step.id, "INSTALLER", "candidates=${candidates.size}")
        AppLog.i(TAG, "installer: candidates=${candidates.size} step=${step.id}")
        // Активности установщика (`.SettingsActivity`, `.activity.ScanActivity`) не exported:
        // запуск из стороннего актора → Permission Denial, фильтры помечены
        // category.MONKEY (пробы 2026-09-26). Экран настроек («Расширенные настройки» →
        // «Получать рекомендации») живёт только внутри потока установки, который открывает
        // MIUI. Поэтому сначала проверяем, не открыт ли он УЖЕ (установка из магазина/
        // браузера) — и работаем на нём, ничего не запуская и ничего не устанавливая.
        if (isInstallerSettingsScreen(settingsMarkers, toggleTexts)) {
            AppLog.i(TAG, "installer: settings screen already open — toggling in place")
            StepDiagnostics.note(step.id, "INSTALLER", "screen_already_open")
            val live = toggleWithGate(step, toggleTexts, settingsMarkers)
            pressBackToNeutral()
            return live ?: Result(false, NOT_APPLICABLE)
        }
        if (candidates.isEmpty()) {
            // S15: у установщика нет экспортированных активностей настроек — это
            // неприменимость на прошивке, а не провал автоматизации (ручная памятка
            // package_installer остаётся в отчёте).
            StepDiagnostics.note(step.id, "APPLICABILITY", "installer_settings_not_found")
            AppLog.i(TAG, "installer: no settings activities — installer_settings_not_found")
            return Result(false, INSTALLER_SETTINGS_ABSENT)
        }

        var tried = 0
        var launchedAny = false
        for (component in candidates) {
            if (tried >= MAX_INSTALLER_CANDIDATES) break
            tried++
            if (cancelled) return Result(false, "cancelled")
            if (!awaitOverlayReadyOrPause()) return Result(false, "overlay_lost")
            val short = component.flattenToShortString()
            StepDiagnostics.note(step.id, "INSTALLER", "open=$tried component=$short")
            if (!launchComponent(component)) {
                AppLog.w(TAG, "installer: launch failed for $short")
                continue
            }
            launchedAny = true
            delay(APP_LAUNCH_DELAY_MS)
            handleConsentWalls(step)
            if (!isInstallerSettingsScreen(settingsMarkers, toggleTexts)) {
                AppLog.w(TAG, "installer: not a settings screen ($short)")
                pressBackToNeutral()
                continue
            }
            val result = toggleWithGate(step, toggleTexts, settingsMarkers)
            pressBackToNeutral()
            if (result != null) return result
            AppLog.i(TAG, "installer: no switch on $short — next activity")
        }
        if (!launchedAny) {
            // S15: все кандидаты отказали в старте (Permission Denial / SecurityException) —
            // честная неприменимость вместо switch_not_found.
            StepDiagnostics.note(step.id, "APPLICABILITY", "installer_settings_denied tried=$tried")
            AppLog.i(TAG, "installer: all candidates denied launch — installer_settings_denied")
            return Result(false, INSTALLER_SETTINGS_DENIED)
        }
        return Result(false, "switch_not_found")
    }

    /**
     * Кандидаты-активности настроек установщика: экспортируемые активности с
     * признаком настроек/проверки. Полный список пишется в StepDiag — по нему
     * debug-прогон показывает фактическую активность на конкретной прошивке.
     */
    internal fun installerSettingsCandidates(packages: List<String>): List<ComponentName> {
        val pm = runCatching { service.packageManager }.getOrNull() ?: return emptyList()
        val result = ArrayList<ComponentName>()
        for (pkg in packages) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.getOrNull() ?: continue
            val activities = info.activities.orEmpty()
            StepDiagnostics.note(
                "installer", "ACTIVITIES",
                "pkg=$pkg exported=" + activities.filter { it.exported }.joinToString(",") { it.name }
            )
            activities.filter { it.exported && isInstallerSettingsActivity(it.name) }
                .forEach { result.add(ComponentName(pkg, it.name)) }
        }
        return result
    }

    /**
     * Признак активности настроек: settings/preference/recommend/advanced/scan.
     * Активности самой установки (`PackageInstallerActivity`, `InstallAppProgress`)
     * сюда не попадают — установку мы не открываем.
     */
    internal fun isInstallerSettingsActivity(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return INSTALLER_SETTINGS_KEYWORDS.any { n.contains(it) }
    }

    /** Тексты подтверждения установки: по ним не тапаем никогда. */
    internal fun isInstallConfirmText(text: String): Boolean {
        val n = TextMatcher.normalize(text)
        return n.isNotEmpty() && INSTALL_CONFIRM_TEXTS.any { n.contains(it) }
    }

    private fun launchComponent(component: ComponentName): Boolean = try {
        service.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        AppLog.w(TAG, "installer: startActivity failed: ${e.message}")
        false
    }

    /** Экран настроек установщика: маркеры каталога или найденный тумблер. */
    private fun isInstallerSettingsScreen(markers: List<String>, toggleTexts: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        val hasSwitch = findSwitchByText(root, toggleTexts) != null
        recycleNode(root)
        val markerHit = markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(text, it) }
        return markerHit || hasSwitch
    }

    /** Закрываем экран установщика и возвращаемся на рабочий стол. */
    private suspend fun pressBackToNeutral() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(UI_SETTLE_DELAY_MS)
        resetToHome()
        delay(UI_SETTLE_DELAY_MS)
    }

    // Consent-стены (welcome/permission) — см. handleConsentWalls + ConsentWallHandler
    // ═════════════════════════════════════════════════════════════════════
    /** Подпись последней нажатой кнопки диалога: идёт в лог стены (`tapped='…'`). */
    @Volatile
    private var lastDialogTapText: String = ""

    /** Мост нажатий для ConsentWallHandler (поиск кликабельного узла по текстам). */
    private val consentTapBridge = object : ConsentWallHandler.TapBridge {
        override val lastTappedText: String get() = lastDialogTapText

        override suspend fun tapByTexts(texts: List<String>): Boolean = tapSystemDialogButton(texts)

        override suspend fun tapEnabledByTexts(texts: List<String>): Boolean =
            tapSystemDialogButton(texts, requireEnabled = true)

        override suspend fun tapDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapSystemDialogButton(texts, avoidTexts = avoidTexts)

        override suspend fun tapEnabledDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapSystemDialogButton(texts, requireEnabled = true, avoidTexts = avoidTexts)

        override suspend fun tapByIds(ids: List<String>): Boolean = tapSystemNodeByIds(ids)

        /** Свайп вверх по центру экрана: закрытие полноэкранного гайда-жеста. */
        override suspend fun swipeUp(): Boolean {
            val dm = service.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            return performGesture(cx, dm.heightPixels * 0.72f, cx, dm.heightPixels * 0.28f, 320)
        }

        /**
         * Снятие отметки с чекбоксов персонализации до согласия. Отметку проверяем
         * после тапа по тому же узлу: MIUI-чекбокс может не отреагировать на
         * ACTION_CLICK, и тогда согласие не должно считаться «чистым».
         */
        override suspend fun uncheckCheckboxes(entries: List<Pair<String, String>>): List<String> {
            if (entries.isEmpty()) return emptyList()
            val root = service.rootInActiveWindow ?: return emptyList()
            val unchecked = ArrayList<String>(entries.size)
            for ((id, label) in entries) {
                val node = NodeTree.findAllInTree(root, predicate = { n ->
                    val rid = n.viewIdResourceName ?: ""
                    rid.isNotEmpty() && rid.endsWith(id, ignoreCase = true)
                }).firstOrNull { it.isCheckable }
                if (node == null) {
                    AppLog.i(TAG, "consent: uncheck id not found id=$id")
                    continue
                }
                if (!node.isChecked) {
                    recycleNode(node)
                    continue
                }
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(UNCHECK_SETTLE_DELAY_MS)
                if (clicked && !node.isChecked) unchecked.add(label)
                recycleNode(node)
            }
            recycleNode(root)
            AppLog.i(TAG, "consent: uncheck ids=${entries.map { it.first }} unchecked=$unchecked")
            return unchecked
        }
    }

    /**
     * Единая точка входа для системных диалогов: welcome-стены и
     * runtime-permission запросы по consentPolicy (deny по умолчанию).
     */
    private suspend fun handleConsentWalls(step: SimpleSteps.Step): Int {
        val handled = ConsentWallHandler.handleUntilSettled(
            service = service,
            bridge = consentTapBridge,
            stepId = step.id,
            stepConsentTexts = SemanticCatalog.consentTexts(step.id),
            // Confirm-тексты шага: диалог, который ведёт сам шаг (msa «Отозвать»),
            // generic-закрытие не трогает.
            stepConfirmTexts = confirmTextsFor(step),
            // Владелец диалога = пакет-цель шага: это диалог самого приложения.
            stepPackages = stepPackagesFor(step),
            // Подписи приложений-целей: MIUI-контроллер разрешений просит доступ
            // «приложению Проводник» — по подписи понимаем, что это доступ для
            // шага, и разрешаем (иначе приложение не пускает дальше).
            stepLabels = stepPackagesFor(step).mapNotNull { appLabel(it) },
            // Welcome-стены запрещены шагам с RouteScript (Проводник): их рабочие
            // экраны совпадают словами с welcomeActions (прогон rmumuqr53).
            allowWelcome = SemanticCatalog.welcomeAllowed(step.id),
            maxIterations = SemanticCatalog.maxConsentIterations(
                step.id,
                SemanticCatalog.maxConsentIterationsPolicy()
            ),
            isCancelled = { cancelled }
        )
        if (handled > 0) delay(UI_SETTLE_DELAY_MS)
        return handled
    }

    /** Тексты поиска: keywords каталога + deprecated legacy searchTexts. */
    private fun searchTextsFor(step: SimpleSteps.Step): List<String> = SemanticCatalog.keywords(
        step.id,
        AdaptiveCatalog.mergeSearchTexts(service, step.id, step.searchTexts)
    )

    /** Тексты подтверждения: каталог + вариантный каталог + legacy. */
    private fun confirmTextsFor(step: SimpleSteps.Step): List<String> = SemanticCatalog.confirmTexts(
        step.id,
        AdaptiveCatalog.mergeConfirmTexts(service, step.id, step.confirmTexts)
    )

    /** Тексты кнопки-действия (экран без тумблера): каталог + вариантный каталог. */
    private fun tapFallbackTextsFor(step: SimpleSteps.Step): List<String> =
        SemanticCatalog.tapFallbackTexts(
            step.id,
            AdaptiveCatalog.mergeTapFallbackTexts(service, step.id, step.tapFallbackTexts)
        )

    /**
     * Маршрут навигации. Приоритет источников: `route` (RouteScript варианта — ровно
     * проверенные руками интенты и тапы) → `variants[].drillPath` (явно совпавший
     * вариант ОС) → legacy-путь + `fallbackDrillPath`-подсказки каталога.
     * Поведение cn_hyperos (базовые поля) не меняется.
     */
    private fun semanticDrillPath(
        step: SimpleSteps.Step,
        legacyPath: List<List<String>>
    ): List<List<String>> {
        val variantPath = SemanticCatalog.variantDrillPath(step.id)
        if (variantPath.isNotEmpty()) return variantPath
        val semantic = SemanticCatalog.fallbackDrillPath(step.id)
        if (semantic.isEmpty()) return legacyPath
        val seen = HashSet<String>()
        val result = ArrayList<List<String>>(legacyPath.size + semantic.size)
        for (level in legacyPath + semantic) {
            if (seen.add(level.joinToString("\u0001"))) result.add(level)
        }
        return result
    }

    /** launchPackage: legacy-значение главнее, каталог дополняет. */
    private fun applySemanticLaunchPackage(step: SimpleSteps.Step): SimpleSteps.Step {
        if (step.launchPackage != null) return step
        val fromCatalog = SemanticCatalog.launchPackage(step.id) ?: return step
        AppLog.i(TAG, "semantic launchPackage for ${step.id}: $fromCatalog")
        return step.copy(launchPackage = fromCatalog)
    }

    /**
     * Кнопка диалога по тексту — для отсчётных подтверждений (msa «Отозвать (N с)»).
     *
     * Прежний поиск брал первый узел, чей текст содержит confirm-текст, и им
     * оказывалось СООБЩЕНИЕ диалога («…Отозвать разрешение?»): тап уходил по его
     * координатам, кнопка не нажималась, шаг падал `revoke_not_confirmed`
     * (прогон rmua0pt7i: экран всё ещё показывал «Отозвать (6 с)»).
     *
     * Порядок: кнопка с точной подписью (после снятия отсчётного хвоста) →
     * кнопка по роли (class Button / id button1|button2|button3).
     */
    internal fun findDialogConfirmButton(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        val exact = NodeTree.findAllInTree(root, predicate = { node ->
            node.isEnabled && isCountdownConfirmLabel(buttonLabel(node), texts)
        }).firstOrNull()
        if (exact != null) return clickableAncestorOrSelf(exact) ?: exact
        val byRole = NodeTree.findAllInTree(root, predicate = { node ->
            node.isEnabled && isDialogButtonNode(node) && matchesAny(node, texts)
        }).firstOrNull()
        return byRole?.let { clickableAncestorOrSelf(it) ?: it }
    }

    /** Подпись узла: text или contentDescription (MIUI часть кнопок подписывает только описанием). */
    private fun buttonLabel(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    /** Отсчётный текст MIUI: «Отозвать (9 с)», «Revoke (9s)» — кнопка ещё неактивна. */
    internal fun isCountdownLabel(label: String?): Boolean =
        COUNTDOWN_LABEL_REGEX.containsMatchIn(TextMatcher.normalize(label))

    /** Подпись кнопки совпадает с confirm-текстом после снятия отсчётного хвоста. */
    internal fun isCountdownConfirmLabel(label: String?, texts: List<String>): Boolean {
        if (label == null) return false
        val stripped = COUNTDOWN_LABEL_REGEX.replace(TextMatcher.normalize(label), "").trim()
        if (stripped.isEmpty()) return false
        return texts.any { TextMatcher.normalize(it) == stripped }
    }

    private suspend fun tapSystemDialogButton(
        texts: List<String>,
        requireEnabled: Boolean = false,
        avoidTexts: List<String> = emptyList()
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findDialogButton(root, texts, avoidTexts, requireEnabled)
        if (node != null) {
            // Подпись нажатой кнопки идёт в лог стены (`tapped='Согласен'`).
            lastDialogTapText = TextMatcher.normalize(
                node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
            )
            val tapped = tapNode(node)
            recycleNode(node); recycleNode(root)
            if (!tapped && requireEnabled) AppLog.w(TAG, "consent: button found but not tappable")
            return tapped
        }
        recycleNode(root)
        return false
    }

    /**
     * Тап по узлу, resource-id которого заканчивается одним из [ids]: крестик обманки
     * («upgrade_x_out» у апдейт-промпта GetApps) своей подписи не имеет, текстом его
     * не найти — закрываем по id. Кнопка «Обновить» не нажимается: её id в списке нет.
     */
    private suspend fun tapSystemNodeByIds(ids: List<String>): Boolean {
        if (ids.isEmpty()) return false
        val root = service.rootInActiveWindow ?: return false
        val node = NodeTree.findAllInTree(root, predicate = { n ->
            val id = n.viewIdResourceName ?: ""
            id.isNotEmpty() && ids.any { id.endsWith(it, ignoreCase = true) }
        }).firstOrNull { clickableAncestorOrSelf(it) != null }
        if (node == null) {
            recycleNode(root)
            AppLog.i(TAG, "consent: close id not found ids=$ids")
            return false
        }
        val target = clickableAncestorOrSelf(node) ?: node
        val tapped = tapNode(target)
        if (target !== node) recycleNode(target)
        recycleNode(node)
        recycleNode(root)
        AppLog.i(TAG, "consent: close by id ids=$ids ok=$tapped")
        return tapped
    }

    /**
     * Кнопка диалога: сначала узлы с button-ролью (button1/2/3 или класс Button),
     * затем прочие кликабельные. Узлы-маркеры ([avoidTexts]) и узлы заголовка/
     * сообщения не нажимаются никогда: «Закрыть принудительно?» ранее «закрывалось»
     * тапом по собственному заголовку (прогон rmu8lzcu9, filemanager).
     */
    private fun findDialogButton(
        root: AccessibilityNodeInfo?,
        texts: List<String>,
        avoidTexts: List<String>,
        requireEnabled: Boolean
    ): AccessibilityNodeInfo? {
        root ?: return null
        val button = findInTree(root) { node ->
            isDialogButtonNode(node) &&
                matchesAny(node, texts) &&
                !isDialogMarkerNode(node, avoidTexts) &&
                (!requireEnabled || node.isEnabled)
        }
        if (button != null) return clickableAncestorOrSelf(button) ?: button
        val fallback = findInTree(root) { node ->
            !isDialogMarkerNode(node, avoidTexts) &&
                matchesAny(node, texts) &&
                (!requireEnabled || node.isEnabled) &&
                clickableAncestorOrSelf(node) != null
        }
        return fallback?.let { clickableAncestorOrSelf(it) }
    }

    /** Заголовок, сообщение или маркер диалога — по таким узлам не тапаем. */
    private fun isDialogMarkerNode(node: AccessibilityNodeInfo, avoidTexts: List<String>): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("alertTitle") || id.endsWith("message")) return true
        return avoidTexts.isNotEmpty() && NodeTree.matchesAny(node, avoidTexts)
    }

    /** Button-роль: id кнопки AlertDialog или класс android.widget.Button. */
    private fun isDialogButtonNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName ?: ""
        if (id.endsWith("button1") || id.endsWith("button2") || id.endsWith("button3")) return true
        return node.className?.toString()?.contains("Button", ignoreCase = true) == true
    }

    /** Пакеты-цели шага: владелец такого диалога = само приложение шага. */
    private fun stepPackagesFor(step: SimpleSteps.Step): List<String> =
        (listOfNotNull(step.launchPackage) + SemanticCatalog.requiredPackages(step.id) + step.requiredPackages)
            .distinct()

    // ─── Navigation & Utilities ───────────────────────────────────────────
    private suspend fun resetSettingsToRoot(): Boolean {
        try {
            service.startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "resetSettingsToRoot: startActivity failed: ${e.message}")
            return false
        }
        delay(CONTENT_WAIT_MS)
        // Корень подтверждается совпадением >= 2 маркеров одновременно;
        // если ACTION_SETTINGS открыл не корень — возвращаемся назад (до 5 раз).
        repeat(5) {
            if (isSettingsRoot()) return true
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(UI_SETTLE_DELAY_MS)
        }
        return isSettingsRoot()
    }

    /** Корень Настроек: совпадение >= 2 маркеров одновременно (не одного). */
    private fun isSettingsRoot(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        recycleNode(root)
        val markers = listOf(
            "Поиск настроек", "О телефоне", "Настройки",
            "SIM-карты и мобильные сети", "Wi-Fi", "Bluetooth"
        )
        return markers.count { TextMatcher.normalizedContains(text, it) } >= 2
    }

    private suspend fun resetToHome(): Boolean {
        // GLOBAL_ACTION_HOME — нативный переход домой; в отличие от
        // ACTION_MAIN+CATEGORY_HOME не вызывает resolver «Главный экран по умолчанию».
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)
        // MIUI помнит последнюю страницу лаунчера: если это лента виджетов (App Vault),
        // папок рабочего стола на экране нет — шаг `folder_recommendations` видел
        // candidates=0 (прогон rmuk1h2al). Уводим на главную страницу: сначала повторным
        // HOME, при упорной ленте — свайпом влево (лента живёт слева от первой страницы).
        repeat(2) {
            if (!isAppVaultVisible()) return ok
            AppLog.i(TAG, "resetToHome: launcher on App Vault — returning to desktop page")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(500)
        }
        if (isAppVaultVisible()) {
            val dm = service.resources.displayMetrics
            performGesture(
                dm.widthPixels * 0.9f, dm.heightPixels * 0.6f,
                dm.widthPixels * 0.1f, dm.heightPixels * 0.6f,
                250
            )
            delay(500)
        }
        return ok
    }

    /**
     * Исполняет явный сценарий маршрута шага: интенты и тапы ровно в заданном порядке
     * (без merge-логики и без guard'ов «корень Настроек»/«чужой экран»). Возвращает
     * true, если маршрут дошёл до целевого экрана — тумблер ищет общий хвост шага;
     * false — вызывающий продолжает авто-навигацией (маршрут = подсказка).
     */
    internal suspend fun executeRouteScript(
        step: SimpleSteps.Step,
        route: List<SemanticCatalog.RouteItem>
    ): Boolean {
        for ((index, item) in route.withIndex()) {
            if (cancelled) return false
            if (!awaitOverlayReadyOrPause()) return false
            // Промо-диалог может всплыть ПОСРЕДИ маршрута (Проводник: «Новая функция
            // Доступна темная тема!» встала между 1/4 и 2/4 и перекрыла дерево —
            // прогон rmuod5cmm: route 2/4 more_action_btn ok=false без тапа).
            handleConsentWalls(step)
            val ok = when {
                item.intent != null -> startRouteIntent(item.intent)
                item.scroll -> {
                    scrollDownOnce()
                    true
                }
                item.tapText != null -> tapRouteNode(step, text = item.tapText)
                item.tapDesc != null -> tapRouteNode(step, desc = item.tapDesc)
                item.tapId != null -> tapRouteNode(step, id = item.tapId)
                else -> false
            }
            // Один ретрай тапа по тексту через 400 мс: drawer/список MIUI анимируется,
            // и первый тап может прийтись мимо строки (прогон rmumuqr53: route
            // filemanager 3/4 «Настройки» и 4/4 «Информация» = ok=false).
            var retried = false
            val finalOk = if (!ok && (item.tapText != null || item.tapDesc != null)) {
                delay(400)
                retried = true
                if (item.tapText != null) tapRouteNode(step, text = item.tapText)
                else tapRouteNode(step, desc = item.tapDesc!!)
            } else {
                ok
            }
            // Узел по id/тексту не найден (перекрыт диалогом или на другой сборке отдан
            // без id): пробуем запасной поиск по content-description.
            val fallbackOk = if (!finalOk && item.fallbackDesc != null) {
                AppLog.w(
                    TAG,
                    "route ${step.id}: ${index + 1}/${route.size} node_not_found " +
                        "id='${item.tapId.orEmpty()}' — retry desc='${item.fallbackDesc}'"
                )
                tapRouteNode(step, desc = item.fallbackDesc)
            } else {
                false
            }
            val resolvedOk = finalOk || fallbackOk
            val what = item.intent ?: item.tapText ?: item.tapDesc ?: item.tapId ?: "scroll"
            AppLog.i(
                TAG,
                "route ${step.id}: ${index + 1}/${route.size} '$what' ok=$resolvedOk" +
                    if (retried) " retry=true" else "" +
                        if (fallbackOk) " fallback=desc" else ""
            )
            StepDiagnostics.note(
                step.id,
                "ROUTE",
                "step=${index + 1} what='$what' ok=$resolvedOk" +
                    if (retried) " retry=true" else "" +
                        if (fallbackOk) " fallback=desc" else ""
            )
            // R2-2: intent-шаг обязан открыться. Не открылся (ActivityNotFound на чужой
            // сборке: карусель без fashiongallery) — на чужом экране тумблер не ищем:
            // маршрут честно отдаёт false, вызывающий уходит в авто-навигацию.
            if (item.intent != null && !resolvedOk) {
                AppLog.w(
                    TAG,
                    "route ${step.id}: ${index + 1}/${route.size} intent failed '$what' — fallback to auto-nav"
                )
                StepDiagnostics.note(step.id, "ROUTE", "intent_failed what='$what'")
                return false
            }
            delay(item.waitMs)
            // Поверх маршрута встаёт стена первого запуска (Проводник: «Добро пожаловать
            // в Проводник» перекрывает «Еще» → «Настройки» → «Информация»; Mi Браузер:
            // страницы мастера) либо обманка-промпт (GetApps: «Доступно обновление»).
            // Закрываем её ДО следующего действия, иначе тап уходит в стену, а шаг
            // объявляется неприменимым (прогон rmulhb4yq: filemanager route 2–4 ok=false,
            // browser_sys/getapps — drill_level_absent).
            val walls = handleConsentWalls(step)
            if (walls > 0) {
                AppLog.i(TAG, "route ${step.id}: consent walls handled=$walls before next action")
                delay(UI_SETTLE_DELAY_MS)
            }
            // D1/R2-2: intent-шаг обязан ПОДТВЕРДИТЬ целевой экран пакетом-владельцем и
            // маркерами (браузер открывал домашнюю ленту; карусель «подтверждалась» на
            // чужом экране Настроек). Не подтвердилось после одного relaunch — маршрут
            // отдаёт false: тумблер на чужом экране не ищем, шаг идёт авто-навигацией.
            if (item.intent != null && !confirmRouteIntentScreen(step, item)) return false
        }
        return true
    }

    /**
     * Подтверждение экрана после intent-шага маршрута (D1/R2-2): маркеры из каталога
     * (`confirmMarkers`, иначе маркеры шага) И, если задан, `confirmPackage` — пакет
     * окна. Пакет проверяется ВМЕСТЕ с маркерами в одном поллинге: после интента окно
     * меняется не мгновенно, разовая проверка давала ложный провал на живом устройстве.
     * Пустые наборы подтверждения не требуют.
     */
    internal suspend fun awaitRouteScreen(
        step: SimpleSteps.Step,
        item: SemanticCatalog.RouteItem,
        timeoutMs: Long = ROUTE_SCREEN_WAIT_MS
    ): Boolean {
        val markers = item.confirmMarkers.ifEmpty { SemanticCatalog.screenMarkers(step.id) }
        val packages = item.confirmPackage
        if (markers.isEmpty() && packages.isEmpty()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (routeScreenMatches(markers, packages)) return true
            if (cancelled) return false
            delay(ROUTE_SCREEN_POLL_MS)
        }
        return false
    }

    /**
     * Экран маршрута опознан: пакет окна ∈ `confirmPackage` (если задан) И маркеры
     * (если заданы). Отдельный от [awaitRouteScreen] шаг нужен, чтобы оба условия
     * читались из ОДНОГО снимка окна.
     */
    private fun routeScreenMatches(markers: List<String>, packages: List<String>): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val text = collectAllText(root)
        val fg = root.packageName?.toString()
        recycleNode(root)
        val packageOk = packages.isEmpty() ||
            packages.any { it.equals(fg, ignoreCase = true) }
        val markersOk = markers.isEmpty() ||
            markers.any { TextMatcher.normalizedContains(text, it) }
        return packageOk && markersOk
    }

    /**
     * Подтверждение экрана intent-шага маршрута с одной повторной попыткой (D1, R2-2):
     * не подтвердилось — relaunch (компонента, затем запасное действие из каталога),
     * иначе false: тумблер на чужом экране не ищем (прогон rmuojptft).
     */
    internal suspend fun confirmRouteIntentScreen(
        step: SimpleSteps.Step,
        item: SemanticCatalog.RouteItem
    ): Boolean {
        if (awaitRouteScreen(step, item)) return true
        AppLog.w(TAG, "route ${step.id}: screen unconfirmed — relaunch")
        StepDiagnostics.note(step.id, "ROUTE", "screen_unconfirmed")
        relaunchRouteIntent(item)
        if (awaitRouteScreen(step, item, ROUTE_SCREEN_RETRY_WAIT_MS)) {
            AppLog.i(TAG, "route ${step.id}: screen confirmed after relaunch")
            return true
        }
        AppLog.w(
            TAG,
            "route ${step.id}: screen unconfirmed after relaunch — fallback to auto-nav " +
                "without touching a foreign screen"
        )
        StepDiagnostics.note(step.id, "ROUTE", "screen_unconfirmed after_relaunch")
        return false
    }

    /**
     * Один relaunch intent-шага: сначала та же компонента, затем запасное действие
     * (`RouteItem.fallbackIntent`, вердикт probe OK). Оба исхода логируются.
     */
    private fun relaunchRouteIntent(item: SemanticCatalog.RouteItem): Boolean {
        val byComponent = item.intent?.let { startRouteIntent(it) } == true
        val action = item.fallbackIntent
        val byAction = action?.let { startRouteIntent(it) } == true
        AppLog.i(
            TAG,
            "route relaunch: component='${item.intent}' ok=$byComponent " +
                "action='$action' ok=$byAction"
        )
        return byComponent || byAction
    }

    /**
     * Доп. цели варианта, обязанные отработать ДО главного тумблера (карусель: строки
     * подменю пропадают вместе с главным тумблером). Раньше блок жил только в
     * drill-ветке, а шаги с RouteScript уходили в [executeRouteScript] и возвращались
     * сразу в [findAndToggleSwitch] — доп. цели не выполнялись вовсе (прогон rmuod5cmm:
     * карусель выключила 1 тумблер из 5 и отчиталась OK).
     */
    private suspend fun runPreMainExtras(step: SimpleSteps.Step) {
        val preTargets = SemanticCatalog.extraTargetsBeforeMain(step.id)
        if (preTargets.isEmpty()) return
        val markers = SemanticCatalog.screenMarkers(step.id)
        // Экран читаем ЗДЕСЬ (живой), а не из снимка до route: на входе в шаг активным
        // было окно прошлого шага (прогон rmuod5cmm: PERCEPTION pkg=com.google.android.gms).
        val liveScreen = currentScreenText()
        val markersOk = markers.isEmpty() ||
            markers.any { TextMatcher.normalizedContains(liveScreen, it) }
        StepDiagnostics.note(
            step.id, "EXTRA",
            "pre_targets=${preTargets.size} markers_ok=$markersOk"
        )
        if (!markersOk) {
            AppLog.w(TAG, "extra targets skipped: markers not matched step=${step.id}")
            return
        }
        runExtraTargets(step, preTargets)
    }

    /** Интент сценария: `pkg/Class` (явная компонента) либо action. */
    private fun startRouteIntent(spec: String): Boolean = try {
        val intent = if (spec.contains('/')) {
            val pkg = spec.substringBefore('/')
            val cls = spec.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it }
            Intent().setComponent(android.content.ComponentName(pkg, cls))
        } else {
            Intent(spec)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        true
    } catch (e: Exception) {
        AppLog.w(TAG, "route intent failed: '$spec' — ${e.message}")
        false
    }

    /**
     * Тап узла сценария по тексту, описанию или суффиксу resource-id. Узел может быть
     * ниже сгиба («Использование и диагностика» в Конфиденциальности) — до 3 попыток
     * с прокруткой.
     */
    private suspend fun tapRouteNode(
        step: SimpleSteps.Step,
        text: String? = null,
        desc: String? = null,
        id: String? = null
    ): Boolean {
        repeat(3) { attempt ->
            val root = service.rootInActiveWindow ?: return false
            val nodes = NodeTree.findAllInTree(root, predicate = { node ->
                val matches = when {
                    text != null -> TextMatcher.normalizedContains(node.text?.toString(), text)
                    desc != null ->
                        TextMatcher.normalizedContains(node.contentDescription?.toString(), desc)
                    id != null -> node.viewIdResourceName?.endsWith(id) == true
                    else -> false
                }
                matches && clickableAncestorOrSelf(node) != null
            })
            val node = nodes.firstOrNull { n ->
                // Точное совпадение приоритетнее вхождения: «Настройки» не должно
                // матчить «Сбросить настройки приложений» (иначе маршрут жмёт соседний
                // пункт меню — прогон rmuk3w8a5, шаг sys_recommendations).
                val label = when {
                    text != null -> n.text?.toString()
                    desc != null -> n.contentDescription?.toString()
                    else -> null
                }.orEmpty()
                val query = text ?: desc ?: id ?: ""
                TextMatcher.normalizedContains(label, query) &&
                    TextMatcher.normalizedContains(query, label)
            } ?: nodes.firstOrNull()
            if (node != null) {
                nodes.forEach { if (it !== node) recycleNode(it) }
                val target = clickableAncestorOrSelf(node) ?: node
                val tapped = tapNode(target)
                if (target !== node) recycleNode(target)
                recycleNode(node)
                recycleNode(root)
                return tapped
            }
            recycleNode(root)
            if (attempt < 2) scrollDownOnce()
        }
        return false
    }

    /**
     * Лента виджетов (App Vault) на экране: её карточки приходят из
     * `com.mi.android.globalminusscreen`, хотя окно принадлежит лаунчеру — поэтому
     * признак ищется по resource-id в дереве, а не по активному пакету.
     */
    private fun isAppVaultVisible(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val nodes = NodeTree.findAllInTree(root, predicate = { node ->
            node.viewIdResourceName?.contains("globalminusscreen") == true
        })
        val found = nodes.isNotEmpty()
        nodes.forEach { recycleNode(it) }
        recycleNode(root)
        return found
    }

    private suspend fun swipeUp() {
        val dm = service.resources.displayMetrics
        performGesture(
            dm.widthPixels / 2f,
            dm.heightPixels * 0.8f,
            dm.widthPixels / 2f,
            dm.heightPixels * 0.2f,
            300
        )
    }

    /** Поиск тумблера вынесен в [SwitchFinder] (3 прохода + нечёткий рубеж). */
    private fun findSwitchByText(
        root: AccessibilityNodeInfo?,
        texts: List<String>
    ): AccessibilityNodeInfo? = SwitchFinder.findSwitch(root, texts)

    /**
     * Поиск тумблера строки с прокруткой: строка может стоять на самой кромке экрана
     * (Проводник «Безопасность» → «Персонализация услуг»: Switch `[0,1995][1080,2179]`
     * при экране 2179 — подпись вообще не попадала в дерево, и цель объявлялась
     * выполненной, хотя тумблер остался включённым; прогон rmuod5cmm).
     */
    internal suspend fun findSwitchByTextWithScroll(
        texts: List<String>,
        attempts: Int = SWITCH_FALLBACK_SCROLLS,
        logLabel: String? = null
    ): AccessibilityNodeInfo? {
        var stalled = 0
        for (attempt in 0 until attempts) {
            val root = service.rootInActiveWindow ?: return null
            val found = findSwitchByText(root, texts)
            recycleNode(root)
            if (found != null) return found
            if (logLabel != null) {
                AppLog.i(TAG, "switch: scroll attempt ${attempt + 1}/$attempts for '$logLabel'")
            }
            val changed = scrollDownVerified()
            stalled = if (changed) 0 else stalled + 1
            if (stalled >= ROW_SCROLL_STALL_LIMIT) return null
        }
        return null
    }

    /**
     * Главный тумблер шага уже выключен. Нужен, чтобы отличить законное исчезновение
     * зависимой строки (карусель: строки подменю пропадают вместе с главным тумблером)
     * от промаха автоматизации, когда строка на устройстве есть, но не найдена.
     */
    private fun mainSwitchIsOff(step: SimpleSteps.Step): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findSwitchByText(root, searchTextsFor(step))
        val state = node?.let { SwitchFinder.isChecked(it) }
        recycleNode(node); recycleNode(root)
        return state == false
    }

    private fun isSwitchLike(node: AccessibilityNodeInfo): Boolean =
        SwitchFinder.isSwitchLike(node)

    /** Обход дерева вынесен в [NodeTree]. */
    private fun findInTree(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? = NodeTree.findInTree(root, predicate)

    /** Поиск тумблера рядом с подписью вынесен в [SwitchFinder]. */
    private fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        SwitchFinder.findSwitchNear(node)

    /** Нормализованное сравнение текста/описания узла вынесено в [NodeTree]. */
    private fun matchesAny(
        node: AccessibilityNodeInfo,
        texts: List<String>,
        fuzzy: Boolean = false
    ): Boolean = NodeTree.matchesAny(node, texts, fuzzy)

    /** Ближайший кликабельный узел вынесен в [NodeTree]. */
    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        NodeTree.clickableAncestorOrSelf(node)

    /** Кликабельный узел, чьи text/contentDescription ТОЧНО равны одной из подписей уровня. */
    private fun findExactClickableByText(
        root: AccessibilityNodeInfo,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        val node = NodeTree.findInTree(root) { n ->
            val text = n.text?.toString()
            val desc = n.contentDescription?.toString()
            texts.any { t ->
                t.isNotBlank() &&
                    (TextMatcher.normalizedEquals(text, t) || TextMatcher.normalizedEquals(desc, t))
            } && clickableAncestorOrSelf(n) != null
        }
        return node?.let { clickableAncestorOrSelf(it) }
    }

    private fun findClickableByText(
        root: AccessibilityNodeInfo? = service.rootInActiveWindow,
        texts: List<String>
    ): AccessibilityNodeInfo? {
        root ?: return null
        return findInTree(root) { matchesAny(it, texts) && clickableAncestorOrSelf(it) != null }
            ?.let { clickableAncestorOrSelf(it) }
    }

    // internal — для тестируемости (SimpleRunnerScrollTest: строка ниже сгиба).
    internal suspend fun findClickableByTextWithScroll(
        texts: List<String>,
        attempts: Int = SWITCH_FALLBACK_SCROLLS,
        logLabel: String? = null
    ): AccessibilityNodeInfo? {
        var stalled = 0
        for (attempt in 0 until attempts) {
            findClickableByText(texts = texts)?.let { return it }
            val root = service.rootInActiveWindow ?: return null
            recycleNode(root)
            if (logLabel != null) {
                AppLog.i(TAG, "drill: scroll attempt ${attempt + 1}/$attempts for '$logLabel'")
            }
            val changed = scrollDownVerified()
            stalled = if (changed) 0 else stalled + 1
            if (stalled >= ROW_SCROLL_STALL_LIMIT) {
                // Экран не двигается (поповер/ViewPager/жест не дошёл): дальнейшие
                // прокрутки бесполезны — выходим сразу, а не «до конца попыток».
                AppLog.w(TAG, "drill: scroll stalled for '$logLabel'")
                break
            }
        }
        return findClickableByText(texts = texts)
    }

    /**
     * Первый прокручиваемый контейнер ПОД окном. Прежняя версия поднималась вверх
     * по `parent` и всегда возвращала null (у окна нет scrollable-предка): drill и
     * поиск тумблеров не прокручивали экран вовсе — «Приложения» на корне Настроек
     * и тумблеры ниже сгиба не находились (прогон rmu8lzcu9).
     */
    internal fun findScrollableContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val containers = NodeTree.findAllInTree(root, predicate = { it.isScrollable })
        if (containers.isEmpty()) return null
        // Предпочитаем вертикальный контейнер (основной список), а не строку вкладок:
        // на экране Тем первым в дереве идёт горизонтальный таб-стрип, и прокрутка
        // уходила в него (прогон rmu8qhjhi).
        val vertical = containers.firstOrNull { c ->
            val r = Rect().also { c.getBoundsInScreen(it) }
            r.height() > r.width()
        }
        return vertical ?: containers.first()
    }

    /** Есть ли на экране прокручиваемый контейнер (список настроек, а не поповер). */
    private fun hasScrollableScreen(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val container = findScrollableContainer(root)
        recycleNode(container)
        recycleNode(root)
        return container != null
    }

    /**
     * Прокрутка вправо горизонтального контейнера: вкладка цели может быть за правым
     * краем (Темы: вкладка «Профиль» — прогон rmu8qhjhi). Вертикальные контейнеры
     * не трогаются.
     */
    private suspend fun scrollRightOnce(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val containers = NodeTree.findAllInTree(root, predicate = { it.isScrollable })
        val horizontal = containers.firstOrNull { c ->
            val r = Rect().also { c.getBoundsInScreen(it) }
            r.width() > r.height() * 2
        }
        val scrolled = horizontal?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        containers.forEach { recycleNode(it) }
        recycleNode(root)
        if (scrolled) {
            AppLog.i(TAG, "scroll: container right")
            delay(400)
        }
        return scrolled
    }

    /**
     * Прокрутка экрана вниз. Контейнер прокручивается action'ом, а если его нет —
     * жестом: списки MIUI бывают без ScrollView-предка, доступного accessibility.
     */
    private suspend fun scrollDownOnce() {
        val root = service.rootInActiveWindow ?: return
        val scrollable = findScrollableContainer(root)
        val byContainer = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        recycleNode(scrollable); recycleNode(root)
        if (!byContainer) swipeUp()
        AppLog.i(TAG, "scroll: ${if (byContainer) "container" else "gesture"} down")
        delay(400)
    }

    /**
     * Прокрутка с проверкой эффекта: контейнер списка может ответить `false`
     * (Cleaner/App Vault: `scroll: gesture down` четыре раза подряд, экран не
     * сдвинулся — шаг не нашёл строку ниже сгиба, прогон rmulhb4yq), а инъекция
     * может уйти в никуда. Возвращает true, если текст экрана фактически изменился.
     */
    private suspend fun scrollDownVerified(): Boolean {
        val before = currentScreenText()
        scrollDownOnce()
        val after = currentScreenText()
        return before.isNotBlank() && after.isNotBlank() && after != before
    }

    /**
     * Прокрутка вниз до появления любой из строк [texts]: тумблер или маркер целевого
     * экрана мог оказаться ниже сгиба (Mi Music: «Показывать рекламу» в разделе
     * «Дополнительные настройки»). Возвращает true, если строка появилась на экране.
     * Прокрутка без сдвига экрана прекращает цикл: бюджет шага не жжём впустую.
     */
    private suspend fun scrollUntilScreenHasAny(texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        var stalled = 0
        for (attempt in 0 until SWITCH_FALLBACK_SCROLLS) {
            if (cancelled) return false
            val changed = scrollDownVerified()
            if (screenHasAny(texts)) return true
            stalled = if (changed) 0 else stalled + 1
            if (stalled >= ROW_SCROLL_STALL_LIMIT) {
                AppLog.w(TAG, "scroll: no effect after ${attempt + 1} attempt(s) — stop")
                return false
            }
        }
        return false
    }

    /** Ожидание экрана вынесено в [ComponentVerifier] (маркеры + нечёткий рубеж). */
    private suspend fun awaitScreen(markers: List<String>, timeoutMs: Long = 3000L): Boolean =
        ComponentVerifier.awaitScreen(
            service = service,
            markers = markers,
            timeoutMs = timeoutMs,
            minMatches = 1,
            isCancelled = { cancelled }
        )

    /** Сбор текста экрана вынесен в [NodeTree]. */
    private fun collectAllText(node: AccessibilityNodeInfo?): String = NodeTree.collectText(node)

    /** Актуальный текст активного экрана (проверка смены экрана после тапа). */
    private fun currentScreenText(): String {
        val root = service.rootInActiveWindow ?: return ""
        val text = collectAllText(root)
        recycleNode(root)
        return text
    }

    /**
     * Текст экрана без тулбара (`action_bar*`): заголовок «Конфиденциальность» не
     * должен считаться ни уровнем маршрута, ни признаком целевого экрана.
     */
    private fun collectBodyText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > NodeTree.DEFAULT_MAX_DEPTH) return
            if (n.viewIdResourceName?.contains("action_bar") == true) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }

    private suspend fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName ?: ""
        val bounds = "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AppLog.i(TAG, "tap: $cls id=$id clickable=true bounds=$bounds via=node")
            return true
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLog.i(TAG, "tap: $cls id=$id clickable=false bounds=$bounds via=parent depth=${depth + 1}")
                return true
            }
            parent = parent.parent
            depth++
        }
        // ACTION_CLICK не сработал: координатный повтор ТОГО ЖЕ узла — строго внутри
        // passthrough-окна. Прежний порядок (сначала жест, окно позже) давал
        // `touch intercepted` и потерю тапа (прогон rmumuqr53).
        val retried = withOverlayPassthrough { tapAtRaw(rect.centerX(), rect.centerY()) }
        if (retried) {
            AppLog.i(
                TAG,
                "tap: coordinate retry inside passthrough bounds=$bounds " +
                    "id=$id center=(${rect.centerX()},${rect.centerY()})"
            )
            return true
        }
        AppLog.i(
            TAG,
            "tap: $cls id=$id clickable=${node.isClickable} bounds=$bounds " +
                "via=gesture center=(${rect.centerX()},${rect.centerY()})"
        )
        return performGesture(
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            rect.centerX().toFloat(),
            rect.centerY().toFloat(),
            100
        )
    }

    /** Жест с временно снятым поглощением тапов оверлеем (см. [withOverlayPassthrough]). */
    private suspend fun performGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = withOverlayPassthrough(durationMs) {
        performGestureRaw(startX, startY, endX, endY, durationMs)
    }

    private suspend fun performGestureRaw(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val dispatched =
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(g: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    /** Установлен/виден ли пакет: getPackageInfo + видимость через launcher-интент. */
    private fun isInstalled(pkg: String): Boolean = ActivityScanner.isPackageVisible(service, pkg)

    /** Пакеты-кандидаты шага: семантика каталога + legacy requiredPackages. */
    private fun candidatePackages(step: SimpleSteps.Step): List<String> =
        (SemanticCatalog.requiredPackages(step.id) + step.requiredPackages).distinct()


    // Легаси: recycle() deprecated с API 33 (система перерабатывает узлы автоматически),
    // но на Android 10–12 возвращает узел в пул — поэтому версионный guard.
    private fun recycleNode(node: AccessibilityNodeInfo?) {
        node ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // checked_before + гейт целостности оверлея (коммит 3: discovery/откат)
    // ══════════════════════════════════════════════════════════════

    /**
     * Хранилище снапшота отката (DataStore через приложение).
     * В unit-тестах mock-сервис не даёт Application → null, запись пропускается.
     */
    private val prefs: PreferencesManager? by lazy {
        runCatching { (service.applicationContext as? XiaoHyperApp)?.preferencesManager }
            .getOrNull()
    }

    /** Фиксирует фактическое состояние тумблера (checked_before) в снапшоте отката.
     *  Не блокирует шаг: запись неблокирующая (in-memory зеркало + фоновый persist). */
    private fun recordCheckedBefore(stepId: String, checkedBefore: Boolean) {
        val store = prefs ?: return
        runCatching { store.recordSimpleToggleState(stepId, checkedBefore) }
            .onFailure { AppLog.w(TAG, "recordCheckedBefore failed: ${it.message}") }
    }

    /**
     * Гейт оверлея (Аддендум A4): ждёт до 2 с восстановления окна прогресса.
     * Не восстановилось — ставит статус «пауза» и сообщает false (шаг не выполняем).
     * В канале отката гейт выключен ([overlayGateRequired] = false): окна прогресса
     * там нет по замыслу.
     */
    // internal — для тестируемости (SimpleRunnerOverlayGateTest).
    internal suspend fun awaitOverlayReadyOrPause(): Boolean {
        if (!overlayGateRequired) return true
        if (OverlayController.isOverlaySolid()) return true
        var waited = 0L
        while (waited < OVERLAY_GATE_WAIT_MS && !OverlayController.isOverlaySolid()) {
            delay(OVERLAY_GATE_POLL_MS)
            waited += OVERLAY_GATE_POLL_MS
        }
        if (OverlayController.isOverlaySolid()) {
            AppLog.i(TAG, "overlay solid again after ${waited}ms")
            return true
        }
        AppLog.w(TAG, "overlay not solid after ${waited}ms — pausing step")
        val text = runCatching { service.getString(R.string.overlay_paused) }
            .getOrNull() ?: "overlay unavailable"
        OverlayController.updateStatus(service, text)
        return false
    }
}
