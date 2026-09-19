package com.xiaohypercleaner.data

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
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
}