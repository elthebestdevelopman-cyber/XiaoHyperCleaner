package com.xiaohypercleaner.util

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.pm.PackageInfoCompat
import com.xiaohypercleaner.data.RomProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    private const val SNAPSHOT_FILE_NAME = "diagnostic_snapshot.json"
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
                put("screenDump", dumpTree.toString().trim())
            }

            val file = File(context.filesDir, SNAPSHOT_FILE_NAME)
            file.writeText(json.toString(2))
            AppLog.i(
                TAG,
                "Saved local diagnostic snapshot for step '$stepId' (reason=$failureReason) to ${file.name}"
            )
            file
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to capture diagnostic snapshot: ${e.message}")
            null
        }
    }

    fun getLatestSnapshotJson(context: Context): String? {
        val file = File(context.filesDir, SNAPSHOT_FILE_NAME)
        return if (file.exists()) file.readText() else null
    }
}
