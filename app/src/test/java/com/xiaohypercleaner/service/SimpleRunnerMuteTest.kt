package com.xiaohypercleaner.service

import android.media.AudioManager
import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.data.SimpleSteps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * D2 (прогон rmuojptft): громкость медиа на шаге Mi Video (`muteMediaOnLaunch`).
 *
 * Дефект был не в чтении значения, а в порядке восстановления: `restoreStepMute()`
 * обнулял снапшот свежего устройства (`originalVolume = -1`), ветка возврата
 * fresh-device становилась мёртвой, и громкость, поднятая владельцем до прогона,
 * могла остаться заглушённой (`media volume restored to 0` без строки mute).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerMuteTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private lateinit var audio: AudioManager
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        audio = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        service = Mockito.mock(AdbEnablerService::class.java)
        Mockito.`when`(service.getSystemService(AudioManager::class.java)).thenReturn(audio)
        runner = SimpleRunner(service)
    }

    @After
    fun tearDown() {
        AdaptiveCatalog.resetForTest()
        SemanticCatalog.resetForTest()
        Locale.setDefault(originalLocale)
    }

    private fun mivideoStep() = SimpleSteps.ALL.first { it.id == "mivideo" }

    private fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    @Test
    fun `step mute saves the pre-mute volume and restores it`() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)

        runner.muteForStepIfNeeded(mivideoStep())
        assertEquals("шаг обязан заглушить медиа", 0, volume())

        runner.restoreStepMute()
        assertEquals(
            "возврат идёт к значению ДО глушения, а не к пост-mute",
            7,
            volume()
        )
    }

    @Test
    fun `fresh device volume survives a step mute`() {
        // Регрессия rmuojptft: шаговая ветка обнуляла снапшот fresh-device, возврат
        // громкости владельца после прогона не срабатывал.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        runner.markFreshDevice()
        assertEquals("свежее устройство глушит медиа", 0, volume())

        runner.muteForStepIfNeeded(mivideoStep())
        runner.cleanupFreshDevice()

        assertEquals(
            "громкость владельца возвращается после выхода шага",
            5,
            volume()
        )
    }

    @Test
    fun `finally branch restores the volume when the step mute is active`() {
        // finally прогона (cancel/timeout/исключение) зовёт cleanupFreshDevice: даже без
        // fresh-device шаговая громкость обязана вернуться.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        runner.muteForStepIfNeeded(mivideoStep())

        runner.cleanupFreshDevice()

        assertEquals(3, volume())
    }

    @Test
    fun `already silent media stays silent and is restored as zero`() {
        // Прогон rmuojptft: медиа было 0 ещё до шага — глушить нечего, но контракт
        // «save -> mute -> restore» обязан сохранять 0 и логировать факт.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        runner.muteForStepIfNeeded(mivideoStep())
        assertEquals(0, volume())

        runner.cleanupFreshDevice()

        assertEquals(0, volume())
    }
}