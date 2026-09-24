package com.xiaohypercleaner.service

import com.xiaohypercleaner.data.AdaptiveCatalog
import com.xiaohypercleaner.data.SemanticCatalog
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
 * Гейт целостности оверлея (Аддендум A4) и канал отката.
 *
 * Регрессия прогонов rmuebpgnr / rmueihkd3: откат Simple Mode
 * (`AdbEnablerService.reverseSimpleToggles`) идёт без окна прогресса (окно показывает
 * MainActivity, а не раннер), а безусловный гейт фейлил каждый шаг отката
 * `overlay_lost` — `reverseSimpleToggles done: 0/11` и диалог «Откат не удался».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleRunnerOverlayGateTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner
    private val originalLocale = Locale.getDefault()

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

    @Test
    fun `progress overlay is required in the run and skipped in the rollback channel`() = runTest {
        assertFalse(
            "обычный прогон: без окна прогресса навигация запрещена",
            runner.awaitOverlayReadyOrPause()
        )

        runner.overlayGateRequired = false

        assertTrue(
            "канал отката идёт без окна прогресса — гейт его не блокирует",
            runner.awaitOverlayReadyOrPause()
        )
    }

    /**
     * Откат выполняется при видимой MainActivity: уход на рабочий стол уводил
     * приложение-инициатор из foreground, и MIUI блокировала запуск приложения шага
     * (`App not ready … fg=com.mi.android.globallauncher`, reverse `music_sys`).
     */
    @Test
    fun `app launch of the rollback channel stays on the launching activity`() {
        assertTrue(
            "обычный прогон: старт приложения шага идёт с рабочего стола",
            runner.needsHomeBeforeAppLaunch()
        )

        runner.overlayGateRequired = false

        assertFalse(
            "канал отката: инициатор остаётся в foreground — без ухода на рабочий стол",
            runner.needsHomeBeforeAppLaunch()
        )
    }
}