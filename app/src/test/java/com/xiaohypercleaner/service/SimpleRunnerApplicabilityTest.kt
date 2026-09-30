package com.xiaohypercleaner.service

import org.junit.Assert.assertEquals

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomFamily
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.RomRegion
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Применимость шага: вход notif_* и чужой экран вместо целевого.
 *
 * Регрессия прогона rmu8qhjhi:
 * - `notif_appvault`/`notif_getapps` остались на «Заблокированном экране», где
 *   встречаются те же ключевые слова («Показывать уведомления полностью») — вход
 *   обязан подтверждаться подписью приложения, иначе шаг неприменим и не тумблится;
 * - `home_suggestions`/`appvault_*` открывали настройки POCO Launcher вместо
 *   Настроек: строк шага там нет вовсе — честный `not_applicable`, а не FAIL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerApplicabilityTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        SemanticCatalog.selectVariant(
            RomProfile(
                region = RomRegion.GLOBAL,
                miuiVersion = "V130",
                hyperOsHint = false,
                isTablet = false,
                family = RomFamily.MIUI,
                uiVersion = "13"
            )
        )
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun node(
        text: String? = null,
        packageName: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (packageName != null) Mockito.`when`(n.packageName).thenReturn(packageName)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    private fun settingsStep(id: String) = SimpleSteps.Step(
        id = id,
        titleRu = id,
        titleEn = id,
        descRu = id,
        descEn = id,
        intents = emptyList(),
        searchTexts = emptyList(),
        manualHintRu = id,
        manualHintEn = id
    )

    /** Подпись приложения для проверки входа notif_* (как её отдаёт PackageManager). */
    private fun appLabelOf(pkg: String, label: String): PackageManager {
        val pm = Mockito.mock(PackageManager::class.java)
        val info = ApplicationInfo()
        @Suppress("DEPRECATION")
        Mockito.`when`(pm.getApplicationInfo(pkg, 0)).thenReturn(info)
        Mockito.`when`(pm.getApplicationLabel(info)).thenReturn(label)
        Mockito.`when`(service.packageManager).thenReturn(pm)
        return pm
    }

    @Test
    fun `notif entry is rejected on a foreign screen with the same keywords`() {
        appLabelOf("com.mi.android.globalminusscreen", "Лента виджетов")
        val lockScreenText = node(
            text = "Заблокированный экран Показывать уведомления полностью Подпись на экране блокировки"
        )
        val root = node(packageName = "com.android.settings", children = arrayOf(lockScreenText))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse(
            "чужой экран без подписи приложения не может быть входом notif_-шага",
            runner.isAppNotificationScreen(
                "com.mi.android.globalminusscreen",
                listOf("Показывать уведомления", "Разрешить уведомления")
            )
        )
    }

    @Test
    fun `notif entry is accepted on the app notification screen`() {
        appLabelOf("com.mi.android.globalminusscreen", "Лента виджетов")
        val notifText = node(
            text = "Лента виджетов Показывать уведомления Разрешить метки уведомлений"
        )
        val root = node(packageName = "com.android.settings", children = arrayOf(notifText))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertTrue(
            "экран уведомлений приложения подтверждается подписью приложения",
            runner.isAppNotificationScreen(
                "com.mi.android.globalminusscreen",
                listOf("Показывать уведомления", "Разрешить уведомления")
            )
        )
    }

    @Test
    fun `settings step opened in another app is not applicable`() {
        val pocoText = node(text = "Рабочий стол ПОКО Launcher ПЕРСОНАЛИЗАЦИЯ Набор значков Настроить макет")
        val pocoRow = node(children = arrayOf(pocoText))
        val root = node(packageName = "com.mi.android.globallauncher", children = arrayOf(pocoRow))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val step = SimpleSteps.ALL.first { it.id == "home_suggestions" }

        assertTrue(
            "строк шага нет на чужом экране — шаг неприменим",
            runner.isForeignScreenForSettingsStep(step, resolvedPkg = null)
        )
    }

    @Test
    fun `settings step inside Settings is not treated as foreign`() {
        val settingsText = node(text = "Рабочий стол Показывать предложения")
        val root = node(packageName = "com.android.settings", children = arrayOf(settingsText))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val step = SimpleSteps.ALL.first { it.id == "home_suggestions" }

        assertFalse(
            "чужой экран — только другое приложение, а не Настройки",
            runner.isForeignScreenForSettingsStep(step, resolvedPkg = null)
        )
    }

    @Test
    fun `app steps are never classified as foreign screen`() {
        val pocoText = node(text = "Рабочий стол")
        val root = node(packageName = "com.mi.android.globallauncher", children = arrayOf(pocoText))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse(
            "для шага-приложения чужой экран не применяется",
            runner.isForeignScreenForSettingsStep(
                settingsStep("themes"),
                resolvedPkg = "com.android.thememanager"
            )
        )
    }

    @Test
    fun `skip kinds separate device absence from automation miss`() {
        // Ведро причины решает текст отчёта: «нет на устройстве», «нет в лаунчере» или
        // «робот не нашёл». Смешивать их нельзя — отчёт врал бы (прогон rmuk44un7).
        assertEquals(
            SimpleRunner.SkipKind.NOT_ON_DEVICE,
            SimpleRunner.classifySkip("app_not_installed")
        )
        // Навигационный провал («не нашёл уровень/экран») — ведро UNRESOLVED: раньше
        // not_applicable уезжал в «нет на устройстве» и отчёт обвинял устройство.
        assertEquals(
            SimpleRunner.SkipKind.UNRESOLVED,
            SimpleRunner.classifySkip(SimpleRunner.NOT_APPLICABLE)
        )
        assertEquals(
            SimpleRunner.SkipKind.LAUNCHER_ABSENT,
            SimpleRunner.classifySkip(SimpleRunner.LAUNCHER_ABSENT)
        )
        assertEquals(
            SimpleRunner.SkipKind.UNRESOLVED,
            SimpleRunner.classifySkip("low_confidence")
        )
        assertEquals(
            SimpleRunner.SkipKind.NOT_ON_DEVICE,
            SimpleRunner.classifySkip("folder_switch_absent")
        )
        assertEquals(
            SimpleRunner.SkipKind.NOT_ON_DEVICE,
            SimpleRunner.classifySkip("switch_disabled")
        )
        assertEquals(
            SimpleRunner.SkipKind.NOT_ON_DEVICE,
            SimpleRunner.classifySkip("installer_settings_not_found")
        )
        assertEquals(
            SimpleRunner.SkipKind.NOT_ON_DEVICE,
            SimpleRunner.classifySkip("installer_settings_denied")
        )
        assertEquals(
            SimpleRunner.SkipKind.UNRESOLVED,
            SimpleRunner.classifySkip("drill_failed")
        )
        assertEquals(
            SimpleRunner.SkipKind.UNRESOLVED,
            SimpleRunner.classifySkip("screen_markers_absent")
        )
        assertEquals(SimpleRunner.SkipKind.UNRESOLVED, SimpleRunner.classifySkip(null))
        // Промах на папках рабочего стола — «не нашёл», а не «нет на устройстве».
        assertEquals(
            SimpleRunner.SkipKind.UNRESOLVED,
            SimpleRunner.classifySkip(SimpleRunner.FOLDER_EDITOR_NOT_OPENED)
        )
    }
/**
     * P5: перед объявлением провала шаг проверяет состояние ещё раз. Прогоны
     * rmuoaz4jm/rmuod5cmm: шаг падал `timeout`/`verify_failed`, хотя настройка на
     * устройстве применилась — MIUI отрисовывает новое состояние с задержкой.
     */
    @Test
    fun `late verify confirms applied state on the step screen`() = runTest {
        val step = SimpleSteps.ALL.first { it.id == "carousel" }
        val root = node(
            text = "Карусель обоев",
            children = arrayOf(carouselSwitch(checked = false))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertTrue(
            "состояние совпало с целевым — провал отменяется",
            runner.lateVerify(step)
        )
    }

    @Test
    fun `late verify never confirms on a foreign screen`() = runTest {
        // Поздняя проверка обязана подтверждать экран маркерами шага: иначе «состояние
        // совпало» читается на чужом экране (прогон rmuod5cmm: PERCEPTION pkg=com.google.android.gms).
        // Тумблер чужой строки специально без подписи карусели — маркеров на экране нет.
        val step = SimpleSteps.ALL.first { it.id == "carousel" }
        val foreignSwitch = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(foreignSwitch.className).thenReturn("android.widget.Switch")
        Mockito.`when`(foreignSwitch.isCheckable).thenReturn(true)
        Mockito.`when`(foreignSwitch.isChecked).thenReturn(false)
        Mockito.`when`(foreignSwitch.contentDescription)
            .thenReturn("Показывать уведомления Разрешить метки уведомлений")
        Mockito.`when`(foreignSwitch.childCount).thenReturn(0)
        val root = node(
            text = "Использование и диагностика",
            children = arrayOf(foreignSwitch)
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse("на чужом экране успех не подтверждается", runner.lateVerify(step))
    }

    @Test
    fun `late verify keeps failure when the switch is still on`() = runTest {
        val step = SimpleSteps.ALL.first { it.id == "carousel" }
        val root = node(
            text = "Карусель обоев",
            children = arrayOf(carouselSwitch(checked = true))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse("тумблер включён — провал остаётся", runner.lateVerify(step))
    }

    /** Тумблер карусели: подпись в content-desc, узел без текста (как на устройстве). */
    private fun carouselSwitch(checked: Boolean): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        Mockito.`when`(n.className).thenReturn("android.widget.Switch")
        Mockito.`when`(n.isCheckable).thenReturn(true)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.contentDescription)
            .thenReturn("Карусель экрана блокировки Просмотр выбранных обоев, историй")
        Mockito.`when`(n.childCount).thenReturn(0)
        return n
    }
}