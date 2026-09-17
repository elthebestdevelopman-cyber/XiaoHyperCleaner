package com.xiaohypercleaner.util

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.pm.PackageInfoCompat
import com.xiaohypercleaner.BuildConfig
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Локальный менеджер диагностических снимков для краудсорсинга и самообучения.
 *
 * Сохраняет анонимный технический отчёт при сбое сценария кликов:
 * - Модель устройства, версия Android, MIUI/HyperOS, системный регион
 * - Целевой пакет и его версия
 * - Структура экрана (AccessibilityNodeInfo Tree) в текстовом виде
 *
 * Подготовлен для будущей отправки Issue в GitHub без правок формата.
 */
object DiagnosticSnapshotManager {

    private const val TAG = "DiagnosticSnapshot"
    private const val DIAG_DIR = "diag"
    private const val MAX_TREE_DEPTH = 15
    private const val MAX_NODES_COLLECTED = 80
    /** COMPACT-сводка: ≤60 узлов, без bounds (меньше размер, тот же текст экрана). */
    private const val COMPACT_MAX_NODES_COLLECTED = 60
    private const val SCREENSHOT_MAX_ATTEMPTS = 3
    private const val SCREENSHOT_RETRY_DELAY_MS = 500L

    /** Purge: не больше 3 прогонов, не старше 7 дней, суммарный потолок 50 МБ. */
    private const val MAX_KEPT_RUNS = 3
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    /** Разрыв между прогонами: файлы с большим разрывом считаются разными прогонами. */
    private const val RUN_GAP_MS = 30L * 60 * 1000

    /**
     * Уровень диагностики:
     * - OFF — снапшоты/скриншоты не пишутся;
     * - COMPACT — сводка дерева без bounds (дефолт release);
     * - FULL — полный дамп дерева + скриншоты (дефолт debug, release — opt-in).
     */
    enum class DiagnosticLevel { OFF, COMPACT, FULL }

    @Volatile
    private var level: DiagnosticLevel =
        if (BuildConfig.DEBUG) DiagnosticLevel.FULL else DiagnosticLevel.COMPACT

    @Volatile
    private var levelSource: String = if (BuildConfig.DEBUG) "debug" else "default"

    /** Текущий уровень диагностики. */
    fun currentLevel(): DiagnosticLevel = level

    /** Устанавливает уровень и пишет единый лог `diag: level=… source=…`. */
    fun setLevel(newLevel: DiagnosticLevel, source: String) {
        level = newLevel
        levelSource = source
        AppLog.i(TAG, "diag: level=${newLevel.name} source=$source")
    }

    /** Разбор override из DataStore: неизвестное значение игнорируется. */
    fun parseLevel(raw: String?): DiagnosticLevel? = DiagnosticLevel.entries.firstOrNull {
        it.name.equals(raw?.trim(), ignoreCase = true)
    }

    data class DiagnosticReport(
        val timestamp: Long,
        val stepId: String,
        val failureReason: String,
        val deviceModel: String,
        val androidSdk: Int,
        val androidRelease: String,
        val miuiVersion: String?,
        val isHyperOs: Boolean,
        val region: String,
        val isTablet: Boolean,
        val targetPackage: String?,
        val targetAppVersion: String?,
        val screenHierarchyDump: String
    )

