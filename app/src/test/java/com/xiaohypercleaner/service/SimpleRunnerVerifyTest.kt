package com.xiaohypercleaner.service

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
 * Честность вердикта тумблера: «узла нет» — успех только на ЧИСТОМ целевом экране.
 *
 * Прогон rmuslthv7: диалог/прогресс «Отзыв разрешения…» (msa) и диалог «Выключить
 * карусель экрана блокировки?» перекрывали экран, тумблер исчезал из дерева, и шаг
 * объявлял `toggled` с `checked_after=null`, оставляя диалог открытым — он уносил
 * следующий шаг (sys_recommendations → timeout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerVerifyTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        // Вариант каталога выбирается как в прогоне (MIUI 13) — маркеры экрана
        // приходят от варианта, а не от шага.
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
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        return n
    }

    private fun msaStep() = SimpleSteps.Step(
        id = "msa",
        titleRu = "MSA",
        titleEn = "MSA",
        descRu = "msa",
        descEn = "msa",
        intents = emptyList(),
        searchTexts = listOf("msa"),
        manualHintRu = "",
        manualHintEn = "",
        confirmTexts = listOf("Отозвать", "ОК"),
        confirmWaitMs = 10_000L
    )

    @Test
    fun `vanished row is not success while the step confirm dialog is visible`() = runTest {
        // Экран: целевой экран виден, но поверх него диалог/прогресс «Отзыв разрешения…».
        val marker = node(text = "Доступ к личным данным")
        val dialog = node(text = "Отзыв разрешения…")
        val root = node(children = arrayOf(marker, dialog))
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        assertFalse(
            "перекрытый диалогом экран — это не применённая настройка",
            runner.verifySwitchState(msaStep(), listOf("msa"))
        )
    }

    @Test
    fun `vanished row is not success when the target markers are absent`() = runTest {
        val root = node(text = "Что-то другое")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val carousel = SimpleSteps.ALL.first { it.id == "carousel" }

        assertFalse(
            "без маркеров целевого экрана «узел исчез» читается на чужом экране",
            runner.verifySwitchState(carousel, listOf("Карусель экрана блокировки"))
        )
    }

    @Test
    fun `vanished row on the clean target screen is success`() = runTest {
        val root = node(text = "Карусель обоев")
        Mockito.`when`(service.rootInActiveWindow).thenReturn(root)

        val carousel = SimpleSteps.ALL.first { it.id == "carousel" }

        assertTrue(
            "строка ушла вместе с настройкой на своём экране — успех",
            runner.verifySwitchState(carousel, listOf("Карусель экрана блокировки"))
        )
    }
}