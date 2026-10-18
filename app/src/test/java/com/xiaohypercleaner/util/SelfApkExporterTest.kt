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
 *
 * Число удалённых файлов здесь НЕ проверяется: старт приложения чистит копии фоном
 * (`XiaoHyperApp.onCreate` → `initScope`), и счётчик гоняется с этой чисткой — на загруженной
 * машине тест падал «ожидалось 1, получено 0». Контракт «старой копии после чистки нет,
 * каталог остаётся рабочим» от гонки не зависит и проверяется полностью.
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

        SelfApkExporter.purge(context)

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

    @Test
    fun `stale purge keeps the copy when no install happened after the pre-step`() {
        // Пользователь вернулся из установщика, ничего не поставив: копия нужна предшагу для
        // продолжения, а приложение старше копии — установки не было.
        val context = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        val dir = SelfApkExporter.dir(context)
        dir.mkdirs()
        val copy = File(dir, "com.xiaohypercleaner-1.0.apk").apply { writeText("copy") }
        val openedAt = System.currentTimeMillis()
        copy.setLastModified(openedAt)

        val removed = SelfApkExporter.purgeStale(context, lastUpdateTimeMs = openedAt - 60_000L)

        assertEquals("установки не было — чистить нечего", 0, removed)
        assertTrue("копия остаётся для продолжения предшага", copy.exists())
    }

    @Test
    fun `stale purge removes the copy when the install happened after the pre-step`() {
        // Установка состоялась: приложение новее копии — копия стала мусором в кэше.
        val context = ApplicationProvider.getApplicationContext<XiaoHyperApp>()
        val dir = SelfApkExporter.dir(context)
        dir.mkdirs()
        val copy = File(dir, "com.xiaohypercleaner-1.0.apk").apply { writeText("copy") }
        copy.setLastModified(System.currentTimeMillis() - 60_000L)

        val removed = SelfApkExporter.purgeStale(
            context,
            lastUpdateTimeMs = System.currentTimeMillis()
        )

        assertEquals("копия установленной сборки удалена", 1, removed)
        assertFalse("копии больше нет", copy.exists())
    }

    @Test
    fun `install gate compares lastUpdateTime with the moment the installer was opened`() {
        val openedAt = 1_800_000_000_000L

        assertTrue(
            "установка ровно в момент открытия (гранулярность ФС) — установка была",
            SelfApkExporter.installHappenedAfterOpened(openedAt, openedAt)
        )
        assertTrue(
            "lastUpdateTime на 900 мс раньше открытия — гранулярность 1 с",
            SelfApkExporter.installHappenedAfterOpened(openedAt - 900L, openedAt)
        )
        assertFalse(
            "обновление на 2 с раньше открытия — установки после предшага не было",
            SelfApkExporter.installHappenedAfterOpened(openedAt - 2_000L, openedAt)
        )
        assertFalse(
            "штатный старт (openedAt=0) гейтом не проверяется",
            SelfApkExporter.installHappenedAfterOpened(Long.MAX_VALUE, 0L)
        )
    }
}