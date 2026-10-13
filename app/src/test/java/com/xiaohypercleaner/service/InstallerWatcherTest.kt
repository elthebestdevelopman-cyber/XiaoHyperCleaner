package com.xiaohypercleaner.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ловец окна установки: решение по снимку окна. Наборы id взяты ИЗ ДАМПОВ
 * (diag-dumps/fresh/installer_settings_v130, installer_confirm_v130, reinstall_3/4),
 * а не придуманы: правило «действовать только в post-install состоянии» держится
 * ровно на различии экрана скана и post-install по id.
 */
class InstallerWatcherTest {

    private val packages = listOf(
        "com.miui.global.packageinstaller",
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    private val settingsIds = setOf(
        "action_bar_title", "up", "content", "list_container", "recycler_view",
        "title", "summary", "preference_icon", "widget_frame", "checkbox", "arrow_right"
    )

    /** Экран скана (reinstall_3): установка идёт, «ОТМЕНА» в дереве — отмена возможна. */
    private val scanIds = setOf(
        "btn_settings", "app_info_layout", "app_icon", "app_name", "install_confirm_question",
        "container_layout", "app_scan_layout", "progress_bar", "progress_text",
        "button_layout", "app_deal_layout", "cancel_button"
    )

    /** Post-install (reinstall_4): приложение установлено, отменять нечего. */
    private val postInstallIds = setOf(
        "btn_settings", "app_info_layout", "app_icon", "app_name", "install_confirm_icon",
        "install_confirm_question", "container_layout", "no_virus_layout", "iv_arrow",
        "button_layout", "app_deal_layout", "ok_button", "cancel_button"
    )

    /** Диалог подтверждения установки (reinstall_1). */
    private val confirmIds = setOf(
        "content", "parentPanel", "topPanel", "title_template", "icon", "alertTitle",
        "customPanel", "custom", "install_confirm_question_update", "buttonPanel",
        "button2", "button1"
    )

    private fun observation(
        packageName: String = "com.miui.global.packageinstaller",
        viewIds: Set<String> = emptySet(),
        settingsScreen: Boolean = false,
        toggleChecked: Boolean? = null
    ) = InstallerObservation(
        packageName = packageName,
        viewIds = viewIds,
        screenText = "screen text",
        settingsScreen = settingsScreen,
        toggleChecked = toggleChecked,
        installerPackages = packages
    )

    @Test
    fun `settings screen with the toggle on turns it off`() {
        val decision = InstallerWatcherLogic.decide(
            observation(viewIds = settingsIds, settingsScreen = true, toggleChecked = true),
            cycleClosed = false
        )

        assertEquals(InstallerDecision.TurnOff, decision)
    }

    @Test
    fun `settings screen with the toggle already off stays silent`() {
        val decision = InstallerWatcherLogic.decide(
            observation(viewIds = settingsIds, settingsScreen = true, toggleChecked = false),
            cycleClosed = false
        )

        assertEquals(InstallerDecision.Ignore(InstallerWatcherLogic.REASON_ALREADY_OFF), decision)
    }

    @Test
    fun `scan window is never acted on while the install can still be cancelled`() {
        val decision = InstallerWatcherLogic.decide(
            observation(viewIds = scanIds, settingsScreen = false),
            cycleClosed = false
        )

        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_SCAN_IN_PROGRESS),
            decision
        )
    }

    @Test
    fun `install confirm dialog is never acted on`() {
        val decision = InstallerWatcherLogic.decide(
            observation(
                packageName = "com.google.android.packageinstaller",
                viewIds = confirmIds
            ),
            cycleClosed = false
        )

        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_CONFIRM_WINDOW),
            decision
        )
    }

    @Test
    fun `post install window opens installer settings`() {
        val decision = InstallerWatcherLogic.decide(
            observation(viewIds = postInstallIds, settingsScreen = false),
            cycleClosed = false
        )

        assertEquals(InstallerDecision.OpenSettings, decision)
    }

    @Test
    fun `foreign window is left alone`() {
        val decision = InstallerWatcherLogic.decide(
            observation(packageName = "com.android.vending", viewIds = setOf("content")),
            cycleClosed = false
        )

        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_FOREIGN_PACKAGE),
            decision
        )
    }

    @Test
    fun `installer window without the settings gear does nothing`() {
        val decision = InstallerWatcherLogic.decide(
            observation(viewIds = setOf("content", "grabLayout")),
            cycleClosed = false
        )

        assertEquals(InstallerDecision.Ignore(InstallerWatcherLogic.REASON_NO_DOOR), decision)
    }

    @Test
    fun `gate silences the watcher while a phase is running`() {
        assertEquals(
            InstallerWatcherLogic.REASON_PHASE_RUNNING,
            InstallerWatcherLogic.gateReason(enabled = true, phaseRunning = true)
        )
        assertEquals(
            InstallerWatcherLogic.REASON_DISABLED,
            InstallerWatcherLogic.gateReason(enabled = false, phaseRunning = false)
        )
        assertNull(InstallerWatcherLogic.gateReason(enabled = true, phaseRunning = false))
    }

    @Test
    fun `cycle closes on a confirmed already-off toggle`() {
        val observation = observation(
            viewIds = settingsIds,
            settingsScreen = true,
            toggleChecked = false
        )

        // Подтверждённое «уже выключено» — работа сделана, цикл закрывает вызывающий.
        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_ALREADY_OFF),
            InstallerWatcherLogic.decide(observation, cycleClosed = false)
        )
    }

    @Test
    fun `missing switch does not close the cycle`() {
        val observation = observation(viewIds = settingsIds, settingsScreen = true)

        // Тумблера нет: состояние НЕ подтверждено, цикл закрывать нельзя.
        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_SWITCH_NOT_FOUND),
            InstallerWatcherLogic.decide(observation, cycleClosed = false)
        )
    }

    @Test
    fun `closed cycle keeps the watcher away even from an enabled toggle`() {
        val observation = observation(
            viewIds = settingsIds,
            settingsScreen = true,
            toggleChecked = true
        )

        // Пользователь включил галочку сам — до конца цикла не воюем.
        assertEquals(
            InstallerDecision.Ignore(InstallerWatcherLogic.REASON_CYCLE_DONE),
            InstallerWatcherLogic.decide(observation, cycleClosed = true)
        )
    }

    @Test
    fun `cycle fingerprint invalidates the flag after a system update`() {
        val now = buildCycleFingerprint("V130.0.1.0", "V130")

        assertEquals("свой отпечаток — цикл закрыт", true, cycleClosedFor(now, now))
        assertEquals("пусто — цикл открыт", false, cycleClosedFor(null, now))
        assertEquals(
            "обновление системы меняет отпечаток — цикл открывается снова",
            false,
            cycleClosedFor(now, buildCycleFingerprint("V130.0.2.0", "V130"))
        )
        assertEquals(
            "новая версия MIUI — тоже новый отпечаток",
            false,
            cycleClosedFor(now, buildCycleFingerprint("V130.0.1.0", "V140"))
        )
    }
}