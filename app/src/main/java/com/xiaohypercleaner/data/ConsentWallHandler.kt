package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import com.xiaohypercleaner.util.UiWait
import kotlinx.coroutines.delay

/**
 * Единая точка входа для системных диалогов вместо старого хардкода
 * (`interceptSystemDialogs`): welcome-стены, runtime-permission запросы и
 * системные alert-диалоги MIUI.
 *
 * Правила (Аддендум B):
 * - системные alert-диалоги (force-stop, отчёт о сбое, «по умолчанию») закрываются
 *   ОТРИЦАТЕЛЬНОЙ кнопкой, тап идёт только по кнопкам: узлы-маркеры (заголовок,
 *   сообщение) исключены. Иначе «Закрыть принудительно?» «закрывался» тапом по
 *   собственному заголовку — шаг крутился 9 итераций (прогон rmu8lzcu9, filemanager);
 * - диалог, которым владеет приложение шага (Проводник, Музыка, Mi Браузер), ведёт
 *   себя как welcome-стена: `appOwnedDecision=accept`, потому что «Отмена» на нём
 *   означает «приложение не открылось»;
 * - permission-запрос → deny по умолчанию (`consentPolicy`), allow только для
 *   `allowOverrides` и диалогов приложения шага;
 * - диалог-заглушка («Произошла ошибка сети» → «Понятно», «Нет, спасибо»)
 *   закрывается ВНУТРИ цикла шага;
 * - лог: `consent: kind=<...> decision=<...> step=<id> cause=<...> pkg=<owner>
 *   verified=<bool> text=<preview>`.
 */
object ConsentWallHandler {

    private const val TAG = "consent"

    /** Длиннее этого текста экран считается настройками, а не диалогом. */
    private const val DIALOG_TEXT_MAX = 500

    /** Пауза перед проверкой «диалог закрылся» (тап уже отправлен) — верхний лимит опроса. */
    private const val VERIFY_DELAY_MS = 250L

    /** Поллинг проверки «экран изменился»: ранний выход, а не фиксированная пауза. */
    private const val VERIFY_POLL_MS = 100L

    /** Максимальное число попыток закрытия видеорекламы при запуске приложения. */
    private const val MAX_AD_DISMISS_ATTEMPTS = 3

    /** Задержка между попытками закрытия рекламы (реклама может показывать несколько роликов). */
    private const val AD_DISMISS_DELAY_MS = 1500L

    /**
     * ID крестика закрытия видеорекламы в приложениях Xiaomi (Mi Music и др.).
     * Яндекс.Реклама (Columbus) показывает полноэкранные ролики с крестиком в правом верхнем углу.
     */
    private val AD_CLOSE_BUTTON_IDS = listOf(
        "columbus_end_card_close",
        "iv_close",
        "close_button",
        "btn_close"
    )

    /**
     * Тексты кнопок закрытия/пропуска видеорекламы.
     * Некоторые рекламы показывают кнопку "Пропустить" или "Следующий" вместо крестика.
     */
    private val AD_SKIP_TEXTS = listOf(
        "Пропустить", "Skip", "Skip ad", "Пропустить рекламу",
        "Следующий", "Next", "Далее"
    )

    /** Маркеры рекламного экрана (Яндекс.Реклама, MIUI-getapps-реклама). */
    private val AD_MARKERS = listOf(
        "РЕКЛАМА", "ANUNCIO", "ANÚNCIO", "广告", "IKLAN", "विज्ञापन", "Reklama"
    )

    /** Кнопки-призывы рекламного экрана (MIUI-interstitial без рекламного контейнера). */
    private val AD_CTA_TEXTS = listOf(
        "Скачать", "Установить", "Подробнее", "Download", "Install", "Learn more"
    )

    /**
     * Пакеты и активности видеорекламы, которые блокируют запуск приложения.
     * При обнаружении — попытка закрытия через BACK, иначе — пропуск шага
     * с пометкой "ad_blocked" (Mi Music и другие приложения с Яндекс.Рекламой).
     */
    private val AD_ACTIVITY_PATTERNS = listOf(
        "com.yandex.mobile.ads",
        "com.google.android.gms.ads",
        "com.applovin",
        "com.ironsource",
        "com.unity3d.ads"
    )

    /**
     * Отрицательные кнопки системных alert-диалогов. Робот закрывает диалог и
     * продолжает шаг: «Установить по умолчанию» (Отмена), отчёт о сбое (Отмена),
     * «Закрыть принудительно?» (Отмена). Диалог, принадлежащий шагу (msa: «Отозвать»),
     * сюда не попадает — его ведёт confirm-логика шага.
     */
    private val ALERT_NEGATIVE_TEXTS = listOf(
        "Отмена", "Отменить", "Cancel", "Abbrechen", "Cancelar", "Batal",
        "Позже", "Later", "Не сейчас", "Not now", "Пропустить", "Skip",
        "Нет", "No", "取消", "취소",
        // «Отклонить» — негативная кнопка MIUI-диалогов Glance/Карусели обоев
        // («Наслаждайтесь еще лучшим экраном блокировки» → «Отклонить»/«Согласиться»,
        // дамп after/diagnostic_snapshot_carousel_*). В dismissTexts её быть не должно:
        // «Отклонить» встречается на welcome-стенах Проводника и permission-диалогах,
        // и dismiss-правило перехватывало их раньше welcome/permission.
        "Отклонить", "Decline"
    )

    /** Отрицательные кнопки диалогов (для теста: «Отклонить» — из промпта карусели). */
    internal fun alertNegativeTextsForTest(): List<String> = ALERT_NEGATIVE_TEXTS

