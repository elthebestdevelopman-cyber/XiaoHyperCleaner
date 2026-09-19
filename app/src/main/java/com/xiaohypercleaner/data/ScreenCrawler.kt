package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.util.AppLog
import com.xiaohypercleaner.util.NodeTree
import com.xiaohypercleaner.util.TextMatcher
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only обход экранов устройства: находит строки-настройки с тумблерами и
 * помечает рекомендательные/промо-строки по лексикону.
 *
 * Инварианты:
 * - НИЧЕГО НЕ ПЕРЕКЛЮЧАЕТ: тумблеры только читаются (`isChecked`);
 * - по неизвестным строкам не кликает — обход идёт по входам каталога (Settings-root,
 *   явные активности пакетов шагов);
 * - результат — отчёт с подписями экранов и кандидатами (DataStore + лог), по нему
 *   формируются рецепты эпика автономии (`StepRecipe`).
 */
object ScreenCrawler {

    private const val TAG = "crawl"
    private const val MAX_CANDIDATES_PER_SCREEN = 8
    private const val ROW_DEPTH = 6
    private const val MAX_SCREENS = 30
    private const val SCREEN_SETTLE_MS = 1500L

    /**
     * Лексикон кандидатов. Машинное поле: точные строки UI устройства (7 локалей),
     * по нему строка-настройка признаётся рекомендательной.
     */
    val LEXICON: List<String> = listOf(
        // ru
        "получать рекомендации", "рекомендации", "рекомендуемое сегодня",
        "рекомендуемые приложения", "показывать предложения", "предложения",
        "лента виджетов", "персональные рекомендации", "персонализированные",
        "персонализированная реклама", "персонализация", "онлайн-рекомендации",
        "рекомендации приложений", "получать спонсированную рекламу",
        // en
        "receive recommendations", "recommendations", "recommended today",
        "recommended apps", "show suggestions", "suggestions", "app vault",
        "personalized recommendations", "personalised recommendations",
        "personalized ads", "ads personalization", "online recommendations",
        "app recommendations", "receive sponsored ads", "discover", "for you",
        // es
        "recibir recomendaciones", "recomendaciones", "recomendado hoy",
        "mostrar sugerencias", "sugerencias", "recomendaciones personalizadas",
        "recomendaciones en línea", "anuncios personalizados",
        // zh
        "接收推荐", "个性化推荐", "推荐", "显示建议", "建议", "在线推荐", "个性化广告",
        // hi (машинное поле: только для сопоставления подписей устройства)
        "\u0938\u0941\u091D\u093E\u0935 \u092A\u094D\u0930\u093E\u092A\u094D\u0924 \u0915\u0930\u0947\u0902",
        "\u0938\u0941\u091D\u093E\u0935",
        "\u0938\u093F\u092B\u093C\u093E\u0930\u093F\u0936\u0947\u0902",
        "\u0905\u0928\u0941\u0936\u0902\u0938\u093F\u0924",
        // pt
        "receber recomendações", "recomendações", "mostrar sugestões",
        "sugestões", "recomendações personalizadas", "recomendações online",
        "anúncios personalizados",
        // id
        "terima rekomendasi", "rekomendasi", "tampilkan saran", "saran",
        "rekomendasi personal", "rekomendasi online", "iklan yang dipersonalisasi"
    )

    /** Тумблер экрана с подписью своей строки (read-only снимок). */
    data class RowSwitch(val rowText: String, val checked: Boolean, val bounds: String)

    /** Кандидат отчёта: строка экрана, совпавшая с лексиконом. */
    data class Candidate(
        val packageName: String,
        val screenSignature: String,
        val rowText: String,
        val matched: String,
        val checked: Boolean,
        val bounds: String
    )

    /** Снимок одного экрана. */
    data class ScreenSnapshot(
        val packageName: String,
        val signature: String,
        val title: String,
        val switchCount: Int,
        val candidates: List<Candidate>
    )

    /** Совпавший ключ лексикона для подписи строки (null — не кандидат). */
    fun classify(rowText: String?, lexicon: List<String> = LEXICON): String? {
        val text = rowText?.trim().orEmpty()
        if (text.isEmpty()) return null
        return lexicon.firstOrNull { TextMatcher.normalizedContains(text, it) }
    }

