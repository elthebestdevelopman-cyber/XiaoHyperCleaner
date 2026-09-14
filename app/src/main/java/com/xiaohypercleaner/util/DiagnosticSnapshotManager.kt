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
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomProfile
import kotlinx.coroutines.suspendCancellableCoroutine
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
        return try {
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
            @Suppress("DEPRECATION")
            fun dump(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_TREE_DEPTH || collectedCount > MAX_NODES_COLLECTED) return
                collectedCount++
                val indent = "  ".repeat(depth)
                val rect = Rect()
                node.getBoundsInScreen(rect)
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
                dumpTree.appendLine(
                    "$indent<$cls id=\"$id\" bounds=\"${rect.toShortString()}\" $flags text=\"$text\" desc=\"$desc\"/>"
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
                "Saved local diagnostic snapshot for step '$stepId' (reason=$failureReason) to ${file.absolutePath}"
            )
            file
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to capture diagnostic snapshot: ${e.message}")
            null
        }
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
        return suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val file = try {
                                saveScreenshotToFile(service, stepId, screenshot)
                            } catch (e: Exception) {
                                AppLog.w(TAG, "captureScreenshot: save failed: ${e.message}")
                                null
                            }
                            if (cont.isActive) cont.resume(file)
                        }

                        override fun onFailure(errorCode: Int) {
                            AppLog.w(TAG, "captureScreenshot: onFailure code=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "captureScreenshot: takeScreenshot failed: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

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
