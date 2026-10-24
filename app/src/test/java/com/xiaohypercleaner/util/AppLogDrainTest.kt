package com.xiaohypercleaner.util

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Барьер записи лога: выгрузка для шаринга обязана содержать весь хвост очереди.
 *
 * Регрессия `e3e24c2`: запись ушла на свой поток, а `UiActions.shareLog` копировал файл
 * сразу — строки «в полёте» (включая строку самого запроса шаринга) в копию не попадали,
 * и разбор провалившегося шага оставался без последних строк фазы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppLogDrainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AppLog.writeDelayMsForTest = 0L
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith(LOG_NAME) }
            ?.forEach { it.delete() }
        AppLog.init(context)
    }

    @After
    fun tearDown() {
        AppLog.writeDelayMsForTest = 0L
        AppLog.clearAllLogs()
        AppLog.close()
    }

    @Test
    fun `flushPending drains the queue before the log is shared`() {
        repeat(200) { AppLog.i(TAG, "line-$it") }

        assertTrue("очередь дренируется без таймаута", AppLog.flushPending(2000))

        val text = AppLog.getLogFile()?.readText(Charsets.UTF_8).orEmpty()
        assertTrue("последняя строка попала в файл", text.contains("line-199"))
    }

    @Test
    fun `flushPending reports timeout when the writer is busy`() {
        // Загруженный писатель: барьер не успевает за 10 мс и обязан сказать об этом,
        // не блокируя шаринг; после разгрузки очередь дренируется.
        AppLog.writeDelayMsForTest = 300L
        repeat(3) { AppLog.i(TAG, "slow-$it") }

        assertFalse("таймаут барьера виден вызывающему", AppLog.flushPending(10))

        AppLog.writeDelayMsForTest = 0L
        assertTrue("после разгрузки очередь дренируется", AppLog.flushPending(5000))

        val text = AppLog.getLogFile()?.readText(Charsets.UTF_8).orEmpty()
        assertTrue("хвост записан после ожидания", text.contains("slow-2"))
    }

    private companion object {
        const val TAG = "drain"
        const val LOG_NAME = "xhc.log"
    }
}