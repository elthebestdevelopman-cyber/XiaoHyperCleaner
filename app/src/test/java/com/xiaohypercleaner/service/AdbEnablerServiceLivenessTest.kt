package com.xiaohypercleaner.service

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Живость сервиса доступности: `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` отвечает
 * «включён», а сам сервис может быть НЕ подключён (после смерти процесса система его не
 * перепривязывает). Префлайт обязан спрашивать живость: прогон rmvikvzqm — 35 шагов
 * `PERCEPTION windows=0`, `result shown: 0/26` и откат, который некому выполнить.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdbEnablerServiceLivenessTest {

    @Test
    fun `service without a connection is not live`() {
        assertFalse(
            "instance не подключён — сервис не считается живым",
            AdbEnablerService.isLive()
        )
    }
}