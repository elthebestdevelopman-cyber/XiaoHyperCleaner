package com.xiaohypercleaner.data

import android.content.Context
import com.xiaohypercleaner.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Семантическая таблица шагов (`assets/catalog/semantic_steps.json`).
 *
 * Единственный источник семантики: keywords, screenMarkers, actionType,
 * sequenceKind, consentTexts, overflowMenuLabels, fallbackDrillPath, 7 локалей.
 * Захардкоженные маршруты остаются подсказками (fallbackDrillPath),
 * `SimpleSteps.searchTexts` — deprecated-fallback на один релиз.
 */
object SemanticCatalog {

    private const val TAG = "semantic"
    private const val ASSET_PATH = "catalog/semantic_steps.json"

    /** Поддерживаемые локали каталога (паритет с resources values-<locale>). */
    val LOCALES: List<String> = listOf("ru", "en", "es", "zh", "hi", "pt", "id")

    enum class ActionType {
        TOGGLE, TAP_CONFIRM, SEQUENCE, SKIP;

        companion object {
            fun from(raw: String?): ActionType = when (raw?.lowercase(Locale.ROOT)) {
                "tap_confirm" -> TAP_CONFIRM
                "sequence" -> SEQUENCE
                "skip" -> SKIP
                else -> TOGGLE
            }
        }
    }

    enum class SequenceKind {
        CONSENT_WALL, DELAYED_CONFIRM, TAP_FALLBACK_SEQUENCE, CLEAR_DATA_DECLINE, OVERFLOW_MENU;