    /** Обобщённые подтверждения: не считаются «владением» диалога шагом. */
    private val GENERIC_CONFIRM_TEXTS =
        setOf("ok", "ок", "oк", "yes", "да", "aceptar", "确定", "ठीक है", "oke")

    data class Outcome(
        val handled: Boolean,
        val kind: String,
        val decision: String,
        /** Тап отправлен, и экран изменился (false — тап не дал эффекта). */
        val verified: Boolean = true
    )

    /** Мост нажатий: реализует SimpleRunner (поиск узла по текстам). */
    interface TapBridge {
        /**
         * Подпись последней нажатой кнопки — для лога `tapped='…'`: по нему видно,
         * какая именно кнопка стены нажата (приёмка прогонов). Пусто — мост подписи
         * не отдаёт (тестовые фейки).
         */
        val lastTappedText: String get() = ""

        suspend fun tapByTexts(texts: List<String>): Boolean

        /**
         * Тап по ВКЛЮЧЁННОЙ кнопке: на стенах с чекбоксами и на отсчётных диалогах
         * кнопка согласия активна только после отметки обязательных пунктов/отсчёта.
         * По умолчанию — обычный тап (обратная совместимость).
         */
        suspend fun tapEnabledByTexts(texts: List<String>): Boolean = tapByTexts(texts)

        /**
         * Тап по КНОПКЕ диалога: узлы, чей текст совпал с [avoidTexts] (заголовок
         * или сообщение диалога), не нажимаются.
         */
        suspend fun tapDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapByTexts(texts)

        /** То же, но кнопка должна быть enabled (стены с чекбоксами/отсчётом). */
        suspend fun tapEnabledDialogButtonByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapEnabledByTexts(texts)

        /**
         * Тап по resource-id: крестик закрытия обманки-промпта (GetApps «Доступно
         * обновление») текста не имеет — закрыть его можно только по id.
         * По умолчанию — «не найдено» (обратная совместимость тестовых мостов).
         */
        suspend fun tapByIds(ids: List<String>): Boolean = false

        /**
         * Снимает отметку с чекбоксов персонализации ДО тапа согласия (стены
         * Браузера `cb_service` и Тём `cb_personal`). Возвращает подписи снятых
         * чекбоксов — для лога `decision=checkbox_unchecked`.
         */
        suspend fun uncheckCheckboxes(entries: List<Pair<String, String>>): List<String> = emptyList()

        /**
         * Свайп вверх: закрытие полноэкранного гайда-жеста (Mi Video «Проведите
         * вверх»). По умолчанию — «не умею»: тестовые мосты свайпов не делают.
         */
        suspend fun swipeUp(): Boolean = false

        /**
         * Тап по ЦЕНТРУ узла с подписью из [texts], даже если узел НЕ кликабелен
         * (промо-страницы WebView: мастер Mi Apps «Mi фаны рекомендуют» → «Пропустить»).
         * Узлы-маркеры ([avoidTexts]) не тапаются. По умолчанию — обычный тап.
         */
        suspend fun tapLabelByTexts(
            texts: List<String>,
            avoidTexts: List<String>
        ): Boolean = tapByTexts(texts)

        /**
         * Системный BACK: закрытие промо/обманки, у которой кнопки закрытия в дереве
         * нет вовсе (обманка обновления GetApps `UpgradeDialogActivity` — только
         * «Обновить», которую нажимать нельзя). По умолчанию — «не умею».
         */
        suspend fun pressBack(): Boolean = false
    }

    /** Разобранный диалог: что это и каким действием он закрывается. */
    internal data class DialogAction(
        val kind: String,
        val decision: String,
        val cause: String,
        val texts: List<String>,
        val markers: List<String>,
        /** Resource-id кнопок закрытия (крестик обманки-промпта: текста у него нет). */
        val ids: List<String> = emptyList(),
        /** Сработавший маркер — для лога welcome (`matched=<маркер>`). */
        val marker: String = "",
        /**
         * Кнопки стены — только из набора шага: живую кнопку ищем в них, а не в
         * общем `welcomeActions` (иначе route-шаг «принимает» рабочую страницу).
         */
        val strictTexts: Boolean = false
    )

