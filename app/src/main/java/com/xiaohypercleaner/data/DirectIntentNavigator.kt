package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Генератор Intent для шагов Simple Mode.
 *
 * Важно для MIUI/HyperOS: «глубокие» Settings Activity часто отсутствуют или
 * уводят на чужие экраны (Google Privacy, список «Все приложения» вместо хаба
 * «Приложения»). Для системных маршрутов с drillPath используем только корень
 * Settings — дальше идёт проверенный drill по каталогу.
 */
object DirectIntentNavigator {

    fun buildIntentsForStep(
        context: Context,
        step: SimpleSteps.Step,
        resolvedPkg: String?,
        @Suppress("UNUSED_PARAMETER") profile: RomProfile
    ): List<Intent> {
        val intents = mutableListOf<Intent>()

        when (step.id) {
            // Системные шаги: только корень Settings + drillPath (без ломающих deep-link).
            "msa",
            "sys_recommendations",
            "ads_personalization",
            "ux_program",
            "carousel",
            "home_suggestions" -> {
                intents.add(settingsRoot())
            }

            "security_sys" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.miui.securityscan.ui.settings.SettingsActivity")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "cleaner" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(Intent("miui.intent.action.GARBAGE_CLEAN").setPackage(pkg))
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.miui.cleanmaster.CleanMaster")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "browser_sys" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.android.browser.preferences.SettingsActivity")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "music_sys" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.miui.player.ui.PreferencesActivity")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "downloads" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.android.providers.downloads.ui.DownloadList")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "themes" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.android.thememanager.settings.ThemeSettingsActivity")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "mivideo" -> {
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent().setComponent(
                            ComponentName(pkg, "com.miui.videoplayer.preferences.SettingActivity")
                        )
                    )
                    getLaunchIntent(context, pkg)?.let { intents.add(it) }
                }
            }

            "filemanager" -> {
                // Только карточка приложения в Settings (Clear data), не сам Проводник
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$pkg")
                        }
                    )
                }
            }

            else -> {
                if (step.id.startsWith("notif_") && resolvedPkg != null) {
                    intents.add(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, resolvedPkg)
                            // MIUI иногда читает эти extras вместо EXTRA_APP_PACKAGE
                            putExtra("app_package", resolvedPkg)
                            putExtra("package", resolvedPkg)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                } else if (resolvedPkg != null) {
                    getLaunchIntent(context, resolvedPkg)?.let { intents.add(it) }
                }
            }
        }

        // Для notif_/filemanager не подмешиваем устаревшие step.intents с чужим пакетом
        if (!step.id.startsWith("notif_") && step.id != "filemanager") {
            intents.addAll(step.intents)
        } else if (intents.isEmpty()) {
            intents.addAll(step.intents)
        }

        return intents.map { intent ->
            Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private fun settingsRoot(): Intent =
        Intent(Settings.ACTION_SETTINGS).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

    private fun getLaunchIntent(context: Context, packageName: String): Intent? {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