    fun captureAndSaveSnapshot(
        context: Context,
        stepId: String,
        failureReason: String,
        rootNode: AccessibilityNodeInfo?,
        profile: RomProfile,
        targetPackage: String?
    ): File? {
        if (level == DiagnosticLevel.OFF) return null
        return try {
            // Purge-триггер: каждая запись снапшота подчищает старые прогоны.
            purge(context)
            val appVersion = targetPackage?.let { pkg ->
                try {
                    val info = context.packageManager.getPackageInfo(pkg, 0)
                    val verCode = PackageInfoCompat.getLongVersionCode(info)
                    "${info.versionName} ($verCode)"
                } catch (_: Exception) {
                    "not_installed"
                }
            }

            val dumpTree = StringBuilder()
            var collectedCount = 0
            // COMPACT: сводка без bounds (меньше объём, текст экрана сохранён).
            val compactDump = level != DiagnosticLevel.FULL
            val nodeLimit = if (compactDump) COMPACT_MAX_NODES_COLLECTED else MAX_NODES_COLLECTED
            @Suppress("DEPRECATION")
            fun dump(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_TREE_DEPTH || collectedCount > nodeLimit) return
                collectedCount++
                val indent = "  ".repeat(depth)
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val id = node.viewIdResourceName?.substringAfterLast(":id/") ?: ""
                val text = node.text?.toString()?.take(60) ?: ""
                val desc = node.contentDescription?.toString()?.take(60) ?: ""
                val flags = buildString {
                    if (node.isClickable) append("[clickable] ")
                    if (node.isCheckable) append("[checkable, checked=${node.isChecked}] ")
                    if (node.isScrollable) append("[scrollable] ")
                    if (node.isFocused) append("[focused] ")
                }
                val boundsAttr = if (compactDump) "" else {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    " bounds=\"${rect.toShortString()}\""
                }
                dumpTree.appendLine(
                    "$indent<$cls id=\"$id\"$boundsAttr $flags text=\"$text\" desc=\"$desc\"/>"
                )
                for (i in 0 until node.childCount) {
                    dump(node.getChild(i), depth + 1)
                }
            }
            dump(rootNode, 0)

            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("stepId", stepId)
                put("failureReason", failureReason)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                put("androidSdk", Build.VERSION.SDK_INT)
                put("androidRelease", Build.VERSION.RELEASE)
                put("miuiVersion", profile.miuiVersion ?: "unknown")
                put("isHyperOs", profile.hyperOsHint)
                put("region", profile.regionCode)
                put("isTablet", profile.isTablet)
                put("targetPackage", targetPackage ?: "unknown")
                put("targetAppVersion", appVersion ?: "unknown")
                put(
                    "fingerprint",
                    JSONObject().apply {
                        put("ro.product.model", Build.MODEL)
                        put("ro.build.version.sdk", Build.VERSION.SDK_INT)
                        put(
                            "ro.miui.ui.version.name",
                            RomProfile.readProp("ro.miui.ui.version.name")
                                ?: RomProfile.readProp("ro.mi.os.version.name")
                                ?: "unknown"
                        )
                        put("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
                        put("region", profile.regionCode)
                        put("catalogVariant", AdaptiveCatalog.currentVariant())
                    }
                )
                put("screenDump", dumpTree.toString().trim())
            }

            val file = File(
                diagDir(context),
                "diagnostic_snapshot_${stepId}_${System.currentTimeMillis()}.json"
            )
            file.writeText(json.toString(2))
            AppLog.i(
                TAG,
                "Saved local diagnostic snapshot for step '$stepId' (reason=$failureReason, " +
                    "level=${level.name}, source=$levelSource) to ${file.absolutePath}"
            )
            file
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to capture diagnostic snapshot: ${e.message}")
            null
        }
    }

    /**
     * Очистка diag-директории: последние [MAX_KEPT_RUNS] прогонов, не старше
     * [MAX_AGE_MS], суммарный объём не больше [MAX_TOTAL_BYTES].
     * Триггеры: старт приложения и [captureAndSaveSnapshot].
     */
    fun purge(context: Context) = runCatching {
        val dir = diagDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return@runCatching
        if (files.isEmpty()) return@runCatching

        val removed = LinkedHashSet<File>()

        // 1) Оставляем только последние MAX_KEPT_RUNS прогонов.
        var runs = 0
        var prevTs = Long.MAX_VALUE
        for (f in files) {
            val ts = f.lastModified()
            if (prevTs - ts > RUN_GAP_MS) runs++
            prevTs = ts
            if (runs > MAX_KEPT_RUNS) removed.add(f)
        }

        // 2) Всё старше MAX_AGE_MS.
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        files.filter { it.lastModified() < cutoff }.forEach { removed.add(it) }

        // 3) Потолок по объёму: удаляем от старых к новым.
        val survivors = files.filterNot { it in removed }
        var totalBytes = survivors.sumOf { it.length() }
        for (f in survivors.asReversed()) {
            if (totalBytes <= MAX_TOTAL_BYTES) break
            removed.add(f)
            totalBytes -= f.length()
        }

        if (removed.isEmpty()) return@runCatching
        var failed = 0
        var freedBytes = 0L
        removed.forEach { f ->
            val size = f.length()
            if (f.delete()) freedBytes += size else failed++
        }
        AppLog.i(
            TAG,
            "diag: purge removed=${removed.size - failed} failed=$failed " +
                "keptBytes=${(totalBytes / 1024)}KB freedBytes=${(freedBytes / 1024)}KB " +
                "maxRuns=$MAX_KEPT_RUNS maxAgeDays=7 maxTotalMb=50"
        )
    }

    fun getLatestSnapshotJson(context: Context): String? {
        return diagDir(context).listFiles()
            ?.filter { it.name.startsWith("diagnostic_snapshot_") && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
            ?.takeIf { it.exists() }
            ?.readText()
    }

    /** Директория для диагностических файлов (снапшоты + скриншоты). */
    private fun diagDir(context: Context): File =
        (context.getExternalFilesDir(DIAG_DIR) ?: File(context.filesDir, DIAG_DIR))
            .apply { if (!exists()) mkdirs() }

    /**
     * Снимает скриншот экрана через AccessibilityService.takeScreenshot (API 30+).
     * Кладёт PNG рядом со снапшотом в diag-директорию; null — если недоступно или ошибка.
     */
    suspend fun captureScreenshot(service: AccessibilityService, stepId: String): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // Скриншоты только при FULL: в release не собираем изображения экрана.
        if (level != DiagnosticLevel.FULL) return null
        val result: AccessibilityService.ScreenshotResult =
            takeScreenshotWithRetry(service) ?: return null
        return try {
            // Сжатие PNG — тяжёлая операция: не блокируем main thread.
            val file = withContext(Dispatchers.IO) { saveScreenshotToFile(service, stepId, result) }
            if (file != null) {
                val sizeKb = file.length() / 1024
                AppLog.i(TAG, "captureScreenshot: saved ${file.absolutePath} (${sizeKb}KB)")
            }
            file
        } catch (e: Exception) {
            AppLog.e(TAG, "captureScreenshot: save failed: ${e.message}", e)
            null
        }
    }

    /**
     * Запрашивает скриншот через AccessibilityService.takeScreenshot (API 30+) с повторами
     * на транзиентных ошибках. Требуется android:canTakeScreenshot="true" в
     * accessibility_service_config.xml, иначе система отвечает NO_ACCESSIBILITY_ACCESS.
     */
    @SuppressLint("NewApi")
    private suspend fun takeScreenshotWithRetry(
        service: AccessibilityService
    ): AccessibilityService.ScreenshotResult? {
        repeat(SCREENSHOT_MAX_ATTEMPTS) { attempt ->
            val (result, errorCode) =
                suspendCancellableCoroutine<Pair<AccessibilityService.ScreenshotResult?, Int>> { cont ->
                    try {
                        service.takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            service.mainExecutor,
                            object : AccessibilityService.TakeScreenshotCallback {
                                override fun onSuccess(
                                    screenshot: AccessibilityService.ScreenshotResult
                                ) {
                                    if (cont.isActive) cont.resume(screenshot to 0)
                                }

                                override fun onFailure(errorCode: Int) {
                                    if (cont.isActive) cont.resume(null to errorCode)
                                }
                            }
                        )
                    } catch (e: Exception) {
                        AppLog.w(TAG, "captureScreenshot: takeScreenshot threw: ${e.message}")
                        if (cont.isActive) cont.resume(null to -1)
                    }
                }
            if (result != null) return result
            AppLog.e(
                TAG,
                "captureScreenshot: attempt ${attempt + 1}/$SCREENSHOT_MAX_ATTEMPTS failed: " +
                    "${screenshotErrorName(errorCode)} ($errorCode) " +
                    "displayId=${Display.DEFAULT_DISPLAY}"
            )
            if (!isTransientScreenshotError(errorCode)) return null
            delay(SCREENSHOT_RETRY_DELAY_MS)
        }
        return null
    }

    /** Человекочитаемое имя кода ошибки takeScreenshot. */
    @SuppressLint("NewApi")
    private fun screenshotErrorName(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        else -> "UNKNOWN"
    }

    /** Транзиентные ошибки: их имеет смысл повторить. */
    @SuppressLint("NewApi")
    private fun isTransientScreenshotError(errorCode: Int): Boolean =
        errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ||
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR

    @SuppressLint("NewApi")
    private fun saveScreenshotToFile(
        context: Context,
        stepId: String,
        result: AccessibilityService.ScreenshotResult
    ): File? {
        val hardwareBuffer = result.hardwareBuffer
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
            ?: run { hardwareBuffer.close(); return null }
        return try {
            val file = File(
                diagDir(context),
                "screenshot_${stepId}_${System.currentTimeMillis()}.png"
            )
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file
        } finally {
            hardwareBuffer.close()
        }
    }
}
