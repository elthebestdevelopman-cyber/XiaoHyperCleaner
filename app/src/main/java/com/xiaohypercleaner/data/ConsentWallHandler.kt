package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
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

    /** Пауза перед проверкой «диалог закрылся» (тап уже отправлен). */
    private const val VERIFY_DELAY_MS = 250L

    /**
     * Отрицательные кнопки системных alert-диалогов. Робот закрывает диалог и
     * продолжает шаг: «Установить по умолчанию» (Отмена), отчёт о сбое (Отмена),
     * «Закрыть принудительно?» (Отмена). Диалог, принадлежащий шагу (msa: «Отозвать»),
     * сюда не попадает — его ведёт confirm-логика шага.
     */
    private val ALERT_NEGATIVE_TEXTS = listOf(
        "Отмена", "Отменить", "Cancel", "Abbrechen", "Cancelar", "Batal",
        "Позже", "Later", "Не сейчас", "Not now", "Пропустить", "Skip",
        "Нет", "No", "取消", "취소"
    )

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
    }

    /** Разобранный диалог: что это и каким действием он закрывается. */
    internal data class DialogAction(
        val kind: String,
        val decision: String,
        val cause: String,
        val texts: List<String>,
        val markers: List<String>
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
        stepLabels: List<String> = emptyList()
    ): Outcome {
        if (isCancelled()) return Outcome(false, "none", "cancelled")
        val root = service.rootInActiveWindow ?: return Outcome(false, "none", "no_window")
        val owner = root.packageName?.toString()
        val screenText = NodeTree.collectText(root)
        val alertDialog = isAlertDialog(root)
        recycle(root)

        val action = classify(
            screenText = screenText,
            ownerPackage = owner,
            stepPackages = stepPackages,
            stepId = stepId,
            stepConfirmTexts = stepConfirmTexts,
            stepConsentTexts = stepConsentTexts,
            alertDialog = alertDialog,
            stepLabels = stepLabels
        ) ?: return Outcome(false, "none", "no_dialog")

        if (action.kind == "welcome") {
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
        if (!verified && action.kind == "dialog") {
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
            screenText = screenText
        )
        return Outcome(true, action.kind, action.decision, verified)
    }

    private suspend fun dispatch(bridge: TapBridge, action: DialogAction): Boolean =
        if (action.kind == "welcome") {
            bridge.tapEnabledDialogButtonByTexts(action.texts, action.markers)
        } else {
            bridge.tapDialogButtonByTexts(action.texts, action.markers)
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
        stepLabels: List<String> = emptyList()
    ): DialogAction? {
        if (ownsDialog(screenText, stepConfirmTexts)) return null

        val welcomeMarkers = SemanticCatalog.welcomeMarkers()
        val permissionMarkers = SemanticCatalog.permissionMarkers()
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

        // 2. Диалог-заглушка внутри шага («Произошла ошибка сети» → «Понятно»).
        //    Только когда это НЕ системный alert-диалог: иначе общая кнопка «Отмена»
        //    перехватывала «Установить … по умолчанию?» и kind уезжал с dialog на dismiss.
        if (!alertDialog && dismissTexts.isNotEmpty() &&
            dismissDialogVisible(screenText, dismissTexts, dismissMarkers)
        ) {
            return DialogAction("dismiss", "closed", "network", dismissTexts, alertMarkers)
        }

        val welcomeHit = markerHit(screenText, welcomeMarkers)
        val permissionHit = markerHit(screenText, permissionMarkers)

        // 3. Диалог принадлежит приложению шага: «Отмена» на нём означает, что
        //    приложение не открылось, — соглашаемся (Проводник, Музыка, Браузер).
        val appOwned = ownerPackage != null &&
            stepPackages.any { it.equals(ownerPackage, ignoreCase = true) }
        if (appOwned && (welcomeHit || permissionHit) &&
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
                    texts = (stepConsentTexts + SemanticCatalog.welcomeActions()).distinct(),
                    markers = welcomeMarkers
                )
            }
        }

        // 4. Welcome-стена: тапаем согласие и продолжаем шаг.
        if (!onTargetScreen && welcomeHit) {
            return DialogAction(
                kind = "welcome",
                decision = "accepted",
                cause = "wall",
                texts = (stepConsentTexts + SemanticCatalog.welcomeActions()).distinct(),
                markers = welcomeMarkers
            )
        }

        // 5. Runtime-permission: deny по умолчанию, allow — по политике каталога
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

        // 6. Прочий AlertDialog (ни стена, ни разрешение не найдены): закрываем
        //    отрицательной кнопкой и продолжаем шаг — прежнее поведение.
        if (alertDialog) {
            return DialogAction(
                kind = "dialog",
                decision = "dismissed",
                cause = "alert",
                texts = (ALERT_NEGATIVE_TEXTS + dismissTexts).distinct(),
                markers = alertMarkers
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
        stepLabels: List<String> = emptyList()
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
                isCancelled, stepLabels
            )
            if (!outcome.handled) break
            handled++
            val screen = screenSignature(service)
            if (screen.isNotEmpty() && screen == previousScreen) break
            previousScreen = screen
        }
        return handled
    }

    /** Текст-маркер считается вхождением по нормализованному тексту экрана. */
    private fun markerHit(screenText: String, markers: List<String>): Boolean =
        markers.isNotEmpty() && markers.any { TextMatcher.normalizedContains(screenText, it) }

    /** Экран изменился после тапа (пустой экран — успех: проверять нечего). */
    private suspend fun settled(service: AccessibilityService, before: String): Boolean {
        delay(VERIFY_DELAY_MS)
        val after = screenSignature(service)
        return after.isEmpty() || after != before
    }

    /** Стандартный alert-диалог: id `alertTitle`/`message` + кнопка `button1/button2`. */
    internal fun isAlertDialog(root: AccessibilityNodeInfo): Boolean {
        val hasTitle = NodeTree.findInTree(root) { viewId(it).endsWith("alertTitle") } != null
        if (hasTitle) return true
        val hasMessage = NodeTree.findInTree(root) { viewId(it).endsWith("message") } != null
        val hasNegative = NodeTree.findInTree(root) { viewId(it).endsWith("button2") } != null
        return hasMessage && hasNegative
    }

    /** Диалог принадлежит шагу, если на экране его собственный confirm-текст (msa). */
    private fun ownsDialog(screenText: String, stepConfirmTexts: List<String>): Boolean =
        stepConfirmTexts.any { text ->
            val normalized = TextMatcher.normalize(text)
            normalized.isNotEmpty() &&
                normalized !in GENERIC_CONFIRM_TEXTS &&
                TextMatcher.normalizedContains(screenText, text)
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
        screenText: String
    ) {
        AppLog.i(
            TAG,
            "consent: kind=${action.kind} decision=$decision step=$stepId cause=${action.cause} " +
                "pkg=${ownerPackage ?: "-"} verified=$verified text='${screenText.take(80)}'"
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

