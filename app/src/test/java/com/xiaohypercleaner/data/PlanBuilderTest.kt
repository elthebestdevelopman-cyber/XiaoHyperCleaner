package com.xiaohypercleaner.data

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
import java.util.Locale

/**
 * Префильтр плана: ложные `app_not_installed` (P0 package visibility).
 *
 * Регресс-кейс: установленный GetApps (`com.xiaomi.mipicks`) и App Vault
 * (`com.mi.android.globalminusscreen`) обязаны попадать в план, а не
 * исключаться по устаревшим market/personalassistant-именам.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PlanBuilderTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private val profile = RomProfile(
        region = RomRegion.GLOBAL,
        miuiVersion = "V816",
        hyperOsHint = false,
        isTablet = false
    )

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(context)
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    private fun install(pkg: String) {
        val info = PackageInfo().apply { packageName = pkg }
        Shadows.shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `installed mipicks keeps getapps steps in plan`() {
        install("com.xiaomi.mipicks")

        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertTrue("getapps должен попасть в план", plan.contains("getapps"))
        assertTrue("notif_getapps должен попасть в план", plan.contains("notif_getapps"))
        assertFalse("messages_sys: Xiaomi Messages нет", plan.contains("messages_sys"))
    }

    @Test
    fun `installed globalminusscreen keeps appvault steps in plan`() {
        install("com.mi.android.globalminusscreen")

        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertTrue(plan.contains("appvault_services"))
        assertTrue(plan.contains("appvault_about"))
        assertTrue(plan.contains("notif_appvault"))
    }

    @Test
    fun `notif transparency off excludes notification steps`() {
        install("com.xiaomi.mipicks")
        install("com.mi.android.globalminusscreen")

        val plan = PlanBuilder.build(context, profile, notifTransparency = false).map { it.id }

        assertFalse("notif_* исключены при выключенной прозрачности", plan.any { it.startsWith("notif_") })
        assertTrue("обычные шаги остаются", plan.contains("getapps"))
    }

    @Test
    fun `unknown package steps are excluded`() {
        val plan = PlanBuilder.build(context, profile).map { it.id }

        // GetApps/App Vault не установлены в этом сценарии — шаги вне плана.
        assertFalse(plan.contains("getapps"))
        assertFalse(plan.contains("appvault_services"))
    }

    @Test
    fun `folder step is excluded when the launcher is not MIUI`() {
        // Шаг папок рабочего стола имеет смысл только в лаунчере MIUI/HyperOS.
        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertFalse(
            "папки рабочего стола только для лаунчера MIUI",
            plan.contains("folder_recommendations")
        )
    }

    @Test
    fun `hyperos3 keeps security and cleaner steps in plan`() {
        // Красный список не исключает UI-шаги: на HyperOS 3 Security = com.miui.securitycore.
        install("com.miui.securitycore")
        val hyperOs3 = RomProfile(
            region = RomRegion.GLOBAL,
            miuiVersion = "OS3.0.1.0",
            hyperOsHint = true,
            isTablet = false,
            family = RomFamily.HYPEROS,
            uiVersion = "3"
        )

        val plan = PlanBuilder.build(context, hyperOs3).map { it.id }

        assertTrue("security_sys остаётся в плане", plan.contains("security_sys"))
        assertTrue("cleaner остаётся в плане", plan.contains("cleaner"))
    }

    @Test
    fun `destructive action over never touch package is forbidden`() {
        val neverTouch = SemanticCatalog.neverTouchPackages()
        assertTrue("красный список загружен", neverTouch.isNotEmpty())

        assertTrue(
            "disable com.miui.securitycore запрещён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.TOGGLE,
                destructiveAction = "disable",
                packages = listOf("com.miui.securitycore"),
                neverTouch = neverTouch
            )
        )
        assertTrue(
            "clear data over never-touch package запрещён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.CLEAR_DATA_DECLINE,
                destructiveAction = null,
                packages = listOf("com.miui.daemon", "com.miui.securitycenter"),
                neverTouch = neverTouch
            )
        )
        assertFalse(
            "обычный тумблер в приложении пакета разрешён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.TOGGLE,
                destructiveAction = null,
                packages = listOf("com.miui.securitycore"),
                neverTouch = neverTouch
            )
        )
        assertFalse(
            "clear data обычного приложения разрешён",
            PlanBuilder.isForbiddenDestructive(
                actionType = SimpleSteps.ActionType.CLEAR_DATA_DECLINE,
                destructiveAction = null,
                packages = listOf("com.mi.android.globalFileexplorer"),
                neverTouch = neverTouch
            )
        )
    }

    @Test
    fun `installer recommendations step is out of the plan for both variants`() {
        // F7: шаг «Рекомендации установщика» живёт только в окне установки (его ловит ловец
        // и предшаг «одной кнопки»), а в обычном плане падал `candidates=0 за 257 мс`
        // (прогон rmvb6atul). Отключён для ОБОИХ вариантов каталога, причина видна в плане.
        val original = Locale.getDefault()
        try {
            // ru → global_ru, en → cn_hyperos (диспетчер варианта по языку устройства).
            listOf("ru" to "global_ru", "en" to "cn_hyperos").forEach { (lang, variant) ->
                Locale.setDefault(Locale.forLanguageTag(lang))
                AdaptiveCatalog.resetForTest()
                SemanticCatalog.resetForTest()
                SemanticCatalog.ensureLoaded(context)

                assertEquals(
                    "вариант каталога по языку",
                    variant,
                    AdaptiveCatalog.selectVariant(context, profile)
                )
                val built = PlanBuilder.buildDetailed(context, profile)

                assertFalse(
                    "шаг не запускается в плане варианта $variant",
                    built.steps.any { it.id == "installer_recommendations" }
                )
                assertTrue(
                    "причина исключения обязана быть видна: ${built.excluded}",
                    built.excluded.contains("installer_recommendations:disabled_for_variant")
                )
            }
        } finally {
            Locale.setDefault(original)
            AdaptiveCatalog.resetForTest()
        }
    }

    @Test
    fun `home suggestions step is disabled only for the global_ru variant`() {
        // F8-3/разбор hs_recon: на POCO Launcher (com.mi.android.globallauncher) настройки
        // «Показывать предложения» нет ни в лаунчере, ни в Ленте виджетов — шаг отключён
        // ТОЛЬКО для global_ru; для cn_hyperos (системный com.miui.home) он остаётся в плане.
        // При системном лаунчере перепроверить: путь там другой (см. handoff).
        install("com.miui.home")
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            AdaptiveCatalog.resetForTest()
            SemanticCatalog.resetForTest()
            SemanticCatalog.ensureLoaded(context)

            // Вариант НЕ выбирается вручную ДО плана: его обязан выбрать сам PlanBuilder, иначе
            // `disabledSteps` читаются из дефолтного cn_hyperos и шаг не исключается для global_ru
            // (прогон rmve63243: `plan steps=27 excluded=2` и падение `launcher_setting_absent`).
            val ru = PlanBuilder.buildDetailed(context, profile)
            assertEquals("вариант каталога по языку", "global_ru", AdaptiveCatalog.selectVariant(context, profile))
            assertFalse(
                "для global_ru шаг предложений лаунчера не запускается",
                ru.steps.any { it.id == "home_suggestions" }
            )
            assertTrue(
                "причина исключения обязана быть видна: ${ru.excluded}",
                ru.excluded.contains("home_suggestions:disabled_for_variant")
            )

            Locale.setDefault(Locale.forLanguageTag("en"))
            AdaptiveCatalog.resetForTest()
            SemanticCatalog.resetForTest()
            SemanticCatalog.ensureLoaded(context)

            assertEquals("вариант каталога по языку", "cn_hyperos", AdaptiveCatalog.selectVariant(context, profile))
            val cn = PlanBuilder.buildDetailed(context, profile)
            assertTrue(
                "cn_hyperos не трогали: шаг остаётся в плане",
                cn.steps.any { it.id == "home_suggestions" }
            )
            assertFalse(
                "у cn_hyperos нет исключения по варианту",
                cn.excluded.contains("home_suggestions:disabled_for_variant")
            )
        } finally {
            Locale.setDefault(original)
            AdaptiveCatalog.resetForTest()
        }
    }

    @Test
    fun `carousel is excluded when fashiongallery is absent`() {
        // R2-3: карусель обоев живёт в com.miui.android.fashiongallery. Без пакета
        // экрана на устройстве нет: шаг не должен попадать в план (прогон rmupuzwh1 —
        // маршрут падал ActivityNotFound и «подтверждался» на чужом экране Настроек).
        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertFalse("без fashiongallery карусели на устройстве нет", plan.contains("carousel"))
    }

    @Test
    fun `carousel stays in plan when fashiongallery is installed`() {
        // Обратная сторона гейта: имя пакета в каталоге обязано совпадать с реальным,
        // иначе шаг молча выпадет из плана на живом устройстве.
        install("com.miui.android.fashiongallery")

        val plan = PlanBuilder.build(context, profile).map { it.id }

        assertTrue("с установленным fashiongallery шаг остаётся в плане", plan.contains("carousel"))
    }
}