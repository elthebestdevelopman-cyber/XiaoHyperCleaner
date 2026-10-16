package com.xiaohypercleaner.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Собственная сборка как APK для предшага установщика: [Intent.ACTION_VIEW] на наш же файл
 * открывает окно установщика, в котором ловец снимает «Получать рекомендации».
 *
 * Почему копия, а не `packageCodePath` напрямую: установщик читает файл только по
 * FileProvider-URI, а `cacheDir/apk_update` уже покрыт `res/xml/file_paths.xml`
 * (`<cache-path name="cache" path="."/>`) — правок манифеста не требуется.
 * Копия живёт до переустановки: чистится на старте приложения ([purge]) и при неудачном
 * открытии установщика.
 */
internal object SelfApkExporter {

    private const val TAG = "SelfApkExporter"
    private const val DIR_NAME = "apk_update"
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Каталог копий (один файл, перезаписывается). */
    internal fun dir(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** Удаляет старые копии, возвращает число удалённых файлов. */
    internal fun purge(context: Context): Int {
        val files = dir(context).listFiles() ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    /**
     * Потоково копирует текущую сборку в кэш. null — копирование не удалось (нет места,
     * sourceDir недоступен): вызывающий обязан пойти обычным путём, без ожидания установки.
     */
    internal fun copyToCache(context: Context): File? = runCatching {
        val source = File(context.packageCodePath)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        val targetDir = dir(context).apply { if (!exists()) mkdirs() }
        val target = File(targetDir, "${context.packageName}-$version.apk")
        if (target.exists() && !target.delete()) {
            AppLog.w(TAG, "stale copy not deleted: ${target.name}")
        }
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // Размер — коротким числом в килобайтах: восьмизначное число в байтах ловит
        // LogMasker.phoneRegex (3+3+2 цифры) и уезжает в лог как `***-***-**-**`.
        AppLog.i(TAG, "self apk copied: ${target.name}, size=${target.length() / 1024} KB")
        target
    }.onFailure { e ->
        AppLog.w(TAG, "self apk copy failed: ${e.message}")
    }.getOrNull()

    /** Открывает окно установщика на копии. false — интент не найден/отклонён системой. */
    internal fun openInstaller(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Резолв пишем ДО старта: связка «права на установку нет → окно не открылось»
        // остаётся гипотезой приёмки (прогон rmvb6atul: `installer opened`, но у MainActivity
        // 0 onPause/onStop). По строке видно, находит ли MIUI активность под ACTION_VIEW на APK.
        val resolved = context.packageManager.queryIntentActivities(intent, 0)
        AppLog.i(
            TAG,
            "self apk installer resolve: count=${resolved.size} " +
                "first=${resolved.firstOrNull()?.activityInfo?.packageName}"
        )
        context.startActivity(intent)
        AppLog.i(TAG, "self apk installer opened: ${apk.name}")
        true
    }.onFailure { e ->
        AppLog.w(TAG, "self apk installer not opened: ${e.message}")
    }.getOrDefault(false)
}