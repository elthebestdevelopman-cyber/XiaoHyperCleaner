package com.xiaohypercleaner.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.RomFamily
import com.xiaohypercleaner.data.RomProfile
import com.xiaohypercleaner.data.RomRegion
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Готовность экрана приложения перед бурением (прогон rmu8qhjhi).
 *
 * GetApps (`com.xiaomi.mipicks`) открывался, но бурение шло по сплэшу магазина
 * («Официальный магазин от Xiaomi») и падало `drill_failed`: уровень «Профиль»
 * появляется только после загрузки UI. Ожидание идёт по подписям первого уровня
 * маршрута, не по фиксированной паузе, и не съедает бюджет шага целиком.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerAppEntryTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    private val getappsPath = listOf(
        listOf("Профиль", "Profile"),
        listOf("Настройки", "Settings"),
        listOf("Конфиденциальность", "Privacy")
    )

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
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
        className: String? = null,
        packageName: String? = null,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        if (packageName != null) Mockito.`when`(n.packageName).thenReturn(packageName)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    private fun step(id: String) = SimpleSteps.Step(
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

    @Test
    fun `app entry waits until the store UI is loaded`() = runTest {
        val splash = node(text = "GetApps", className = "android.webkit.WebView")
        val store = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Профиль", className = "android.widget.TextView"))
        )
        var polls = 0
        Mockito.`when`(service.rootInActiveWindow).thenAnswer {
            if (polls++ < 2) splash else store
        }

        assertTrue(
            "ожидание обязано пережить сплэш и подтвердиться по первому уровню",
            runner.awaitAppScreenReady(step("getapps"), getappsPath, timeoutMs = 2_000L)
        )
    }

    @Test
    fun `app entry timeout is honest and does not abort the step`() = runTest {
        // Узел создаётся до стаба сервиса: вложенный мок внутри thenReturn ломает Mockito.
        val splash = node(
            text = "Официальный магазин от Xiaomi",
            className = "android.webkit.WebView"
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(splash)

        assertFalse(
            "незагруженный экран — не ложный успех: бурение продолжится как раньше",
            runner.awaitAppScreenReady(step("getapps"), getappsPath, timeoutMs = 1_200L)
        )
    }

    @Test
    fun `resume does not wait for the app screen`() = runTest {
        assertTrue(
            "уровень > 0 означает подтверждённый экран — ожидание не нужно",
            runner.awaitAppScreenReady(step("getapps"), getappsPath, timeoutMs = 2_000L, startLevel = 1)
        )
        Mockito.verify(service, Mockito.never()).rootInActiveWindow
    }

    @Test
    fun `app entry matches the first level labels of the path`() = runTest {
        val store = node(
            className = "android.widget.FrameLayout",
            children = arrayOf(node(text = "Аккаунт", className = "android.widget.TextView"))
        )
        Mockito.`when`(service.rootInActiveWindow).thenReturn(store)

        assertTrue(
            "ожидание идёт по первому уровню маршрута, а не по хардкоду",
            runner.awaitAppScreenReady(
                step("getapps"),
                listOf(listOf("Аккаунт", "Account"), listOf("Настройки", "Settings")),
                timeoutMs = 2_000L
            )
        )
    }

    /**
     * Mi Браузер открывается контентной лентой без нижней навигации: подписи первого
     * уровня («Профиль») на ней нет, и шаг падал в `not_applicable` (прогон rmueihkd3).
     * Один guarded BACK возвращает главную страницу, по которой вход подтверждается.
     */
    @Test
    fun `content feed gets exactly one guarded BACK to the app home`() = runTest {
        val feed = node(
            text = "Опасные приложения найдены? Проверить",
            className = "android.webkit.WebView",
            packageName = "com.mi.globalbrowser"
        )
        val home = node(
            className = "android.widget.FrameLayout",
            packageName = "com.mi.globalbrowser",
            children = arrayOf(node(text = "Профиль", className = "android.widget.TextView"))
        )
        var polls = 0
        Mockito.`when`(service.rootInActiveWindow).thenAnswer { if (polls++ < 6) feed else home }

        val ready = runner.awaitAppScreenReady(
            step("browser_sys").copy(launchPackage = "com.mi.globalbrowser"),
            listOf(listOf("Профиль", "Profile"), listOf("Настройки", "Settings")),
            timeoutMs = 2_400L
        )

        assertTrue("возврат на главную обязан подтвердиться по первому уровню", ready)
        Mockito.verify(service, Mockito.times(1))
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /** Чужой экран на входе в приложение BACK не трогаем: уход из приложения недопустим. */
    @Test
    fun `foreign screen during app entry is never backed out`() = runTest {
        val foreign = node(text = "Рабочий стол", packageName = "com.miui.home")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(foreign)

        assertFalse(
            "экран другого приложения — не повод нажимать BACK",
            runner.awaitAppScreenReady(
                step("browser_sys").copy(launchPackage = "com.mi.globalbrowser"),
                listOf(listOf("Профиль", "Profile")),
                timeoutMs = 1_200L
            )
        )
        Mockito.verify(service, Mockito.never())
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}