package com.xiaohypercleaner.data

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Точка входа приложения-шага (прогон rmu8qhjhi).
 *
 * Регресс: launcher-интенты (`MAIN` + `CATEGORY_LAUNCHER`, без `CATEGORY_DEFAULT`)
 * отбраковывались проверкой `MATCH_DEFAULT_ONLY` — у `getapps` оставался только
 * Settings-фолбэк, магазин поднимался последним рубежом и бурение шло по сплэшу
 * (`Intent not available: android.intent.action.MAIN / com.xiaomi.mipicks`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DirectIntentNavigatorTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val profile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V130",
        hyperOsHint = false,
        isTablet = false,
        family = RomFamily.MIUI,
        uiVersion = "13"
    )

    @Before
    fun setUp() {
        // Кэш интентов статический — иначе тесты делят один результат шага.
        DirectIntentNavigator.clearCache()
    }

    @After
    fun tearDown() {
        DirectIntentNavigator.clearCache()
    }

    /** Пакет с launcher-активностью, объявленной без CATEGORY_DEFAULT (как GetApps). */
    private fun installLauncherOnly(pkg: String) {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        shadowPm.installPackage(PackageInfo().apply { packageName = pkg })
        val component = ComponentName(pkg, "$pkg.Launcher")
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun getappsStep() = SimpleSteps.ALL.first { it.id == "getapps" }

    @Test
    fun `installed getapps keeps a launcher entry in the chain`() {
        installLauncherOnly("com.xiaomi.mipicks")

        val intents = DirectIntentNavigator.buildIntentsForStep(
            context,
            getappsStep(),
            "com.xiaomi.mipicks",
            profile
        )

        assertTrue(
            "у getapps должна быть рабочая точка входа в магазин, а не только фолбэк",
            intents.any { it.`package` == "com.xiaomi.mipicks" }
        )
    }

    @Test
    fun `installed privacy activity leads the getapps intent chain`() {
        // Дамп устройства: экран «Конфиденциальность» («гайка» в профиле) открывается
        // напрямую экспортированной активностью PrivacyPreferenceFragmentActivity —
        // она и должна идти первой, иначе drill упирается в нативный
        // MarketPreferenceActivity без «Конфиденциальности» (прогон rmua2sd7x).
        val shadowPm = Shadows.shadowOf(context.packageManager)
        shadowPm.installPackage(PackageInfo().apply { packageName = "com.xiaomi.mipicks" })
        val privacy = ComponentName(
            "com.xiaomi.mipicks",
            "com.xiaomi.market.ui.PrivacyPreferenceFragmentActivity"
        )
        shadowPm.addActivityIfNotPresent(privacy)

        val intents = DirectIntentNavigator.buildIntentsForStep(
            context,
            getappsStep(),
            "com.xiaomi.mipicks",
            profile
        )

        assertTrue(
            "первым интентом должен быть прямой вход на экран «Конфиденциальность»",
            intents.firstOrNull()?.component == privacy
        )
    }

    @Test
    fun `absent getapps package has no launcher entry`() {
        val intents = DirectIntentNavigator.buildIntentsForStep(
            context,
            getappsStep(),
            "com.xiaomi.mipicks",
            profile
        )

        assertFalse(
            "неустановленный магазин не должен давать интент-призрак",
            intents.any { it.`package` == "com.xiaomi.mipicks" }
        )
    }

    @Test
    fun `security settings chain leads to the security settings screen`() {
        // Дамп устройства: экран настроек Безопасности объявлен под
        // SECURITYCENTER_SETTINGS. `miui.intent.action.APP_SETTINGS` там без
        // CATEGORY_DEFAULT и неявным интентом не резолвится вовсе
        // (`am start -a` → "unable to resolve"), поэтому в цепочке его быть не должно.
        val intents = DirectIntentNavigator.securitySettingsIntents()

        assertEquals(
            "первым должен идти вход на экран настроек Безопасности",
            "com.miui.securitycenter.action.SECURITYCENTER_SETTINGS",
            intents.first().action
        )
        assertTrue(
            "страховкой остаётся явная компонента экрана настроек",
            intents.any {
                it.component == ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securityscan.ui.settings.SettingsActivity"
                )
            }
        )
        assertFalse(
            "APP_SETTINGS нерезолвим неявным интентом и вводит шаг в заблуждение",
            intents.any { it.action == "miui.intent.action.APP_SETTINGS" }
        )
    }

    @Test
    fun `cleaner chain stays inside the cleaner package`() {
        // Дамп устройства: «Настройки очистки» — это com.miui.cleaner, а не экран
        // Безопасности с одноимённой строкой «Получать рекомендации».
        val intents = DirectIntentNavigator.cleanerSettingsIntents()

        assertEquals(
            "очистка открывается своим действием",
            "com.miui.securitycenter.action.GARBAGE_CLEANUP_SETTINGS",
            intents.first().action
        )
        assertEquals("com.miui.cleaner", intents.first().`package`)
        assertTrue(
            "Безопасность в цепочке очистки — это чужой экран с теми же строками",
            intents.none {
                it.`package` == "com.miui.securitycenter" ||
                    it.component?.packageName == "com.miui.securitycenter"
            }
        )
    }

    @Test
    fun `downloads chain pins the list instead of asking the user`() {
        // Неявный VIEW_DOWNLOADS показывает диалог «Что использовать?»
        // («Загрузки» / «Файлы», дамп resolver_now) — пакет задаём явно.
        val intents = DirectIntentNavigator.downloadListIntents()

        assertEquals("android.intent.action.VIEW_DOWNLOADS", intents.first().action)
        assertEquals("com.android.providers.downloads.ui", intents.first().`package`)
        assertTrue(
            "страховкой остаётся явная компонента списка загрузок",
            intents.any {
                it.component == ComponentName(
                    "com.android.providers.downloads.ui",
                    "com.android.providers.downloads.ui.DownloadList"
                )
            }
        )
    }

    @Test
    fun `launcher settings lead the home suggestions chain`() {
        // POCO Launcher: настройки лаунчера живут в отдельном пакете
        // (com.mi.android.globallauncher, дамп home_settings_desktop), пункта
        // «Рабочий стол» в системных Настройках на POCO нет.
        val intents = DirectIntentNavigator.launcherSettingsIntents()

        assertEquals("com.mi.android.globallauncher.Setting", intents.first().action)
        assertEquals("com.mi.android.globallauncher", intents.first().`package`)
        assertTrue(
            "настройки POCO Launcher открываются через HomeSettingsActivity",
            intents.any {
                it.component == ComponentName(
                    "com.mi.android.globallauncher",
                    "com.miui.home.settings.HomeSettingsActivity"
                )
            }
        )
    }

    @Test
    fun `carousel chain opens the wallpaper carousel settings`() {
        // POCO/MIUI 13: пункта «Карусель обоев» в «Блокировке экрана» нет вовсе, зато у
        // приложения карусели есть экспортированное действие настроек (дамп carousel_setting_act).
        val intents = DirectIntentNavigator.carouselSettingsIntents()

        assertEquals("com.miui.android.fashiongallery.setting.SETTING", intents.first().action)
        assertEquals("com.miui.android.fashiongallery", intents.first().`package`)
        assertTrue(
            "страховкой остаётся явная компонента экрана настроек карусели",
            intents.any {
                it.component == ComponentName(
                    "com.miui.android.fashiongallery",
                    "com.miui.cw.feature.ui.setting.SettingActivity"
                )
            }
        )
    }
}