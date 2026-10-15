package com.xiaohypercleaner.util

import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Копия собственной сборки для предшага установщика. Проверяется чистка: копия нужна только
 * на время установки, а оставшийся APK — мусор в кэше на десятки мегабайт.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SelfApkExporterTest {

    @Test
    fun `purge removes stale apk copies and keeps the directory usable`() {
        val context = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        val dir = SelfApkExporter.dir(context)
        dir.mkdirs()
        val stale = File(dir, "com.xiaohypercleaner-1.0-beta2.apk").apply { writeText("stale") }

        val removed = SelfApkExporter.purge(context)

        assertEquals("удалён ровно один файл", 1, removed)
        assertFalse("копии больше нет", stale.exists())
        assertTrue("каталог остаётся рабочим", dir.isDirectory)
    }

    @Test
    fun `purge on the missing directory is not an error`() {
        val context = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        SelfApkExporter.dir(context).deleteRecursively()

        // Старт приложения обязан пережить отсутствие каталога: чистка не бросает.
        assertEquals(0, SelfApkExporter.purge(context))
    }
}