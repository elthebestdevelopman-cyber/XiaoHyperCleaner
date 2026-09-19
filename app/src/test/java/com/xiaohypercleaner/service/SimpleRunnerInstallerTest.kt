package com.xiaohypercleaner.service

import com.xiaohypercleaner.data.SemanticCatalog
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

/**
 * Шаг «Проверка приложений при установке»: маршрут через активность настроек
 * установщика и жёсткий запрет на подтверждение установки.
 *
 * Установку APK приложение не запускает никогда: активности установки не попадают
 * в кандидаты, а ни один текст-подтверждение установки не тапается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleRunnerInstallerTest {

    private lateinit var service: AdbEnablerService
    private lateinit var runner: SimpleRunner

    @Before
    fun setUp() {
        SemanticCatalog.resetForTest()
        SemanticCatalog.ensureLoaded(RuntimeEnvironment.getApplication())
        service = Mockito.mock(AdbEnablerService::class.java)
        runner = SimpleRunner(service)
    }

    @After
    fun tearDown() {
        SemanticCatalog.resetForTest()
    }

    @Test
    fun `settings activity is a candidate, install activities are not`() {
        assertTrue(runner.isInstallerSettingsActivity("com.miui.packageinstaller.SettingsActivity"))
        assertTrue(runner.isInstallerSettingsActivity("com.android.packageinstaller.settings.PreferenceActivity"))
        assertTrue(runner.isInstallerSettingsActivity("com.miui.packageinstaller.RecommendSettingsActivity"))

        assertFalse(
            "активность установки не должна попадать в кандидаты",
            runner.isInstallerSettingsActivity("com.android.packageinstaller.PackageInstallerActivity")
        )
        assertFalse(runner.isInstallerSettingsActivity("com.android.packageinstaller.InstallAppProgress"))
        assertFalse(runner.isInstallerSettingsActivity("com.miui.packageinstaller.InstallStart"))
    }

    @Test
    fun `install confirm texts are recognised as forbidden`() {
        assertTrue(runner.isInstallConfirmText("Установить"))
        assertTrue(runner.isInstallConfirmText("Install"))
        assertTrue(runner.isInstallConfirmText("安装"))

        assertFalse(runner.isInstallConfirmText("Получать рекомендации"))
        assertFalse(runner.isInstallConfirmText("Receive recommendations"))
    }

    @Test
    fun `catalog switch labels never look like an install confirmation`() {
        val labels = SemanticCatalog.itemTexts("installer_recommendations")
        assertTrue("подписи тумблера есть", labels.isNotEmpty())
        assertTrue(
            "ни одна подпись из каталога не является подтверждением установки",
            labels.none { runner.isInstallConfirmText(it) }
        )
    }
}