    /**
     * Обрабатывает диалог, если он на экране. Вызывается в начале шага
     * и после каждого навигационного действия.
     *
     * @param stepPackages пакеты-цели шага: диалог с таким владельцем считается
     *   диалогом приложения шага (согласие), а не системной стеной
     */
    suspend fun handleOnce(
        service: AccessibilityService,
        bridge: TapBridge,
        stepId: String,
        stepConsentTexts: List<String> = emptyList(),
        stepConfirmTexts: List<String> = emptyList(),
        stepPackages: List<String> = emptyList(),
        isCancelled: () -> Boolean = { false },
        /** Подписи приложений-целей шага: по ним узнаём адресата permission-запроса. */
        stepLabels: List<String> = emptyList(),
        /**
         * Шаг разрешает обработку welcome-стен. У шагов с RouteScript — запрещено
         * (`welcomeAllowed: false`): на рабочих экранах drawer/списка находится
         * «Еще»/«Настройки» из welcomeActions, классификатор тапал их и ломал маршрут
         * (прогон rmumuqr53: три ложных `kind=welcome` на step=filemanager,
         * route 3/4 и 4/4 = ok=false).
         */
        allowWelcome: Boolean = true
    ): Outcome {
        if (isCancelled()) return Outcome(false, "none", "cancelled")
        val root = service.rootInActiveWindow ?: return Outcome(false, "none", "no_window")
        val owner = root.packageName?.toString()
        val screenText = NodeTree.collectText(root)
        val alertDialog = isAlertDialog(root)

        // Подписи кнопочных узлов нужны классификатору, чтобы опознать СВОЙ confirm-диалог
        // по кнопке, а не по подстроке в тексте всего экрана (C4/R2-6).
        val confirmButtonTexts = dialogButtonTexts(root)

        val action = classify(
            screenText = screenText,
            ownerPackage = owner,
            stepPackages = stepPackages,
            stepId = stepId,
            stepConfirmTexts = stepConfirmTexts,
            stepConsentTexts = stepConsentTexts,
            alertDialog = alertDialog,
            stepLabels = stepLabels,
            allowWelcome = allowWelcome,
            confirmButtonTexts = confirmButtonTexts
        )
        if (action == null) {
            recycle(root)
            return Outcome(false, "none", "no_dialog")
        }
        // Welcome-стена — только при НАЛИЧИИ живой кнопки согласия: на обычных экранах
        // (список Проводника, drawer) слова «Еще»/«Настройки» совпадают с welcomeActions,
        // и без этой проверки робот «принимал» рабочую страницу, схлопывая drawer.
        if (action.kind == "welcome") {
            // Узкая стена шага: живую кнопку ищем только в его наборе — иначе
            // «Еще»/«Настройки» рабочего экрана проходили гейт (route-шаги).
            val welcomeTapTexts = if (action.strictTexts) {
                action.texts
            } else {
                (action.texts + SemanticCatalog.welcomeActionsAllLocales()).distinct()
            }
            if (!hasEnabledAction(root, welcomeTapTexts)) {
                // Фолбэк разрешён ТОЛЬКО когда в дереве реально есть enabled-узел с
                // подписью согласия БЕЗ кликабельного предка (WebView-стены: Загрузки,
                // магазин). Иначе — прежний честный skip: «тапать на веру» нельзя,
                // иначе чужая/рабочая кнопка принималась бы как согласие.
                val labelOnlyPresent = hasLabelOnlyAction(root, welcomeTapTexts)
                recycle(root)
                if (!labelOnlyPresent || !bridge.tapLabelByTexts(welcomeTapTexts, action.markers)) {
                    AppLog.i(
                        TAG,
                        "consent: kind=welcome decision=skipped_no_button step=$stepId " +
                            "matched='${action.marker}' cause=${action.cause}"
                    )
                    return Outcome(false, "none", "welcome_without_button", true)
                }
                val labelVerified = settled(service, screenText)
                log(
                    stepId = stepId,
                    action = action,
                    ownerPackage = owner,
                    verified = labelVerified,
                    decision = if (labelVerified) "accepted:label" else "accepted:label:unverified",
                    screenText = screenText,
                    tapped = bridge.lastTappedText
                )
                return Outcome(true, action.kind, action.decision, labelVerified)
            }
        }
        recycle(root)

        if (action.kind == "welcome") {
            // Чекбоксы персонализации снимаем ДО тапа согласия: на стенах Mi Браузера
            // и Тём они отмечены по умолчанию, и согласие «как есть» включало бы сбор.
            val uncheckEntries = SemanticCatalog.uncheckIds(stepId).mapIndexed { index, id ->
                id to SemanticCatalog.uncheckTexts(stepId).getOrElse(index) { id }
            }
            for (label in bridge.uncheckCheckboxes(uncheckEntries)) {
                AppLog.i(
                    TAG,
                    "consent: kind=welcome decision=checkbox_unchecked step=$stepId text='$label'"
                )
            }
            // Стена с чекбоксами: сначала отмечаем «Выбрать все»/обязательные
            // пункты, затем жмём кнопку (до отметки она неактивна).
            val checkboxTexts = SemanticCatalog.checkboxTexts()
            val checkboxVisible = checkboxTexts.isNotEmpty() &&
                checkboxTexts.any { TextMatcher.normalizedContains(screenText, it) }
            if (checkboxVisible && bridge.tapByTexts(checkboxTexts)) {
                AppLog.i(TAG, "consent: kind=welcome decision=checkbox_marked step=$stepId")
            }
        }
        val dispatched = dispatch(bridge, action)
        if (!dispatched) {
            log(stepId, action, owner, verified = false, decision = "not_found", screenText = screenText)
            return Outcome(false, action.kind, action.decision, true)
        }

        var verified = settled(service, screenText)
        if (!verified && (action.kind == "dialog" || action.kind == "decoy")) {
            // Первый тап мог не попасть (MIUI меняет подпись кнопки после
            // анимации): один повтор расширенным набором, затем честный отказ.
            val retry = action.copy(
                texts = (ALERT_NEGATIVE_TEXTS + SemanticCatalog.dismissTexts()).distinct()
            )
            dispatch(bridge, retry)
            verified = settled(service, screenText)
        }
        log(
            stepId = stepId,
            action = action,
            ownerPackage = owner,
            verified = verified,
            decision = if (verified) action.decision else "${action.decision}:unverified",
            screenText = screenText,
            tapped = if (action.kind == "welcome") bridge.lastTappedText else ""
        )
        return Outcome(true, action.kind, action.decision, verified)
    }

