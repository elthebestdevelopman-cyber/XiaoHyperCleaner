package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Интеллектуальный генератор прямых Intent-вызовов для экранов настроек Xiaomi / HyperOS.
 *
 * Исключает слепой поиск и блуждание по меню:
 * 1. Формирует явные (Explicit) или целевые (Deep-link) Intent для конкретных версий MIUI/HyperOS
 * 2. Применяет динамически определенный установленный пакет из package_aliases.json
 * 3. Имеет безопасный fallback на корневой экран или стандартный запуск при отсутствии глубокой Activity
 */
object DirectIntentNavigator {

    private const val SETTINGS_PKG = "com.android.settings"

    fun buildIntentsForStep(
        context: Context,
        step: SimpleSteps.Step,
        resolvedPkg: String?,
        profile: RomProfile
    ): List<Intent> {
        val intents = mutableListOf<Intent>()

        // Специфичные прямые вызовы по step.id
        when (step.id) {
            "msa" -> {
                // Доступ к личным данным / Авторизация и отзыв
                intents.add(
                    Intent().setComponent(
                        ComponentName(SETTINGS_PKG, "com.android.settings.Settings\$PersonalDataAccessActivity")
                    )
                )
                intents.add(
                    Intent().setComponent(
                        ComponentName(SETTINGS_PKG, "com.android.settings.Settings\$AuthorizationRevocationActivity")
                    )
                )
                intents.add(Intent("android.settings.AUTHORIZATION_SETTINGS"))
                intents.add(Intent("com.android.settings.action.LICENSE_ACTIVITY"))
                intents.add(
                    Intent(Settings.ACTION_SETTINGS).putExtra(
                        ":settings:show_fragment",
                        "com.android.settings.SpecialAccessSettings"
                    )
                )
                intents.add(Intent(Settings.ACTION_SETTINGS))
            }

            "sys_recommendations" -> {
                // Все приложения / Управление приложениями
                intents.add(
                    Intent().setComponent(
                        ComponentName(SETTINGS_PKG, "com.android.settings.applications.ManageApplications")
                    )
                )
                intents.add(Intent("android.settings.MANAGE_APPLICATIONS_SETTINGS"))
                intents.add(Intent(Settings.ACTION_SETTINGS))
            }

            "ads_personalization" -> {
                // Рекламные службы / Персонализация рекламы
                intents.add(Intent("com.android.settings.action.AD_SERVICES"))
                intents.add(Intent("android.settings.AD_SERVICES_SETTINGS"))
                intents.add(Intent("android.settings.PRIVACY_SETTINGS"))
                intents.add(
                    Intent(Settings.ACTION_SETTINGS).putExtra(
                        ":settings:show_fragment",
                        "com.android.settings.privacy.PrivacyDashboardSettings"
                    )
                )
                intents.add(Intent(Settings.ACTION_SETTINGS))
            }

            "ux_program" -> {
                // Конфиденциальность / Программа улучшения качества
                intents.add(Intent("android.settings.PRIVACY_SETTINGS"))
                intents.add(
                    Intent(Settings.ACTION_SETTINGS).putExtra(
                        ":settings:show_fragment",
                        "com.android.settings.privacy.PrivacyDashboardSettings"
                    )
                )
                intents.add(Intent(Settings.ACTION_SETTINGS))
            }

            "carousel" -> {
                // Блокировка экрана / Карусель обоев
                intents.add(
                    Intent().setComponent(
                        ComponentName(SETTINGS_PKG, "com.android.settings.Settings\$LockScreenSettingsActivity")
                    )
                )
                intents.add(Intent("com.android.settings.LOCKSCREEN_SETTINGS"))
                intents.add(Intent(Settings.ACTION_SETTINGS))
            }

            "home_suggestions" -> {
                // Рабочий стол
                intents.add(Intent(Settings.ACTION_HOME_SETTINGS))
                intents.add(Intent(Settings.ACTION_SETTINGS))
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
                resolvedPkg?.let { pkg ->
                    intents.add(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$pkg".toUri()
                        }
                    )
                }
            }

            else -> {
                // Если шаг уведомительный: notif_*
                if (step.id.startsWith("notif_") && resolvedPkg != null) {
                    intents.add(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, resolvedPkg)
                        }
                    )
                } else if (resolvedPkg != null) {
                    getLaunchIntent(context, resolvedPkg)?.let { intents.add(it) }
                }
            }
        }

        // Добавляем статические интенты из шага как запасные
        intents.addAll(step.intents)

        // Добавляем флаги FLAG_ACTIVITY_NEW_TASK для корректного запуска из службы
        return intents.map { intent ->
            Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private fun getLaunchIntent(context: Context, packageName: String): Intent? {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
