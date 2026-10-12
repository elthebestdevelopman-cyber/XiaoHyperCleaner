package com.xiaohypercleaner.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Пак shareLog: в zip уходят ТОЛЬКО снимки упавшего экрана.
 *
 * `crawl_report_*.json` несёт сырые подписи чужих окон и обязан оставаться локальным
 * (.clinerules, «Краул окон»). Правило закрыто allowlist, а не «всем, кроме crawl_report_*»:
 * новый тип файла в diag-каталоге по умолчанию остаётся локальным — это и фиксирует тест.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DiagSharePackTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val diagDir: File = requireNotNull(context.getExternalFilesDir("diag"))

    @After
    fun cleanUp() {
        // packageDiagnosticsZip оставляет последние три архива, поэтому каждый кейс чистит за
        // собой: иначе соседний тест прочитает чужой zip из cacheDir.
        File(context.cacheDir, "diag").deleteRecursively()
        diagDir.deleteRecursively()
    }

    private fun writeDiag(name: String) {
        diagDir.mkdirs()
        File(diagDir, name).writeText("x")
    }

    private fun zipEntries(zip: File): List<String> =
        ZipFile(zip).use { zf -> zf.entries().toList().map { it.name } }

    @Test
    fun `only screen dumps are packed, crawl report stays local`() {
        writeDiag("diagnostic_snapshot_carousel_1.json")
        writeDiag("screenshot_carousel_1.png")
        writeDiag("crawl_report_1.json")
        writeDiag("prefs_dump.pb")
        writeDiag("notes.txt")

        val zip = packageDiagnosticsZip(context)

        assertNotNull("zip обязан собраться из разрешённых файлов", zip)
        val entries = zipEntries(zip!!)
        assertEquals(
            "в zip ровно два разрешённых файла (иначе allowlist расширился сам)",
            listOf("diagnostic_snapshot_carousel_1.json", "screenshot_carousel_1.png"),
            entries.sorted()
        )
        assertTrue(
            "crawl_report_*.json не имеет права уезжать в пак: там сырые подписи чужих окон",
            entries.none { it.startsWith("crawl_report_") }
        )
    }

    @Test
    fun `no zip is created when nothing is shareable`() {
        writeDiag("crawl_report_1.json")

        assertNull("без разрешённых файлов zip не создаём", packageDiagnosticsZip(context))
    }

    @Test
    fun `allowlist requires both prefix and extension`() {
        assertTrue(isShareableDiagFile("diagnostic_snapshot_carousel_1.json"))
        assertTrue(isShareableDiagFile("screenshot_carousel_1.png"))
        assertFalse("чужое расширение при своём префиксе", isShareableDiagFile("diagnostic_snapshot_x.png"))
        assertFalse("префикс screenshot и чужое расширение", isShareableDiagFile("screenshot_x.json"))
        assertFalse("краул остаётся локальным", isShareableDiagFile("crawl_report_1.json"))
        assertFalse(isShareableDiagFile("prefs_dump.pb"))
        assertFalse(isShareableDiagFile(""))
    }
}