    private suspend fun dispatch(bridge: TapBridge, action: DialogAction): Boolean =
        when (action.kind) {
            "welcome" -> bridge.tapEnabledDialogButtonByTexts(action.texts, action.markers)
            // Гайд-жест: тапать нечего, экран закрывается свайпом вверх.
            "guide" -> bridge.swipeUp()
            // Промо-мастер магазина: кнопка-пропуск в дереве есть, но кликабельного
            // узла у неё нет (WebView) — тапаем по центру её подписи.
            "master" -> bridge.tapLabelByTexts(action.texts, action.markers)
            else -> {
                // Обманка закрывается крестиком по id; если крестика в дереве нет —
                // отрицательная кнопка/текст, как у обычного диалога.
                val byIdOrText = bridge.tapByIds(action.ids) ||
                    bridge.tapDialogButtonByTexts(action.texts, action.markers)
                // Стена-промо без отрицательной кнопки (Mi Браузер: «Совершенно новые
                // AI-функции», «Приватные файлы»): у неё есть только кнопка продолжения,
                // поэтому после отказа по dismiss-текстам пробуем действия стены.
                // Только для kind=dismiss: permission-диалоги этот путь не трогает.
                val byWall = byIdOrText || (action.kind == "dismiss" &&
                    bridge.tapEnabledDialogButtonByTexts(
                        (SemanticCatalog.welcomeActions() + action.texts).distinct(),
                        action.markers
                    ))
                // Обманка обновления GetApps (`UpgradeDialogActivity`): кнопки закрытия в
                // дереве нет вовсе (только «Обновить», которую нажимать нельзя) — закрываем
                // системным BACK (проверено на устройстве: промпт уходит, магазин остаётся).
                byWall || (action.kind == "decoy" && bridge.pressBack())
            }
        }