        companion object {
            fun from(raw: String?): SequenceKind? = entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true)
            }
        }
    }

    data class SemanticStep(
        val id: String,
        val titles: Map<String, String>,
        val keywords: Map<String, List<String>>,
        val screenMarkers: Map<String, List<String>>,
        val actionType: ActionType,
        val sequenceKind: SequenceKind?,
        val confirmTexts: Map<String, List<String>>,
        val tapFallbackTexts: Map<String, List<String>>,
        val consentTexts: Map<String, List<String>>,
        val overflowMenuLabels: Map<String, List<String>>,
        val confirmWaitMs: Long,
        val maxConsentIterations: Int,
        val launchPackage: String?,
        /** Сценарий маршрута уровня шага (когда у шага нет вариантов). */
        val route: List<RouteItem> = emptyList(),
        val skipReason: String?,
        val fallbackDrillPath: List<List<String>>,
        /**
         * Шаг принимает welcome-стены. У route-шагов — false: их рабочие экраны
         * (drawer Проводника, список файлов) совпадают словами с welcomeActions,
         * и «принятие» стены ломает маршрут (прогон rmumuqr53).
         */
        val welcomeAllowed: Boolean = true,
        /**
         * Приложение шага при запуске проигрывает промо-ролик со звуком (Mi Video):
         * медиа-громкость глушится на время шага и возвращается в конце.
         */
        val muteMediaOnLaunch: Boolean = false,
        /**
         * Решение по welcome-стене ЭТОГО шага: "accept" — принимать её даже когда
         * текст стены совпал со screenMarkers шага. Стена «Загрузок» — системный
         * `PrivacyGrantDialog` («Условия использования», кнопки «Отмена»/«Согласен»),
         * в тексте которого есть слово «Загрузки»: без этого поля правило стены
         * отступало, и шаг уходил на «запустите приложение вручную»
         * (дамп `diag-dumps/fresh2/g_downloads_wall.xml`).
         */
        val welcomeDecision: String = "",
        /** Кнопки согласия стены шага (ru — точная строка устройства). */
        val welcomeAcceptTexts: Map<String, List<String>> = emptyMap(),
        /** Тексты «пропуска» стены шага: согласием не считаются и не тапаются. */
        val welcomeSkipTexts: Map<String, List<String>> = emptyMap(),
        val safe: Boolean,
        val localeCoverage: List<String>,
        /** Пакеты-цели шага (видимость задаётся <queries> манифеста). */
        val requiredPackages: List<String> = emptyList(),
        /**
         * Подсказки имени папки рабочего стола (7 локалей): «Рекомендуемое», «Рекомендации»,
         * «Recommended», country-имя региона задаётся отдельно. Имя — только ПОДСКАЗКА:
         * истина — наличие секции рекомендаций внутри папки, поэтому переименование
         * на прошивке не ломает поиск.
         */
        val folderNames: Map<String, List<String>> = emptyMap(),
        /** Имя региональной папки-предустановки (RU → Russia), ключ — код региона. */
        val folderNamesByRegion: Map<String, List<String>> = emptyMap(),
        /** Варианты шага под версии оболочки (variants[] каталога). */
        val variants: List<StepVariant> = emptyList(),
        /**
         * Destructive-действие шага (disable/uninstall/clear_data/force_stop)
         * для гейта красного списка neverTouchPackages.
         */
        val destructiveAction: String? = null
    )

    /**
     * Вариант шага под диапазон версий оболочки: свои drillPath, screenMarkers и
     * itemTexts (подписи тумблеров). Выбирается по [RomProfile.family]/uiVersion.
     */
    data class StepVariant(
        val id: String,
        val family: RomFamily,
        val uiMin: Double,
        val uiMax: Double,
        /** Вариант последней надежды: применяется при нераспознанной версии. */
        val fallback: Boolean,
        val drillPath: List<List<String>>,
        val screenMarkers: Map<String, List<String>>,
        val itemTexts: Map<String, List<String>>,
        val tapFallbackTexts: Map<String, List<String>>,
        val consentTexts: Map<String, List<String>>,
        val control: ActionType?,
        val extraTargets: List<VariantExtraTarget>,
        val fallbackAction: String?,
        /** Явный сценарий маршрута (RouteScript) для этой прошивки; пусто — обычная логика. */
        val route: List<RouteItem> = emptyList(),
        /** Точка входа: "settings" — маршрут через Настройки, а не через приложение. */
        val entry: String?,
        /** Подсказки имени папки рабочего стола на уровне варианта (если отличаются). */
        val folderNames: Map<String, List<String>> = emptyMap(),
        /**
         * Тексты кнопки-отказа диалога, который оболочка показывает ПОСЛЕ главного
         * тумблера (Карусель обоев: «Нет, спасибо»). Тап по этой кнопке — часть шага:
         * без него тумблер остаётся включённым, а шаг падает verify_failed
         * (дамп diag-dumps/fresh/carousel_after_tap1.xml).
         */
        val toggleDeclineTexts: Map<String, List<String>> = emptyMap()
    )

    /**
     * Один шаг явного сценария маршрута (RouteScript).
     *
     * Сценарий нужен там, где авто-маршрут (merge legacy/variant/adaptive + guard'ы
     * «корень Настроек»/«чужой экран») уводит шаг не туда: в него попадает ТОЛЬКО то,
     * что проверено руками на конкретной прошивке. Тумблер после сценария ищется
     * обычным механизмом (гейт уверенности + checked_before).
     */
    data class RouteItem(
        /** Явная компонента `pkg/Class` или action — открывается как есть. */
        val intent: String? = null,
        /** Текст узла (тап с прокруткой до него). */
        val tapText: String? = null,
        /** content-description узла (иконки/☰ без текста). */
        val tapDesc: String? = null,
        /** Суффикс resource-id узла (`more`, `button1`). */
        val tapId: String? = null,
        /** Одна прокрутка вниз перед следующим шагом. */
        val scroll: Boolean = false,
        /** Пауза после действия. */
        val waitMs: Long = 700L
    )

    /**
     * Дополнительная цель варианта (второй тумблер/второй экран):
     * `back` нажатий «Назад» → drillPath → itemTexts → тумблер.
     */
    data class VariantExtraTarget(
        val back: Int,
        val drillPath: List<List<String>>,
        val itemTexts: Map<String, List<String>>,
        val control: ActionType?,
        /**
         * Кнопки подтверждения ДИАЛОГА ЦЕЛИ: MIUI спрашивает подтверждение именно у
         * отдельной строки («Проведите вправо по Экрану блокировки» →
         * «Выключить карусель экрана блокировки?» → «Подтвердить», дамп car_dlg2.xml).
         * Пустой список = подтверждения у цели нет.
         */
        val confirmTexts: Map<String, List<String>> = emptyMap(),
        /** Целевое состояние тумблера цели: по умолчанию выключить (как раньше). */
        val targetChecked: Boolean = false,
        /**
         * Цель обязана отработать ДО главного тумблера: на MIUI 13 строка исчезает
         * после его выключения (Карусель: «Обновлять через мобильный интернет»
         * пропадает, как только карусель выключена — дамп carousel_optout_wait).
         */
        val beforeMain: Boolean = false
    )

    /** Выбранный вариант шага + признак применения фолбэка (для лога `rom: variant=`). */
    data class VariantSelection(val variant: StepVariant, val fallback: Boolean)

    /** Целевая ОС для выбора вариантов (семейство + числовая версия). */
    data class OsTarget(val family: RomFamily, val ui: Double)

    /** Ручная памятка (`manualSteps[]` каталога): то, что автоматизация не делает. */
    data class ManualStep(
        val id: String,
        val titles: Map<String, String>,
        val bodies: Map<String, String>,
        val warning: Boolean
    )

    /** Политика обработки системных диалогов (deny по умолчанию). */
    data class ConsentPolicy(
        val welcomeMarkers: Map<String, List<String>>,
        val welcomeActions: Map<String, List<String>>,
        val permissionMarkers: Map<String, List<String>>,
        val defaultDecision: String,
        val denyTexts: Map<String, List<String>>,
        val allowTexts: Map<String, List<String>>,
        val allowOverrides: Set<String>,
        val maxIterationsPerStep: Int,
        /** Чекбоксы согласия («Выбрать все», «(обязательно)»). */
        val checkboxTexts: Map<String, List<String>> = emptyMap(),
        /** Маркеры диалогов-заглушек внутри шага («Произошла ошибка сети»). */
        val dismissMarkers: Map<String, List<String>> = emptyMap(),
        /** Кнопки закрытия диалогов-заглушек («Нет, спасибо», «Понятно»). */
        val dismissTexts: Map<String, List<String>> = emptyMap(),
        /** Маркеры системного диалога «Закрыть принудительно?» (force-stop). */
        val forceStopMarkers: Map<String, List<String>> = emptyMap(),
        /** Маркеры диалога отчёта о сбое приложения. */
        val crashReportMarkers: Map<String, List<String>> = emptyMap(),
        /** Маркеры диалога «Установить … по умолчанию?» (браузер и лаунчер). */
        val defaultAppMarkers: Map<String, List<String>> = emptyMap(),
        /**
         * Обманки-промпты (апдейт-промпт GetApps и подобные): закрываются, а не
         * принимаются — «Обновить»/«Скачать» на них не нажимаем никогда.
         */
        val decoyMarkers: Map<String, List<String>> = emptyMap(),
        /** Resource-id крестиков закрытия обманок (машинные, без локалей). */
        val decoyCloseIds: List<String> = emptyList(),
        /** Решение для диалога, которым владеет приложение шага (accept). */
        val appOwnedDecision: String = "accept",
        /**
         * Чекбоксы персонализации, снимаемые ДО тапа согласия (ключ — id шага):
         * стена Mi Браузера `cb_service` и стена Тём `cb_personal` отмечены по
         * умолчанию, и согласие «как есть» включало бы сбор данных
         * (дампы `diag-dumps/fresh/fresh_browser_after_consent.xml`,
         * `diag-dumps/fresh/fresh_now2.xml`).
         */
        val uncheckIds: Map<String, List<String>> = emptyMap(),
        /** Подписи тех же чекбоксов — для лога `decision=checkbox_unchecked text='…'`. */
        val uncheckTexts: Map<String, List<String>> = emptyMap()
    )

    @Volatile
    private var stepsById: Map<String, SemanticStep> = emptyMap()

    @Volatile
    private var loadedSteps: List<SemanticStep> = emptyList()

    @Volatile
    private var consentPolicy: ConsentPolicy? = null

    @Volatile
    private var isLoaded: Boolean = false

    /** Целевая ОС активного прогона (задаётся PlanBuilder через [selectVariant]). */
    @Volatile
    private var activeOs: OsTarget? = null

    /** Варианты, для которых уже писали лог выбора (один лог на шаг). */
    private val variantLogged: MutableSet<String> = HashSet()

    @Volatile
    private var manualStepsRaw: List<ManualStep> = emptyList()

    @Volatile
    private var neverTouch: Set<String> = emptySet()

    private val lock = Any()

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(lock) {
            if (isLoaded) return
            load(context)
        }
    }

    fun all(): List<SemanticStep> = loadedSteps

    fun step(id: String): SemanticStep? = stepsById[id]

    fun policy(): ConsentPolicy? = consentPolicy

    // ─── Варианты по версиям ОС ──────────────────────────────────────────

    private const val UI_EPS = 0.001

    /**
     * Выбирает вариант шагов под профиль прошивки. Вызывается один раз на прогон
     * (PlanBuilder) до рантайма: все accessors далее отдают эффективные значения.
     */
    fun selectVariant(profile: RomProfile) {
        activeOs = OsTarget(profile.family, profile.uiOrdinal)
        variantLogged.clear()
        AppLog.i(
            TAG,
            "rom: family=${profile.family} ui=${profile.uiVersion ?: "unknown"} " +
                "variant=${selectedVariantTag()} fallback=${selectedVariantFallback()}"
        )
    }

    fun osTarget(): OsTarget? = activeOs

    /** Выбранный вариант шага (null — шаг использует базовые поля каталога). */
    fun selection(id: String): VariantSelection? {
        val selection = selectionFor(step(id), activeOs) ?: return null
        val log = synchronized(lock) { variantLogged.add(id) }
        if (log) {
            AppLog.i(TAG, "rom: variant=${selection.variant.id} fallback=${selection.fallback} step=$id")
        }
        return selection
    }

    internal fun selectionFor(step: SemanticStep?, os: OsTarget?): VariantSelection? =
        resolveVariant(step?.variants.orEmpty(), os)

    /**
     * Разрешение варианта (чистая функция — тестируется без Android):
     * 1. точное совпадение семейства и диапазона версий;
     * 2. HyperOS 4+ → ближайший известный HyperOS-вариант (fallback);
     * 3. нераспознанная/отсутствующая версия → вариант с флагом `fallback`
     *    (miui12-12.5), иначе последний объявленный (fallback).
     */
    internal fun resolveVariant(
        variants: List<StepVariant>,
        os: OsTarget?
    ): VariantSelection? {
        if (variants.isEmpty()) return null
        val fallbackVariant = variants.firstOrNull { it.fallback }
        if (os == null || os.family == RomFamily.UNKNOWN) {
            return VariantSelection(fallbackVariant ?: variants.last(), true)
        }
        variants.firstOrNull {
            it.family == os.family && os.ui >= it.uiMin - UI_EPS && os.ui <= it.uiMax + UI_EPS
        }?.let { return VariantSelection(it, false) }
        if (os.family == RomFamily.HYPEROS) {
            variants.filter { it.family == RomFamily.HYPEROS }.maxByOrNull { it.uiMax }
                ?.let { return VariantSelection(it, true) }
        }
        return VariantSelection(fallbackVariant ?: variants.last(), true)
    }

    private fun selectedVariantTag(): String =
        loadedSteps.firstNotNullOfOrNull { selectionFor(it, activeOs)?.variant?.id } ?: "base"

    private fun selectedVariantFallback(): Boolean =
        loadedSteps.firstNotNullOfOrNull { selectionFor(it, activeOs)?.fallback } ?: false

    /** Дополнительная цель шага в терминах рантайма (тексты локализованы). */
    data class ExtraTarget(
        val back: Int,
        val drillPath: List<List<String>>,
        val itemTexts: List<String>,
        val control: ActionType,
        /** Кнопки подтверждения диалога цели (пусто = подтверждения у цели нет). */
        val confirmTexts: List<String> = emptyList(),
        /** Целевое состояние тумблера цели (включается, если true). */
        val targetChecked: Boolean = false,
        /** Цель-«до главного тумблера»: её строка исчезает после выключения главной. */
        val beforeMain: Boolean = false
    )

    /** Авторитетный drillPath варианта: заменяет legacy-путь при явном совпадении ОС. */
    fun variantDrillPath(id: String): List<List<String>> {
        val selection = selection(id) ?: return emptyList()
        if (selection.fallback) return emptyList()
        return selection.variant.drillPath
    }

    /** Дополнительные цели варианта: второй экран/второй тумблер. */
    fun extraTargets(id: String): List<ExtraTarget> =
        selection(id)?.variant?.extraTargets.orEmpty().map { target ->
            ExtraTarget(
                back = target.back.coerceAtLeast(0),
                drillPath = target.drillPath,
                itemTexts = localizedTexts(target.itemTexts),
                control = target.control ?: ActionType.TOGGLE,
                confirmTexts = localizedTexts(target.confirmTexts),
                targetChecked = target.targetChecked,
                beforeMain = target.beforeMain
            )
        }

    /** Цели, обязанные отработать ДО главного тумблера: строка исчезает после его выключения. */
    fun extraTargetsBeforeMain(id: String): List<ExtraTarget> =
        extraTargets(id).filter { it.beforeMain }

    /** Цели после главного тумблера (второй экран/второй тумблер). */
    fun extraTargetsAfterMain(id: String): List<ExtraTarget> =
        extraTargets(id).filterNot { it.beforeMain }

    /** Тексты кнопки-отказа диалога, который оболочка показывает после главного тумблера. */
    fun toggleDeclineTexts(id: String): List<String> =
        localizedTexts(selection(id)?.variant?.toggleDeclineTexts)

    /**
     * Подсказки имени папки рабочего стола: имя страны региона, затем вариант, затем
     * слова локали. Имя — подсказка для порядка проверки; истина — секция рекомендаций
     * внутри папки (структурный поиск), поэтому переименование папки не ломает шаг.
     */
    fun folderNameHints(id: String, regionCode: String?): List<String> {
        val region = regionCode?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> step(id)?.folderNamesByRegion?.get(code) }
            .orEmpty()
        val fromVariant = localizedTexts(selection(id)?.variant?.folderNames)
        val fromStep = localizedTexts(step(id)?.folderNames)
        return (region + fromVariant + fromStep).distinct()
    }

    /** Фолбэк-действие варианта (например `clear_data_decline` для Проводника). */
    fun fallbackAction(id: String): String? = selection(id)?.variant?.fallbackAction

    /**
     * CONTROL явно совпавшей вариантной ветки. Для фолбэк-вариантов (нераспознанная
     * версия) не применяется: legacy-тип шага остаётся главным, чтобы не менять
     * поведение устройств без определённой версии.
     */
    fun variantControl(id: String): ActionType? {
        val selection = selection(id) ?: return null
        if (selection.fallback) return null
        return selection.variant.control
    }

    /** Точка входа варианта ("settings" — маршрут идёт через Настройки, а не приложение). */
    fun entry(id: String): String? {
        val selection = selection(id) ?: return null
        if (selection.fallback) return null
        return selection.variant.entry
    }

    /** Destructive-действие шага для гейта neverTouchPackages. */
    fun destructiveAction(id: String): String? = step(id)?.destructiveAction

    // ─── Ручная памятка и красный список ─────────────────────────────────

    /** Локализованный пункт ручной памятки. */
    data class LocalizedManualStep(
        val id: String,
        val title: String,
        val body: String,
        val warning: Boolean
    )

    fun manualSteps(): List<LocalizedManualStep> = manualStepsRaw.map { step ->
        LocalizedManualStep(
            id = step.id,
            title = localizedText(step.titles),
            body = localizedText(step.bodies),
            warning = step.warning
        )
    }

    /** Пакеты, над которыми запрещены destructive-операции (disable/uninstall/clear data/force-stop). */
    fun neverTouchPackages(): Set<String> = neverTouch

    private fun localizedText(map: Map<String, String>): String {
        map[locale()]?.takeIf { it.isNotBlank() }?.let { return it }
        map["en"]?.takeIf { it.isNotBlank() }?.let { return it }
        map["ru"]?.takeIf { it.isNotBlank() }?.let { return it }
        return map.values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /** Локаль устройства, если покрыта каталогом; иначе en → ru → первая доступная. */
    fun locale(): String {
        val device = Locale.getDefault().language.lowercase(Locale.ROOT)
        if (device in LOCALES) return device
        if ("en" in LOCALES) return "en"
        if ("ru" in LOCALES) return "ru"
        return LOCALES.firstOrNull() ?: "en"
    }

    // ─── Доступ к текстам шага ───────────────────────────────────────────

    fun keywords(id: String, fallback: List<String> = emptyList()): List<String> {
        val fromVariant = localizedTexts(selection(id)?.variant?.itemTexts)
        return merge(merge(fromVariant, localizedTexts(step(id)?.keywords)), fallback)
    }

    /**
     * Подписи тумблеров шага (itemTexts варианта, 7 локалей). Фолбэк — keywords:
     * рантайм-рутины (папки рабочего стола) не бурятся по drillPath и берут тумблер
     * из семантики.
     */
    fun itemTexts(id: String): List<String> {
        val fromVariant = localizedTexts(selection(id)?.variant?.itemTexts)
        if (fromVariant.isNotEmpty()) return fromVariant
        return localizedTexts(step(id)?.keywords)
    }

    /**
     * Маркеры экрана: вариант ОС авторитетен (наборы маркеров различаются между
     * MIUI и HyperOS), базовые поля — когда вариант их не задаёт.
     */
    fun screenMarkers(id: String): List<String> {
        val fromVariant = localizedTexts(selection(id)?.variant?.screenMarkers)
        if (fromVariant.isNotEmpty()) return fromVariant
        return localizedTexts(step(id)?.screenMarkers)
    }

    fun confirmTexts(id: String, fallback: List<String> = emptyList()): List<String> =
        merge(localizedTexts(step(id)?.confirmTexts), fallback)

    fun tapFallbackTexts(id: String, fallback: List<String> = emptyList()): List<String> {
        val fromVariant = localizedTexts(selection(id)?.variant?.tapFallbackTexts)
        return merge(merge(fromVariant, localizedTexts(step(id)?.tapFallbackTexts)), fallback)
    }

    fun consentTexts(id: String): List<String> =
        merge(localizedTexts(selection(id)?.variant?.consentTexts), localizedTexts(step(id)?.consentTexts))

    fun overflowMenuLabels(id: String): List<String> = localizedTexts(step(id)?.overflowMenuLabels)

    fun title(id: String, fallback: String): String {
        val titles = step(id)?.titles ?: return fallback
        return titles[locale()] ?: titles["en"] ?: titles["ru"] ?: fallback
    }

    fun actionType(id: String): ActionType =
        selection(id)?.variant?.control ?: step(id)?.actionType ?: ActionType.TOGGLE

    fun sequenceKind(id: String): SequenceKind? = step(id)?.sequenceKind

    fun confirmWaitMs(id: String, fallback: Long): Long =
        step(id)?.confirmWaitMs?.takeIf { it > 0L } ?: fallback

    fun maxConsentIterations(id: String, fallback: Int): Int =
        step(id)?.maxConsentIterations?.takeIf { it > 0 } ?: fallback

    fun launchPackage(id: String): String? = step(id)?.launchPackage

    /** Пакеты-цели шага из семантической таблицы (fallback к legacy requiredPackages). */
    fun requiredPackages(id: String): List<String> = step(id)?.requiredPackages.orEmpty()

    fun skipReason(id: String): String? = step(id)?.skipReason

    /**
     * Шаг принимает welcome-стены. false у route-шагов: их рабочие экраны (drawer
     * Проводника, список файлов) совпадают словами с welcomeActions, и «принятие»
     * стены тапает рабочую кнопку, ломая маршрут (прогон rmumuqr53).
     */
    fun welcomeAllowed(id: String): Boolean = step(id)?.welcomeAllowed ?: true

    /**
     * Решение шага по welcome-стене: "accept" — стена принимается даже тогда, когда
     * её текст совпал со screenMarkers самого шага (стена «Загрузок»).
     */
    fun stepWelcomeDecision(id: String): String = step(id)?.welcomeDecision.orEmpty()

    /** Кнопки согласия стены шага: объединяются с `welcomeActions` и идут первыми. */
    fun stepWelcomeAcceptTexts(id: String): List<String> = localizedTexts(step(id)?.welcomeAcceptTexts)

    /** Тексты «пропуска» стены шага: из набора согласия исключаются. */
    fun stepWelcomeSkipTexts(id: String): List<String> = localizedTexts(step(id)?.welcomeSkipTexts)

    /** Шаг глушит медиа-звук на время запуска (Mi Video: автоплей промо-ролика). */
    fun muteMediaOnLaunch(id: String): Boolean = step(id)?.muteMediaOnLaunch ?: false

    /** Подсказки-фолбэки drillPath (старые вариантные пути + путь фолбэк-варианта). */
    fun fallbackDrillPath(id: String): List<List<String>> {
        val base = step(id)?.fallbackDrillPath.orEmpty()
        val selection = selection(id) ?: return base
        if (!selection.fallback) return base
        return dedupPath(base + selection.variant.drillPath)
    }

    private fun dedupPath(path: List<List<String>>): List<List<String>> {
        val seen = HashSet<String>()
        val result = ArrayList<List<String>>(path.size)
        for (level in path) {
            if (seen.add(level.joinToString("\u0001"))) result.add(level)
        }
        return result
    }

    // ─── Consent policy ──────────────────────────────────────────────────

    fun welcomeMarkers(): List<String> = localizedTexts(consentPolicy?.welcomeMarkers)

    fun welcomeActions(): List<String> = localizedTexts(consentPolicy?.welcomeActions)

    /**
     * Кнопки согласия на ВСЕХ локалях: guard welcome-стены ищет живую кнопку, а
     * язык стены может отличаться от языка интерфейса приложения.
     */
    fun welcomeActionsAllLocales(): List<String> =
        (consentPolicy?.welcomeActions?.values?.flatten() ?: emptyList()).distinct()

    fun permissionMarkers(): List<String> = localizedTexts(consentPolicy?.permissionMarkers)

    fun denyTexts(): List<String> = localizedTexts(consentPolicy?.denyTexts)

    fun allowTexts(): List<String> = localizedTexts(consentPolicy?.allowTexts)

    /** Чекбоксы согласия на стене («Выбрать все», «(обязательно)»). */
    fun checkboxTexts(): List<String> = localizedTexts(consentPolicy?.checkboxTexts)

    /** Маркеры диалогов-заглушек внутри шага («Произошла ошибка сети»). */
    fun dismissMarkers(): List<String> = localizedTexts(consentPolicy?.dismissMarkers)

    /** Кнопки закрытия диалогов-заглушек («Нет, спасибо», «Понятно»). */
    fun dismissTexts(): List<String> = localizedTexts(consentPolicy?.dismissTexts)

    /** Маркеры системного диалога «Закрыть принудительно?» (force-stop). */
    fun forceStopMarkers(): List<String> = localizedTexts(consentPolicy?.forceStopMarkers)

    /** Маркеры диалога отчёта о сбое приложения. */
    fun crashReportMarkers(): List<String> = localizedTexts(consentPolicy?.crashReportMarkers)

    /** Маркеры диалога «Установить … по умолчанию?» (браузер и лаунчер). */
    fun defaultAppMarkers(): List<String> = localizedTexts(consentPolicy?.defaultAppMarkers)

    /** Маркеры обманок-промптов (апдейт-промпт GetApps и подобные). */
    fun decoyMarkers(): List<String> = localizedTexts(consentPolicy?.decoyMarkers)

    /** Resource-id крестиков закрытия обманок (машинные, без локалей). */
    fun decoyCloseIds(): List<String> = consentPolicy?.decoyCloseIds.orEmpty()

    /** Решение для диалога, которым владеет приложение шага (accept). */
    fun appOwnedDecision(): String = consentPolicy?.appOwnedDecision ?: "accept"

    /** Resource-id чекбоксов персонализации шага: снимаются до тапа согласия. */
    fun uncheckIds(stepId: String): List<String> = consentPolicy?.uncheckIds?.get(stepId).orEmpty()

    /** Подписи этих чекбоксов: только для лога `checkbox_unchecked text='…'`. */
    fun uncheckTexts(stepId: String): List<String> = consentPolicy?.uncheckTexts?.get(stepId).orEmpty()

    /**
     * Тексты-маркеры диалогов: по ним тапать запрещено — это заголовки и
     * сообщения, а не кнопки. Tap-мост использует список как avoid-набор.
     */
    fun alertMarkerTexts(): List<String> = listOf(
        forceStopMarkers(), crashReportMarkers(), defaultAppMarkers(),
        welcomeMarkers(), permissionMarkers(), dismissMarkers(), decoyMarkers()
    ).flatten().filter { it.isNotBlank() }.distinct()

    fun shouldAllow(stepId: String): Boolean =
        consentPolicy?.allowOverrides?.contains(stepId) == true

    fun maxConsentIterationsPolicy(): Int =
        consentPolicy?.maxIterationsPerStep?.coerceAtLeast(1) ?: 3

    // ─── Загрузка ─────────────────────────────────────────────────────────

    private fun load(context: Context) {
        try {
            val raw = context.assets.open(ASSET_PATH)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            val arr = root.optJSONArray("steps") ?: JSONArray()
            val parsed = ArrayList<SemanticStep>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { parsed.add(parseStep(it)) }
            }
            loadedSteps = parsed
            stepsById = parsed.associateBy { it.id }
            consentPolicy = parsePolicy(root.optJSONObject("consentPolicy"))
            manualStepsRaw = parseManualSteps(root.optJSONArray("manualSteps"))
            neverTouch = parseStringArray(root.optJSONArray("neverTouchPackages")).toSet()
            AppLog.i(
                TAG,
                "loaded steps=${parsed.size} locales=${LOCALES.size} " +
                    "policy=${if (consentPolicy != null) "yes" else "no"} locale=${locale()} " +
                    "manual=${manualStepsRaw.size} neverTouch=${neverTouch.size}"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "load failed: ${e.message}", e)
            loadedSteps = emptyList()
            stepsById = emptyMap()
            consentPolicy = null
            manualStepsRaw = emptyList()
            neverTouch = emptySet()
        } finally {
            isLoaded = true
        }
    }

    private fun parseStep(o: JSONObject): SemanticStep = SemanticStep(
        id = o.optString("id"),
        titles = parseStringMap(o.optJSONObject("title")),
        keywords = parseListMap(o.optJSONObject("keywords")),
        screenMarkers = parseListMap(o.optJSONObject("screenMarkers")),
        actionType = ActionType.from(o.optString("actionType")),
        sequenceKind = SequenceKind.from(o.optString("sequenceKind").takeIf { it.isNotEmpty() }),
        confirmTexts = parseListMap(o.optJSONObject("confirmTexts")),
        tapFallbackTexts = parseListMap(o.optJSONObject("tapFallbackTexts")),
        consentTexts = parseListMap(o.optJSONObject("consentTexts")),
        overflowMenuLabels = parseListMap(o.optJSONObject("overflowMenuLabels")),
        confirmWaitMs = o.optLong("confirmWaitMs", 0L),
        maxConsentIterations = o.optInt("maxConsentIterations", 0),
        launchPackage = o.optString("launchPackage").takeIf { it.isNotEmpty() },
        route = parseRoute(o.optJSONArray("route")),
        skipReason = o.optString("skipReason").takeIf { it.isNotEmpty() },
        fallbackDrillPath = parseDrillPath(o.optJSONArray("fallbackDrillPath")),
        welcomeAllowed = o.optBoolean("welcomeAllowed", true),
        welcomeDecision = o.optString("welcomeDecision").takeIf { it.isNotEmpty() }.orEmpty(),
        welcomeAcceptTexts = parseListMap(o.optJSONObject("welcomeAcceptTexts")),
        welcomeSkipTexts = parseListMap(o.optJSONObject("welcomeSkipTexts")),
        muteMediaOnLaunch = o.optBoolean("muteMediaOnLaunch", false),
        safe = o.optBoolean("safe", true),
        localeCoverage = parseStringArray(o.optJSONArray("localeCoverage")),
        requiredPackages = parseStringArray(o.optJSONArray("requiredPackages")),
        folderNames = parseListMap(o.optJSONObject("folderNames")),
        folderNamesByRegion = parseListMap(o.optJSONObject("folderNamesByRegion")),
        variants = parseVariants(o.optJSONArray("variants")),
        destructiveAction = o.optString("destructiveAction").takeIf { it.isNotEmpty() }
    )

    private fun parseVariants(arr: JSONArray?): List<StepVariant> {
        arr ?: return emptyList()
        val result = ArrayList<StepVariant>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val os = o.optJSONObject("os")
            result.add(
                StepVariant(
                    id = o.optString("id").takeIf { it.isNotEmpty() } ?: "variant${i + 1}",
                    family = osFamily(os?.optString("family")),
                    uiMin = os?.optDouble("uiMin", 0.0) ?: 0.0,
                    uiMax = os?.optDouble("uiMax", 0.0) ?: 0.0,
                    fallback = o.optBoolean("fallback", false),
                    drillPath = parseDrillPath(o.optJSONArray("drillPath")),
                    screenMarkers = parseListMap(o.optJSONObject("screenMarkers")),
                    itemTexts = parseListMap(o.optJSONObject("itemTexts")),
                    tapFallbackTexts = parseListMap(o.optJSONObject("tapFallbackTexts")),
                    consentTexts = parseListMap(o.optJSONObject("consentTexts")),
                    control = o.optString("control").takeIf { it.isNotEmpty() }?.let { ActionType.from(it) },
                    extraTargets = parseExtraTargets(o.optJSONArray("extraTargets")),
                    toggleDeclineTexts = parseListMap(o.optJSONObject("toggleDeclineTexts")),
                    folderNames = parseListMap(o.optJSONObject("folderNames")),
                    fallbackAction = o.optString("fallbackAction").takeIf { it.isNotEmpty() },
                    route = parseRoute(o.optJSONArray("route")),
                    entry = o.optString("entry").takeIf { it.isNotEmpty() }
                )
            )
        }
        return result
    }

    /**
     * Явный сценарий маршрута (RouteScript) активного варианта: если он задан,
     * авто-маршрут (интенты + drill + guard'ы) для шага не применяется вовсе —
     * выполняется ровно то, что проверено руками на этой прошивке.
     */
    fun route(id: String): List<RouteItem> {
        val fromVariant = selection(id)?.variant?.route.orEmpty()
        if (fromVariant.isNotEmpty()) return fromVariant
        return step(id)?.route.orEmpty()
    }

    private fun parseRoute(arr: JSONArray?): List<RouteItem> {
        arr ?: return emptyList()
        val result = ArrayList<RouteItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result.add(
                RouteItem(
                    intent = o.optString("intent").takeIf { it.isNotEmpty() },
                    tapText = o.optString("tapText").takeIf { it.isNotEmpty() },
                    tapDesc = o.optString("tapDesc").takeIf { it.isNotEmpty() },
                    tapId = o.optString("tapId").takeIf { it.isNotEmpty() },
                    scroll = o.optBoolean("scroll", false),
                    waitMs = o.optLong("waitMs", 700L).coerceIn(0L, 5_000L)
                )
            )
        }
        return result
    }

    private fun parseExtraTargets(arr: JSONArray?): List<VariantExtraTarget> {
        arr ?: return emptyList()
        val result = ArrayList<VariantExtraTarget>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result.add(
                VariantExtraTarget(
                    back = o.optInt("back", 0),
                    drillPath = parseDrillPath(o.optJSONArray("drillPath")),
                    itemTexts = parseListMap(o.optJSONObject("itemTexts")),
                    control = o.optString("control").takeIf { it.isNotEmpty() }?.let { ActionType.from(it) },
                    confirmTexts = parseListMap(o.optJSONObject("confirmTexts")),
                    targetChecked = o.optBoolean("target", false),
                    beforeMain = o.optBoolean("beforeMain", false)
                )
            )
        }
        return result
    }

    private fun parseManualSteps(arr: JSONArray?): List<ManualStep> {
        arr ?: return emptyList()
        val result = ArrayList<ManualStep>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            result.add(
                ManualStep(
                    id = id,
                    titles = parseStringMap(o.optJSONObject("title")),
                    bodies = parseStringMap(o.optJSONObject("body")),
                    warning = o.optBoolean("warning", false)
                )
            )
        }
        return result
    }

    private fun osFamily(raw: String?): RomFamily = when (raw?.lowercase(Locale.ROOT)) {
        "miui" -> RomFamily.MIUI
        "hyperos" -> RomFamily.HYPEROS
        else -> RomFamily.UNKNOWN
    }

    private fun parsePolicy(o: JSONObject?): ConsentPolicy? {
        o ?: return null
        return ConsentPolicy(
            welcomeMarkers = parseListMap(o.optJSONObject("welcomeMarkers")),
            welcomeActions = parseListMap(o.optJSONObject("welcomeActions")),
            permissionMarkers = parseListMap(o.optJSONObject("permissionMarkers")),
            defaultDecision = o.optString("defaultDecision", "deny"),
            denyTexts = parseListMap(o.optJSONObject("denyTexts")),
            allowTexts = parseListMap(o.optJSONObject("allowTexts")),
            allowOverrides = parseStringArray(o.optJSONArray("allowOverrides")).toSet(),
            maxIterationsPerStep = o.optInt("maxIterationsPerStep", 3),
            checkboxTexts = parseListMap(o.optJSONObject("checkboxTexts")),
            dismissMarkers = parseListMap(o.optJSONObject("dismissMarkers")),
            dismissTexts = parseListMap(o.optJSONObject("dismissTexts")),
            forceStopMarkers = parseListMap(o.optJSONObject("forceStopMarkers")),
            crashReportMarkers = parseListMap(o.optJSONObject("crashReportMarkers")),
            defaultAppMarkers = parseListMap(o.optJSONObject("defaultAppMarkers")),
            decoyMarkers = parseListMap(o.optJSONObject("decoyMarkers")),
            decoyCloseIds = parseStringArray(o.optJSONArray("decoyCloseIds")),
            uncheckIds = parseListMap(o.optJSONObject("uncheckIds")),
            uncheckTexts = parseListMap(o.optJSONObject("uncheckTexts")),
            appOwnedDecision = o.optString("appOwnedDecision", "accept")
        )
    }

    private fun parseStringMap(o: JSONObject?): Map<String, String> {
        o ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        o.keys().forEach { key -> map[key] = o.optString(key) }
        return map
    }

    private fun parseListMap(o: JSONObject?): Map<String, List<String>> {
        o ?: return emptyMap()
        val map = LinkedHashMap<String, List<String>>()
        o.keys().forEach { key -> map[key] = parseStringArray(o.optJSONArray(key)) }
        return map
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }

    private fun parseDrillPath(arr: JSONArray?): List<List<String>> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            parseStringArray(arr.optJSONArray(i)).takeIf { it.isNotEmpty() }
        }
    }

    /** Тексты локали устройства с фолбэком en → ru → первой непустой локали. */
    private fun localizedTexts(map: Map<String, List<String>>?): List<String> {
        map ?: return emptyList()
        map[locale()]?.takeIf { it.isNotEmpty() }?.let { return it }
        map["en"]?.takeIf { it.isNotEmpty() }?.let { return it }
        map["ru"]?.takeIf { it.isNotEmpty() }?.let { return it }
        return map.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun merge(primary: List<String>, fallback: List<String>): List<String> =
        (primary + fallback).filter { it.isNotBlank() }.distinct()

    /** Сброс синглтона — только для тестов. */
    internal fun resetForTest() {
        synchronized(lock) {
            loadedSteps = emptyList()
            stepsById = emptyMap()
            consentPolicy = null
            manualStepsRaw = emptyList()
            neverTouch = emptySet()
            activeOs = null
            variantLogged.clear()
            isLoaded = false
        }
    }
}