    /** Отчёт обхода: экраны + кандидаты (сохраняется в DataStore, пишется в лог). */
    data class CrawlReport(
        val timestamp: Long,
        val osFamily: String,
        val osUi: String,
        val incremental: String,
        val screens: List<ScreenSnapshot>
    ) {
        val candidateCount: Int get() = screens.sumOf { it.candidates.size }

        fun toJson(): String {
            val root = JSONObject()
            root.put("schema", SCHEMA)
            root.put("timestamp", timestamp)
            root.put("osFamily", osFamily)
            root.put("osUi", osUi)
            root.put("incremental", incremental)
            val arr = JSONArray()
            screens.forEach { screen ->
                val s = JSONObject()
                s.put("package", screen.packageName)
                s.put("signature", screen.signature)
                s.put("title", screen.title)
                s.put("switches", screen.switchCount)
                val cands = JSONArray()
                screen.candidates.forEach { c ->
                    cands.put(
                        JSONObject()
                            .put("rowText", c.rowText)
                            .put("matched", c.matched)
                            .put("checked", c.checked)
                            .put("bounds", c.bounds)
                    )
                }
                s.put("candidates", cands)
                arr.put(s)
            }
            root.put("screens", arr)
            return root.toString()
        }

        companion object {
            const val SCHEMA = 1

            fun fromJson(json: String): CrawlReport? = try {
                val root = JSONObject(json)
                if (root.optInt("schema", 0) != SCHEMA) return null
                val screens = ArrayList<ScreenSnapshot>()
                root.optJSONArray("screens")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        val pkg = s.optString("package")
                        val signature = s.optString("signature")
                        val candidates = ArrayList<Candidate>()
                        s.optJSONArray("candidates")?.let { cands ->
                            for (j in 0 until cands.length()) {
                                val c = cands.optJSONObject(j) ?: continue
                                candidates.add(
                                    Candidate(
                                        packageName = pkg,
                                        screenSignature = signature,
                                        rowText = c.optString("rowText"),
                                        matched = c.optString("matched"),
                                        checked = c.optBoolean("checked", false),
                                        bounds = c.optString("bounds")
                                    )
                                )
                            }
                        }
                        screens.add(
                            ScreenSnapshot(
                                packageName = pkg,
                                signature = signature,
                                title = s.optString("title"),
                                switchCount = s.optInt("switches", 0),
                                candidates = candidates
                            )
                        )
                    }
                }
                CrawlReport(
                    timestamp = root.optLong("timestamp", 0L),
                    osFamily = root.optString("osFamily"),
                    osUi = root.optString("osUi"),
                    incremental = root.optString("incremental"),
                    screens = screens
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "crawl report parse failed: ${e.message}")
                null
            }
        }
    }

    /** Вход обхода: пакет + интент + метка шага. */
    data class CrawlEntry(val packageName: String, val label: String, val intent: Intent)

    /** Подпись строки тумблера: его собственный текст либо текст ближайшей строки. */
    fun rowLabel(switchNode: AccessibilityNodeInfo, maxDepth: Int = ROW_DEPTH): String {
        switchNode.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        var current: AccessibilityNodeInfo? = switchNode
        var depth = 0
        while (current != null && depth <= maxDepth) {
            val row = NodeTree.clickableAncestorOrSelf(current)
            if (row != null && row !== switchNode) {
                val text = NodeTree.collectText(row).trim()
                if (text.isNotBlank()) return text.take(200)
            }
            current = current.parent
            depth++
        }
        return ""
    }

    /** Read-only снимок экрана: тумблеры, подписи строк и кандидаты по лексикону. */
    fun snapshotScreen(
        root: AccessibilityNodeInfo?,
        packageName: String,
        lexicon: List<String> = LEXICON
    ): ScreenSnapshot {
        root ?: return ScreenSnapshot(packageName, "", "", 0, emptyList())
        val switches = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > NodeTree.DEFAULT_MAX_DEPTH) return
            if (SwitchFinder.isSwitchLike(node)) {
                switches.add(node)
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)

        val title = firstText(root).orEmpty()
        val signature = signatureOf(packageName, title, root)
        val candidates = ArrayList<Candidate>()
        switches.forEach { sw ->
            val label = rowLabel(sw)
            val matched = classify(label, lexicon) ?: return@forEach
            val rect = Rect().also { sw.getBoundsInScreen(it) }
            candidates.add(
                Candidate(
                    packageName = packageName,
                    screenSignature = signature,
                    rowText = label,
                    matched = matched,
                    checked = SwitchFinder.isChecked(sw),
                    bounds = "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
                )
            )
        }
        return ScreenSnapshot(
            packageName = packageName,
            signature = signature,
            title = title,
            switchCount = switches.size,
            candidates = candidates.take(MAX_CANDIDATES_PER_SCREEN)
        )
    }

    /** Первый непустой текст дерева (обычно заголовок экрана). */
    private fun firstText(root: AccessibilityNodeInfo, maxDepth: Int = 3): String? {
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (found != null || node == null || depth > maxDepth) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { found = it.trim() }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    /** Отпечаток экрана = пакет + заголовок + первые три текста (стабильная подпись). */
    private fun signatureOf(
        packageName: String,
        title: String,
        root: AccessibilityNodeInfo
    ): String {
        val texts = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 2 || texts.size >= 3) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it.trim()) }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return "$packageName|$title|${texts.joinToString("~")}"
    }

    /**
     * Входы обхода: Settings-root + целевые пакеты шагов (явные активности из
     * `ActivityScanner`, фолбэк — неявный LAUNCHER). Только чтение.
     */
    suspend fun entryPoints(context: Context): List<CrawlEntry> {
        val entries = ArrayList<CrawlEntry>()
        entries.add(
            CrawlEntry(
                packageName = "com.android.settings",
                label = "settings",
                intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        )
        val profile = RomProfile.detect(context)
        SimpleSteps.ALL.forEach { step ->
            val pkg = AdaptiveCatalog.resolveInstalledPackageForGroup(context, step.id, profile)
                ?: AdaptiveCatalog.packagesForStep(context, step.id, step.requiredPackages, profile)
                    .firstOrNull { ActivityScanner.isPackageVisible(context, it) }
            pkg ?: return@forEach
            val keywords = listOf(step.id.removePrefix("notif_"), "settings", "main", "home")
            val scan = ActivityScanner.scan(context, pkg, keywords, null)
            if (scan.candidates.isEmpty()) {
                entries.add(
                    CrawlEntry(
                        packageName = pkg,
                        label = step.id,
                        intent = Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setPackage(pkg)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                )
            } else {
                scan.candidates.forEach { candidate ->
                    entries.add(
                        CrawlEntry(
                            packageName = pkg,
                            label = step.id,
                            intent = Intent()
                                .setClassName(pkg, candidate.className)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    )
                }
            }
        }
        return entries
            .distinctBy { it.intent.component?.flattenToShortString() ?: it.intent.action.orEmpty() }
            .take(MAX_SCREENS)
    }

    /**
     * Обход: открывает входы, снимает read-only снимки экранов, тумблеры не нажимает.
     * Отчёт возвращается вызывающей стороне (сохранение — в DataStore).
     */
    suspend fun crawl(
        service: AccessibilityService,
        entries: List<CrawlEntry>,
        onScreen: (ScreenSnapshot) -> Unit = {}
    ): CrawlReport {
        val screens = ArrayList<ScreenSnapshot>()
        for (entry in entries.take(MAX_SCREENS)) {
            try {
                service.startActivity(entry.intent)
            } catch (e: Exception) {
                AppLog.w(TAG, "crawl entry failed pkg=${entry.packageName}: ${e.message}")
                continue
            }
            delay(SCREEN_SETTLE_MS)
            val root = service.rootInActiveWindow ?: continue
            val fg = root.packageName?.toString() ?: entry.packageName
            val snapshot = snapshotScreen(root, fg)
            recycle(root)
            screens.add(snapshot)
            AppLog.i(
                TAG,
                "crawl screen pkg=${snapshot.packageName} step=${entry.label} " +
                    "switches=${snapshot.switchCount} candidates=${snapshot.candidates.size} " +
                    "title='${snapshot.title}'"
            )
            onScreen(snapshot)
        }
        val profile = RomProfile.detect(service)
        val report = CrawlReport(
            timestamp = System.currentTimeMillis(),
            osFamily = profile.family.name,
            osUi = profile.uiVersion ?: "unknown",
            incremental = android.os.Build.VERSION.INCREMENTAL,
            screens = screens
        )
        AppLog.i(
            TAG,
            "crawl done screens=${report.screens.size} candidates=${report.candidateCount} " +
                "os=${report.osFamily}/${report.osUi}"
        )
        return report
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        node ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}