    /**
     * Решение по экрану: что за диалог и чем он закрывается. Чистая функция
     * (тестируется без Accessibility).
     */
    internal fun classify(
        screenText: String,
        ownerPackage: String?,
        stepPackages: List<String>,
        stepId: String,
        stepConfirmTexts: List<String>,
        stepConsentTexts: List<String>,
        alertDialog: Boolean,
        /** Подписи приложений-целей шага («Проводник», «Музыка»): по ним узнаём адресата запроса. */
        stepLabels: List<String> = emptyList(),
        /** Welcome-стены разрешены только на стенах, не на рабочих экранах route-шагов. */
        allowWelcome: Boolean = true,
        /**
         * Подписи ТОЛЬКО кнопочных узлов экрана (id button1/2/3 либо класс Button):
         * «свой» confirm-диалог шага опознаётся по его КНОПКЕ, а не по подстроке в тексте
         * всего экрана — та же фраза встречается в описаниях чужих экранов (C4/R2-6).
         */
        confirmButtonTexts: List<String> = emptyList()
    ): DialogAction? {
        if (ownsDialog(confirmButtonTexts, stepConfirmTexts)) return null

        val welcomeMarkers = SemanticCatalog.welcomeMarkers()
        val permissionMarkers = SemanticCatalog.permissionMarkers()
        // Владелец диалога — пакет шага (используется и ниже, для app-owned).
        val appOwned = ownerPackage != null &&
            stepPackages.any { it.equals(ownerPackage, ignoreCase = true) }
        val welcomeHit = markerHit(screenText, welcomeMarkers)
        val permissionHit = markerHit(screenText, permissionMarkers)
        // Welcome/permission-стена СОБСТВЕННОГО приложения шага «чужой» не считается: её
        // текст может случайно содержать confirm-текст другого шага (первый запуск
        // «Безопасности»: описание содержит «отзыв разрешения», совпадая с confirmTexts msa —
        // стена уходила в skip, security_sys падал drill_failed, прогон rmuu1hsq6).
        val appOwnedWall = appOwned && (welcomeHit || permissionHit)
        // Диалог, чей текст совпадает с confirm-маркерами ДРУГОГО шага (msa «Отзыв
        // разрешения», appvault_about «Отключить службы?»), — не наш: dismiss/alert-политика
        // текущего шага его не трогает, иначе отменяет чужой отзыв (прогон rmupuud3s:
        // sys_recommendations погасил диалог отзыва msa).
        val foreignOwner = if (appOwnedWall) null else foreignConfirmOwner(confirmButtonTexts, stepId)
        if (foreignOwner != null) {
            AppLog.i(TAG, "consent: skip foreign confirm dialog step=$stepId owner=$foreignOwner")
            return null
        }

        val dismissMarkers = SemanticCatalog.dismissMarkers()
        val dismissTexts = SemanticCatalog.dismissTexts()
        val alertMarkers = SemanticCatalog.alertMarkerTexts()
        val forceStop = markerHit(screenText, SemanticCatalog.forceStopMarkers())
        val crashReport = markerHit(screenText, SemanticCatalog.crashReportMarkers())
        val defaultApp = markerHit(screenText, SemanticCatalog.defaultAppMarkers())

        // Целевой экран шага диалогом не считаем: маркеры диалогов («по умолчанию»,
        // «Условия использования») встречаются и в обычных настройках.
        val stepMarkers = SemanticCatalog.screenMarkers(stepId)
        val onTargetScreen = stepMarkers.isNotEmpty() &&
            stepMarkers.any { TextMatcher.normalizedContains(screenText, it) }
        val shortDialog = !onTargetScreen && screenText.length <= DIALOG_TEXT_MAX

        // 1. Системные alert-диалоги: «Закрыть принудительно?», отчёт о сбое,
        //    «Установить … по умолчанию?» — отрицательная кнопка, без согласия.
        //    MIUI-диалог без android:id/alertTitle распознаётся по короткому тексту.
        if ((forceStop || crashReport || defaultApp) && (alertDialog || shortDialog)) {
            return DialogAction(
                kind = "dialog",
                decision = "dismissed",
                cause = when {
                    forceStop -> "force_stop"
                    crashReport -> "crash_report"
                    else -> "default_app"
                },
                texts = (ALERT_NEGATIVE_TEXTS + dismissTexts).distinct(),
                markers = alertMarkers
            )
        }

        // 1b. Полноэкранный гайд-жест (Mi Video «Проведите вверх для просмотра других
        //     видео»): кнопки у него нет, вкладки он перекрывает — закрываем свайпом
        //     вверх, иначе drill уровня «Профиль» упирается в ENTRY timeout и шаг
        //     уходит not_applicable (прогон rmuoaz4jm, дамп
        //     diag-dumps/stumble/vid_feed.xml).
        val guideMarkers = SemanticCatalog.guideMarkers()
        if (markerHit(screenText, guideMarkers)) {
            return DialogAction(
                kind = "guide",
                decision = "swiped",
                cause = "swipe_guide",
                texts = emptyList(),
                markers = guideMarkers
            )
        }

        // 1c. Промо-мастер магазина (Mi Apps «Mi фаны рекомендуют», RecommendPageActivity):
        //     страница перекрывает вход в магазин, кнопка-пропуск в дереве есть, но
        //     БЕЗ кликабельного узла (WebView) — гейт «живой кнопки» её не видел, и
        //     drill уровня «Профиль» уходил в ENTRY timeout / not_applicable
        //     (прогон rmuvijbl1, дамп diag-dumps/fresh/getapps_recommend.xml).
        //     Тап по центру подписи делает мост (kind=master, [dispatch]).
        val masterMarkers = SemanticCatalog.masterMarkers()
        val masterSkipTexts = SemanticCatalog.masterSkipTextsAllLocales()
        if (markerHit(screenText, masterMarkers) && markerHit(screenText, masterSkipTexts)) {
            return DialogAction(
                kind = "master",
                decision = "skipped",
                cause = "promo_master",
                texts = masterSkipTexts,
                markers = masterMarkers
            )
        }

        // 2. Обманка-промпт («Доступно обновление» GetApps и подобные): закрываем
        //    крестиком по id, «Обновить» не нажимаем никогда. Классифицируется ДО
        //    диалогов-заглушек: у обманки нет ни текстовой кнопки закрытия, ни
        //    alertTitle, поэтому прежняя политика её не видела вовсе (прогон
        //    rmulhb4yq: апдейт-промпт закрывал экран GetApps, и шаг уходил в
        //    not_applicable «уровень маршрута отсутствует»).
        if (shortDialog && markerHit(screenText, SemanticCatalog.decoyMarkers())) {
            return DialogAction(
                kind = "decoy",
                decision = "dismissed",
                cause = "update_prompt",
                texts = (ALERT_NEGATIVE_TEXTS + dismissTexts).distinct(),
                markers = SemanticCatalog.decoyMarkers(),
                ids = SemanticCatalog.decoyCloseIds()
            )
        }

        // 3. Диалог-заглушка внутри шага («Произошла ошибка сети» → «Понятно»).
        //    Только когда это НЕ системный alert-диалог: иначе общая кнопка «Отмена»
        //    перехватывала «Установить … по умолчанию?» и kind уезжал с dialog на dismiss.
        //    Дополнительный гейт: на ЧУЖОМ экране (текст виджета POCO Launcher
        //    «Местное время…» совпадал с сетевыми маркерами) политика тапала кнопки
        //    лаунчера (btnCancel) — app-шаги не поднимались (прогон rmuihm2vo). Поэтому
        //    dismiss применяем только когда владелец диалога — пакет шага либо текст
        //    короткий (настоящий диалог), либо владелец неизвестен.
        val foreignScreen = ownerPackage != null && !appOwned && !shortDialog
        if (!alertDialog && dismissTexts.isNotEmpty() && !foreignScreen &&
            dismissDialogVisible(screenText, dismissTexts, dismissMarkers)
        ) {
            return DialogAction(
                kind = "dismiss",
                decision = "closed",
                cause = "network",
                texts = dismissTexts,
                markers = alertMarkers,
                // Кнопка диалога закрывается и по resource-id (`tv_ok` Mi Music):
                // подпись «OK» может отсутствовать в локали/меняться, а id стабилен.
                ids = SemanticCatalog.dismissCloseIds()
            )
        }

        val welcomeMarker = welcomeMarkers.firstOrNull { TextMatcher.normalizedContains(screenText, it) }.orEmpty()

        // Кнопки согласия: пер-шаговый набор стены идёт первым (стена «Загрузок»),
        // «пропуск» шага из набора исключён — пропуск согласием не является.
        val skipBlocked = SemanticCatalog.stepWelcomeSkipTexts(stepId)
        val stepAcceptTexts = SemanticCatalog.stepWelcomeAcceptTexts(stepId)
        // Шаг объявил стену обязательной к принятию: её текст может совпадать со
        // screenMarkers шага (PrivacyGrantDialog Загрузок упоминает «Загрузки»).
        val stepAcceptsWall = SemanticCatalog.stepWelcomeDecision(stepId) == "accept"
        // Узкая стена шага: только его собственные кнопки. Нужна route-шагам
        // (Проводник: `welcomeAllowed=false`), где общий набор `welcomeActions`
        // ловил «Еще»/«Настройки» рабочих экранов, но настоящая first-run стена с
        // `confirm_btn` «Принять и продолжить» должна приниматься (прогон rmuoaz4jm:
        // route 2/4 упирался в стену, шаг уходил `not_applicable`).
        val restrictedWall = stepAcceptsWall && stepAcceptTexts.isNotEmpty()
        val welcomeTexts = (stepConsentTexts +
            if (restrictedWall) stepAcceptTexts else stepAcceptTexts + SemanticCatalog.welcomeActions())
            .distinct()
            .filterNot { candidate -> skipBlocked.any { it.equals(candidate, ignoreCase = true) } }

        // 4. Диалог принадлежит приложению шага: «Отмена» на нём означает, что
        //    приложение не открылось, — соглашаемся (Проводник, Музыка, Браузер).
        //    Welcome-ветка отключается для шагов с RouteScript (allowWelcome=false):
        //    на их рабочих экранах «Еще»/«Настройки» из welcomeActions давали ложные стены.
        if (appOwned && (welcomeHit || permissionHit) && (allowWelcome || permissionHit) &&
            SemanticCatalog.appOwnedDecision() == "accept"
        ) {
            return if (permissionHit && !welcomeHit) {
                DialogAction(
                    kind = "permission",
                    decision = "allow",
                    cause = "app_owned",
                    texts = SemanticCatalog.allowTexts(),
                    markers = permissionMarkers
                )
            } else {
                DialogAction(
                    kind = "welcome",
                    decision = "accepted",
                    cause = "app_owned",
                    texts = welcomeTexts,
                    markers = welcomeMarkers,
                    marker = welcomeMarker
                )
            }
        }

        // 5. Welcome-стена: тапаем согласие и продолжаем шаг. Стена шага, объявленного
        //    через welcomeDecision=accept, принимается и тогда, когда её текст совпал
        //    со screenMarkers шага (PrivacyGrantDialog Загрузок: «…приложению Загрузки…»).
        //    Но НЕ когда на экране лежит САМА ЦЕЛЬ шага: в настройках Загрузок строка
        //    «Политика конфиденциальности» (маркер welcomeMarkers) — это целевой экран,
        //    а не стена (прогон rmuvlyyor: ложная стена skipped_no_button на downloads).
        val targetRows = SemanticCatalog.itemTexts(stepId)
        val onTargetRows = targetRows.isNotEmpty() &&
            targetRows.any { TextMatcher.normalizedContains(screenText, it) }
        if ((allowWelcome || restrictedWall) && (stepAcceptsWall || !onTargetScreen) &&
            !onTargetRows && welcomeHit
        ) {
            return DialogAction(
                kind = "welcome",
                decision = "accepted",
                cause = if (stepAcceptsWall && onTargetScreen) "wall_step" else "wall",
                texts = welcomeTexts,
                markers = welcomeMarkers,
                marker = welcomeMarker,
                strictTexts = restrictedWall
            )
        }

        // 6. Runtime-permission: deny по умолчанию, allow — по политике каталога
        //    ИЛИ когда доступ запрашивают для приложения-цели шага: без этого
        //    разрешения приложение не пускает дальше (Проводник: «Нет доступа к
        //    файлам» — доступ к фото/мультимедиа был отклонён, прогон rmua0pt7i).
        if (permissionHit) {
            val forTargetApp = requestsAccessForStep(screenText, stepLabels)
            val allow = SemanticCatalog.shouldAllow(stepId) || forTargetApp
            return DialogAction(
                kind = "permission",
                decision = if (allow) "allow" else "deny",
                cause = when {
                    SemanticCatalog.shouldAllow(stepId) -> "allow_override"
                    forTargetApp -> "target_app"
                    else -> "deny_default"
                },
                texts = if (allow) SemanticCatalog.allowTexts() else SemanticCatalog.denyTexts(),
                markers = permissionMarkers
            )
        }

        // 7. Прочий AlertDialog (ни стена, ни разрешение не найдены): закрываем
        //    отрицательной кнопкой и продолжаем шаг — прежнее поведение.
        if (alertDialog) {
            return DialogAction(
                kind = "dialog",
                decision = "dismissed",
                cause = "alert",
                texts = (ALERT_NEGATIVE_TEXTS + dismissTexts).distinct(),
                markers = alertMarkers,
                // Промо-диалоги MIUI (Mi Music «Ярлыки функций доступны сейчас») —
                // это AlertDialog-подобные окна с единственной кнопкой по id.
                ids = SemanticCatalog.dismissCloseIds()
            )
        }

        return null
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
        stepConfirmTexts: List<String> = emptyList(),
        stepPackages: List<String> = emptyList(),
        maxIterations: Int = SemanticCatalog.maxConsentIterationsPolicy(),
        isCancelled: () -> Boolean = { false },
        /** Подписи приложений-целей шага: по ним узнаём адресата permission-запроса. */
        stepLabels: List<String> = emptyList(),
        /** Welcome-стены шага (route-шаги их не принимают: `welcomeAllowed: false`). */
        allowWelcome: Boolean = true
    ): Int {
        var handled = 0
        val limit = maxIterations.coerceAtLeast(1)
        // Подпись экрана ДО первого действия: тап, который ничего не изменил,
        // дальше повторять нечего (прежний цикл трижды «принимал» одну и ту же
        // стену — browser_sys/music_sys, прогон rmu8lzcu9).
        var previousScreen = screenSignature(service)
        while (handled < limit) {
            if (isCancelled()) break
            val outcome = handleOnce(
                service, bridge, stepId, stepConsentTexts, stepConfirmTexts, stepPackages,
                isCancelled, stepLabels, allowWelcome
            )
            if (!outcome.handled) break
            handled++
            val screen = screenSignature(service)
            if (screen.isNotEmpty() && screen == previousScreen) break
            previousScreen = screen
        }
        return handled
    }

