package com.xiaohypercleaner.data

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageInfo
import android.provider.Settings
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Read-only краулер экранов: лексикон кандидатов, подпись строки тумблера и
 * отчёт (JSON round-trip). Тумблеры не переключаются — только читается состояние.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ScreenCrawlerTest {

    @Suppress("DEPRECATION")
    private fun node(
        className: String? = null,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        checked: Boolean = false,
        checkable: Boolean = false,
        vararg children: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {
        val n = Mockito.mock(AccessibilityNodeInfo::class.java)
        if (className != null) Mockito.`when`(n.className).thenReturn(className)
        if (text != null) Mockito.`when`(n.text).thenReturn(text)
        if (desc != null) Mockito.`when`(n.contentDescription).thenReturn(desc)
        Mockito.`when`(n.isClickable).thenReturn(clickable)
        Mockito.`when`(n.isChecked).thenReturn(checked)
        Mockito.`when`(n.isCheckable).thenReturn(checkable)
        Mockito.`when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { i, c -> Mockito.`when`(n.getChild(i)).thenReturn(c) }
        children.forEach { Mockito.`when`(it.parent).thenReturn(n) }
        return n
    }

    @Test
    fun `lexicon matches recommendation rows in several locales`() {
        assertNotNull("ru", ScreenCrawler.classify("Получать рекомендации"))
        assertNotNull("ru", ScreenCrawler.classify("Рекомендуемое сегодня"))
        assertNotNull("en", ScreenCrawler.classify("Show suggestions"))
        assertNotNull("en", ScreenCrawler.classify("Personalized recommendations"))
        assertNotNull("zh", ScreenCrawler.classify("接收推荐"))
    }

    @Test
    fun `more specific lexicon key wins over the generic one`() {
        // Порядок лексикона не должен решать исход: общий «рекомендации» стоит раньше специфичного
        // «рекомендации приложений», и при первом совпадении специфичный ключ молча не срабатывал —
        // отчёт показывал его в unmatchedLexicon, хотя строка на экране была.
        assertEquals(
            "специфичный ключ важнее общего",
            "рекомендации приложений",
            ScreenCrawler.classify("Рекомендации приложений")
        )
        assertEquals("общий ключ работает, когда специфичного нет", "рекомендации", ScreenCrawler.classify("Рекомендации"))
        assertEquals(
            "самый длинный совпавший ключ выигрывает и на английском",
            "personalized recommendations",
            ScreenCrawler.classify("Personalized recommendations")
        )
    }

    @Test
    fun `equal length keys keep the lexicon priority and the choice is deterministic`() {
        // Порядок лексикона — приоритет локали: при РАВНОЙ длине ключей побеждает более ранний
        // (maxByOrNull отдаёт первый максимум). Лексикон — упорядоченный List, не Map, поэтому
        // порядок итерации гарантирован и повторный вызов даёт тот же ключ.
        val lexicon = listOf("aaa", "bbb")
        val row = "строка со словами aaa и bbb"
        assertEquals("первый из равных по длине", "aaa", ScreenCrawler.classify(row, lexicon))
        assertEquals("выбор детерминирован", "aaa", ScreenCrawler.classify(row, lexicon))
        assertEquals(
            "порядок — единственный арбитр при равной длине",
            "bbb",
            ScreenCrawler.classify(row, lexicon.reversed())
        )
    }

    @Test
    fun `unrelated rows are not candidates`() {
        assertNull(ScreenCrawler.classify("Показывать пароли"))
        assertNull(ScreenCrawler.classify("О телефоне"))
        assertNull(ScreenCrawler.classify(""))
        assertNull(ScreenCrawler.classify(null))
    }

    @Test
    fun `row label comes from the own text or the clickable row`() {
        val switchWithText =
            node(className = "android.widget.Switch", text = "Recommendations", checkable = true)
        assertEquals("Recommendations", ScreenCrawler.rowLabel(switchWithText))

        val label = node(text = "Получать рекомендации")
        val switchWithoutText = node(className = "android.widget.Switch", checkable = true)
        node(
            clickable = true,
            children = arrayOf(label, switchWithoutText)
        )
        assertEquals("Получать рекомендации", ScreenCrawler.rowLabel(switchWithoutText))
    }

    @Test
    fun `snapshot collects only recommendation switches and never toggles`() {
        val recLabel = node(text = "Получать рекомендации")
        val recSwitch = node(
            className = "android.widget.Switch", checkable = true, checked = true
        )
        node(clickable = true, children = arrayOf(recLabel, recSwitch))

        val otherLabel = node(text = "Показывать пароли")
        val otherSwitch = node(
            className = "android.widget.Switch", checkable = true, checked = false
        )
        node(clickable = true, children = arrayOf(otherLabel, otherSwitch))

        val root = node(
            className = "android.widget.FrameLayout",
            text = "Конфиденциальность",
            children = arrayOf(recLabel.parent, otherLabel.parent)
        )

        val snapshot = ScreenCrawler.snapshotScreen(root, "com.miui.securitycenter")

        assertEquals("оба тумблера посчитаны", 2, snapshot.switchCount)
        assertEquals("кандидат только рекомендательный", 1, snapshot.candidates.size)
        val candidate = snapshot.candidates.first()
        assertEquals("Получать рекомендации", candidate.rowText)
        assertTrue("состояние прочитано, а не переключено", candidate.checked)
    }

    @Test
    fun `report json survives round trip`() {
        val candidate = ScreenCrawler.Candidate(
            packageName = "com.miui.securitycenter",
            screenSignature = "pkg|title|texts",
            rowText = "Получать рекомендации",
            matched = "получать рекомендации",
            checked = true,
            bounds = "[10,20,30,40]"
        )
        val report = ScreenCrawler.CrawlReport(
            timestamp = 123L,
            osFamily = "MIUI",
            osUi = "13",
            incremental = "V13.0.5.0.SJURUXM",
            screens = listOf(
                ScreenCrawler.ScreenSnapshot(
                    packageName = "com.miui.securitycenter",
                    signature = "pkg|title|texts",
                    title = "Конфиденциальность",
                    switchCount = 2,
                    candidates = listOf(candidate)
                )
            )
        )

        val restored = ScreenCrawler.CrawlReport.fromJson(report.toJson())

        assertEquals(report, restored)
        assertEquals(1, restored?.candidateCount)
    }

    @Test
    fun `report with unknown schema is rejected`() {
        assertNull(ScreenCrawler.CrawlReport.fromJson("""{"schema":99,"screens":[]}"""))
        assertNull(ScreenCrawler.CrawlReport.fromJson("not a json"))
    }

    @Test
    fun `crawl is skipped while a phase is running`() = runBlocking {
        val service = Mockito.mock(AccessibilityService::class.java)
        val entries = listOf(
            ScreenCrawler.CrawlEntry(
                packageName = "com.android.settings",
                label = "settings",
                intent = Intent(Settings.ACTION_SETTINGS)
            )
        )

        val result = ScreenCrawler.crawl(service, entries, isPhaseRunning = { true })

        assertTrue("скан обязан уступить фазе", result is ScreenCrawler.CrawlResult.Skipped)
        assertEquals(
            ScreenCrawler.REASON_PHASE_RUNNING,
            (result as ScreenCrawler.CrawlResult.Skipped).reason
        )
        Mockito.verify(service, Mockito.never()).startActivity(Mockito.any())
    }

    @Test
    fun `report keeps lexicon entries that never matched`() {
        val report = ScreenCrawler.CrawlReport(
            timestamp = 1L,
            osFamily = "MIUI",
            osUi = "13",
            incremental = "inc",
            screens = emptyList(),
            unmatchedLexicon = listOf("получать рекомендации", "recommendations")
        )

        val restored = ScreenCrawler.CrawlReport.fromJson(report.toJson())

        assertEquals(
            "перечень ни разу не совпавших строк обязан пережить round-trip: иначе риск «молча не сработает» теряется",
            listOf("получать рекомендации", "recommendations"),
            restored?.unmatchedLexicon
        )
    }

    @Test
    fun `installer entry survives when only the legacy package list knows the package`() {
        // Путь краулера — единственное место, где legacy-список шага читается БЕЗ
        // семантической оси (`AdaptiveCatalog.packagesForStep(defaults=requiredPackages)`).
        // Пакет установщика с экраном настроек — com.miui.global.packageinstaller.
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.miui.global.packageinstaller" }
        )

        val entries = runBlocking { ScreenCrawler.entryPoints(context) }
        val installer = entries.firstOrNull { it.label == "installer_recommendations" }

        assertNotNull(
            "вход установщика обязан остаться: если пакета нет в legacy-списке, " +
                "entryPoints молча теряет точку обхода",
            installer
        )
        assertEquals("com.miui.global.packageinstaller", installer!!.packageName)
    }
}
