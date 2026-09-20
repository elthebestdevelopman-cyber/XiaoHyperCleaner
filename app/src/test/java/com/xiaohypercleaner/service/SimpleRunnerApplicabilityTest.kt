package com.xiaohypercleaner.service

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomFamily
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.RomRegion
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
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
}