    /** Хотя бы одна КНОПКА диалога доступна: маркер сам по себе стену не подтверждает. */
    private fun hasEnabledAction(root: AccessibilityNodeInfo, texts: List<String>): Boolean =
        texts.isNotEmpty() && NodeTree.findInTree(root) { node ->
            // enabled НЕ требуем: на стенах с чекбоксами кнопка согласия активна только
            // после отметки обязательных пунктов (её тапает tapEnabledByTexts).
            NodeTree.matchesAny(node, texts) &&
                NodeTree.clickableAncestorOrSelf(node) != null
        } != null

    /**
     * На экране есть enabled-узел с подписью из [texts] БЕЗ кликабельного предка —
     * только для такого случая разрешён тап по центру подписи (WebView-стены: Загрузки,
     * магазин). Без этой проверки фолбэк «тапал на веру» и принимал чужую кнопку.
     */
    private fun hasLabelOnlyAction(root: AccessibilityNodeInfo, texts: List<String>): Boolean =
        texts.isNotEmpty() && NodeTree.findInTree(root) { node ->
            NodeTree.matchesAny(node, texts) &&
                node.isEnabled &&
                NodeTree.clickableAncestorOrSelf(node) == null
        } != null

    /** Текст-маркер считается вхождением по нормализованному тексту экрана. */
    private fun markerHit(screenText: String, markers: List<String>): Boolean =
        markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(screenText, it) }

    /** Экран изменился после тапа (пустой экран — успех: проверять нечего). */
    private suspend fun settled(service: AccessibilityService, before: String): Boolean =
        UiWait.until(
            timeoutMs = VERIFY_DELAY_MS,
            pollMs = VERIFY_POLL_MS
        ) {
            val after = screenSignature(service)
            after.isEmpty() || after != before
        }.hit

    /** Стандартный alert-диалог: id `alertTitle`/`message` + кнопка `button1/button2`. */
    internal fun isAlertDialog(root: AccessibilityNodeInfo): Boolean {
        val hasTitle = NodeTree.findInTree(root) { viewId(it).endsWith("alertTitle") } != null
        if (hasTitle) return true
        val hasMessage = NodeTree.findInTree(root) { viewId(it).endsWith("message") } != null
        val hasNegative = NodeTree.findInTree(root) { viewId(it).endsWith("button2") } != null
        return hasMessage && hasNegative
    }

    /**
     * Диалог принадлежит шагу, если его confirm-текст стоит на КНОПОЧНОМ узле диалога.
     * Подстрока по всему экрану давала ложное «свой диалог» на чужих экранах, где та же
     * фраза встречается в описании (msa «Отзыв разрешения» — заголовок) (C4/R2-6).
     */
    private fun ownsDialog(confirmButtonTexts: List<String>, stepConfirmTexts: List<String>): Boolean {
        if (confirmButtonTexts.isEmpty() || stepConfirmTexts.isEmpty()) return false
        return stepConfirmTexts.any { text ->
            val normalized = TextMatcher.normalize(text)
            normalized.isNotEmpty() &&
                normalized !in GENERIC_CONFIRM_TEXTS &&
                confirmButtonTexts.any { button -> TextMatcher.normalizedContains(button, text) }
        }
    }

    /**
     * Владелец чужого confirm-диалога: шаг (не текущий), чья confirm-КНОПКА видна на
     * экране. Такой диалог ведёт сам шаг-владелец — хендлер стен обязан отойти.
     */
    private fun foreignConfirmOwner(confirmButtonTexts: List<String>, currentStepId: String): String? =
        SemanticCatalog.all().firstNotNullOfOrNull { other ->
            other.id.takeIf {
                it != currentStepId &&
                    ownsDialog(confirmButtonTexts, SemanticCatalog.confirmTexts(it))
            }
        }

    /** Подписи кнопочных узлов экрана: id `button1`/`button2`/`button3` либо класс Button. */
    private fun dialogButtonTexts(root: AccessibilityNodeInfo): List<String> {
        val labels = ArrayList<String>()
        NodeTree.findAllInTree(root, predicate = { node ->
            val id = viewId(node)
            val isButton = id.endsWith("button1") || id.endsWith("button2") ||
                id.endsWith("button3") ||
                node.className?.toString()?.contains("Button", ignoreCase = true) == true
            if (isButton) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (label != null) labels.add(label)
            }
            false
        })
        return labels
    }

    /**
     * Запрос доступа адресован приложению-цели шага: в тексте диалога есть его
     * подпись («Разрешить приложению Проводник доступ к фото и мультимедиа на
     * устройстве?»). Такой запрос разрешаем — иначе целевой экран приложения
     * недостижим, и шаг честно проваливается (Проводник: «Нет доступа к файлам»).
     */
    private fun requestsAccessForStep(screenText: String, stepLabels: List<String>): Boolean =
        stepLabels.any { label ->
            label.trim().length >= 3 && TextMatcher.normalizedContains(screenText, label)
        }

    private fun viewId(node: AccessibilityNodeInfo): String = node.viewIdResourceName ?: ""

    private fun screenSignature(service: AccessibilityService): String {
        val root = service.rootInActiveWindow ?: return ""
        val text = NodeTree.collectText(root)
        recycle(root)
        return text
    }

    private fun dismissDialogVisible(
        screenText: String,
        dismissTexts: List<String>,
        dismissMarkers: List<String>
    ): Boolean {
        if (markerHit(screenText, dismissMarkers)) return true
        // «Нет, спасибо»/«Понятно» сами по себе — маркер диалога-заглушки.
        return dismissTexts.any { TextMatcher.normalizedContains(screenText, it) }
    }

    private fun log(
        stepId: String,
        action: DialogAction,
        ownerPackage: String?,
        verified: Boolean,
        decision: String,
        screenText: String,
        /** Подпись нажатой кнопки стены (`tapped='Согласен'`). */
        tapped: String = ""
    ) {
        AppLog.i(
            TAG,
            "consent: kind=${action.kind} decision=$decision step=$stepId cause=${action.cause} " +
                "pkg=${ownerPackage ?: "-"} verified=$verified tapped='$tapped' " +
                "text='${screenText.take(80)}'"
        )
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }

    /**
     * Закрытие видеорекламы при запуске приложения.
     *
     * Некоторые приложения (Mi Music, GetApps) показывают полноэкранную видеорекламу
     * при холодном старте. Реклама блокирует доступ к настройкам. Функция ищет крестик
     * (columbus_end_card_close) или кнопку "Пропустить" и закрывает рекламу до 3 раз
     * (реклама может показывать несколько роликов подряд).
     *
     * @param service AccessibilityService для доступа к дереву UI
     * @param stepId ID шага для логирования
     * @return Количество закрытых рекламных экранов
     */
    suspend fun dismissVideoAdsUntilSettled(
        service: AccessibilityService,
        stepId: String
    ): Int {
        var dismissed = 0
        repeat(MAX_AD_DISMISS_ATTEMPTS) { attempt ->
            if (!isVideoAdScreen(service)) return@repeat
            val closed = dismissVideoAdOnce(service, stepId, attempt + 1)
            if (!closed) return@repeat
            dismissed++
            delay(AD_DISMISS_DELAY_MS)
        }
        if (dismissed > 0) {
            AppLog.i(TAG, "ad: dismissed $dismissed video ad(s) for step=$stepId")
        }
        return dismissed
    }

    /** Проверка: текущий экран — реклама (пакет рекламного SDK, маркер «РЕКЛАМА» + контейнер/CTA). */
    private fun isVideoAdScreen(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        val text = NodeTree.collectText(root)

        // Проверка 1: пакет содержит рекламный SDK
        val isAdPackage = AD_ACTIVITY_PATTERNS.any { pattern ->
            pkg.contains(pattern, ignoreCase = true)
        }

        // Проверка 2: на экране есть маркер рекламы («РЕКЛАМА · 16+» Яндекс.Рекламы и MIUI-рекламы)
        val hasAdMarker = AD_MARKERS.any { text.contains(it, ignoreCase = true) } ||
            text.contains("AD ·", ignoreCase = false)

        // Проверка 3: есть ID рекламного контейнера
        val hasAdContainer = NodeTree.findInTree(root) { node ->
            val id = viewId(node)
            id.contains("adContainer", ignoreCase = true) ||
                id.contains("columbus", ignoreCase = true) ||
                id.contains("adsPlace", ignoreCase = true)
        } != null

        // Проверка 4: MIUI-interstitial без рекламного контейнера (Темы/GetApps: 2ГИС) —
        // маркер «РЕКЛАМА · 16+» плюс кнопка-призыв вместо крестика в дереве.
        val hasAdCta = AD_CTA_TEXTS.any { text.contains(it, ignoreCase = true) }

        recycle(root)
        return isAdPackage || (hasAdMarker && (hasAdContainer || hasAdCta))
    }

    /** Текущий экран — реклама. Для вызывающей стороны (гейт неприменимости шага). */
    fun isAdScreenNow(service: AccessibilityService): Boolean = isVideoAdScreen(service)

    /** Одна попытка закрытия видеорекламы: поиск крестика или кнопки "Пропустить". */
    private suspend fun dismissVideoAdOnce(
        service: AccessibilityService,
        stepId: String,
        attempt: Int
    ): Boolean {
        val root = service.rootInActiveWindow ?: return false

        // Попытка 1: найти крестик по ID
        val closeButton = AD_CLOSE_BUTTON_IDS.firstNotNullOfOrNull { targetId ->
            NodeTree.findInTree(root) { node ->
                viewId(node).endsWith(targetId, ignoreCase = true) && node.isClickable
            }
        }

        if (closeButton != null) {
            val tapped = closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycle(closeButton)
            recycle(root)
            AppLog.i(TAG, "ad: attempt=$attempt tapped close button for step=$stepId success=$tapped")
            return tapped
        }

        // Попытка 2: найти кнопку "Пропустить"/"Следующий" по тексту
        val skipButton = NodeTree.findInTree(root) { node ->
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            val matches = AD_SKIP_TEXTS.any { skipText ->
                nodeText.equals(skipText, ignoreCase = true) ||
                    nodeDesc.equals(skipText, ignoreCase = true)
            }
            matches && node.isClickable
        }

        if (skipButton != null) {
            val tapped = skipButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recycle(skipButton)
            recycle(root)
            AppLog.i(TAG, "ad: attempt=$attempt tapped skip button for step=$stepId success=$tapped")
            return tapped
        }

        recycle(root)
        // Попытка 3: MIUI-interstitial без крестика и без кнопки «Пропустить» в дереве
        // (Темы/GetApps: 2ГИС). Закрываем системным BACK — он возвращает в приложение.
        val backed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        AppLog.i(TAG, "ad: attempt=$attempt pressed BACK for step=$stepId success=$backed")
        return backed
    }
}

