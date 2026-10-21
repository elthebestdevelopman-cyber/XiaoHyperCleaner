package com.xiaohypercleaner.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Гейт предшага держит ВСЕ двери в шаги, а не только те две, через которые идёт обычный поток.
 *
 * Дверей шесть вызовов в пяти функциях: `advance()` (ветка «все разрешения выданы»),
 * `continueToSteps()`, `onBatteryDialogSkipped()`, две ветки `onBatteryReturn()` и
 * `reshowBatteryDialog()`. Каждая обязана упереться в единственную проверку внутри `goSteps()`
 * и выйти ДО `nextStep`, иначе шаги стартуют без решения пользователя.
 *
 * Наблюдаемые признаки выбраны так, чтобы не зависеть от истории публикаций состояния:
 * - текущее состояние — `controller.snapshot` (при закрытом гейте `goSteps()` не публикует
 *   ничего, и последний элемент колбэка к моменту ассерта устарел бы);
 * - различие фаз: закрытый гейт оставляет `PERMISSIONS` и не создаёт шаг, открытый — начинает
 *   прогон (`STEPS`/`DONE`). Флаг «разрешение оверлея потеряно» для этой проверки не годится:
 *   в Robolectric префлайт проходит и флаг не выставляется.
 *
 * Счётчик вызовов гейта сбрасывается непосредственно перед действием двери: подготовка ветки 5
 * (`onRestrictedScreenDone()` → `advance()`, `onBatteryDialogSkipped()`) сама консультирует гейт.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SimpleModeControllerPreStepGateTest {

    private lateinit var app: Application

    /** Решение гейта для текущего действия двери. */
    private var gateOpen: Boolean = false

    /** Сколько раз контроллер спросил гейт в текущем действии. */
    private var gateCalls: Int = 0

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        SimplePlan.reset()
        gateOpen = false
        gateCalls = 0
    }

    @After
    fun tearDown() {
        SimplePlan.reset()
    }

    private fun newController(): SimpleModeController =
        SimpleModeController(app, PermissionFlowManager(app), { gateCalls++; gateOpen }) { }

    /** Разрешения выданы: фаза доходит до батареи, а не до диалогов разрешений. */
    private fun grantPermissions(controller: SimpleModeController) {
        controller.start()
        controller.updatePermissionStatuses(accEnabled = true, overlayGranted = true)
    }

    /** Батарея «уже решена» — условие ветки 5 `advance()` и дверей отката по батарее. */
    private fun markBatteryDecided(controller: SimpleModeController) {
        controller.onBatteryDialogSkipped()
    }

    /** Ветка 5 `advance()` достижима: разрешения выданы, ограниченные настройки пройдены. */
    private fun reachAdvanceBranch(controller: SimpleModeController) {
        grantPermissions(controller)
        // Robolectric: приложение считается сайдлоадом, поэтому без этого шага advance()
        // останавливается на экране ограниченных настроек (ветка 2).
        controller.onRestrictedScreenDone()
        markBatteryDecided(controller)
    }

    /** Общая проверка «шаги не стартовали» для закрытого гейта. */
    private fun assertNoRun(controller: SimpleModeController) {
        assertEquals("гейт спрошен ровно один раз", 1, gateCalls)
        assertEquals(
            "фаза не переходит в STEPS",
            SimpleModePhase.PERMISSIONS,
            controller.snapshot.phase
        )
        assertNull("шаг не стартует", controller.snapshot.step)
    }

    /**
     * Общая проверка «открытый гейт действительно пускает в шаги».
     *
     * Префлайт в Robolectric ПРОХОДИТ (`ShadowAppOpsManager` по умолчанию разрешает
     * `SYSTEM_ALERT_WINDOW`), поэтому различие видно по фазе: закрытый гейт оставляет
     * `PERMISSIONS`, открытый — переводит в `STEPS` (или `DONE`, если собранный для тестового
     * окружения план пуст). Пустой план для этой проверки не помеха: старт прогона уже состоялся.
     */
    private fun assertRunStarted(controller: SimpleModeController) {
        assertEquals("гейт спрошен ровно один раз", 1, gateCalls)
        assertTrue(
            "открытый гейт пускает в шаги",
            controller.snapshot.phase == SimpleModePhase.STEPS ||
                controller.snapshot.phase == SimpleModePhase.DONE
        )
    }

    @Test
    fun `advance branch waits for the pre-step decision`() {
        val controller = newController()
        reachAdvanceBranch(controller)

        gateOpen = false
        gateCalls = 0
        controller.refresh()
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.refresh()
        assertRunStarted(controller)
    }

    @Test
    fun `continueToSteps waits for the pre-step decision`() {
        val controller = newController()
        grantPermissions(controller)

        gateOpen = false
        gateCalls = 0
        controller.continueToSteps()
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.continueToSteps()
        assertRunStarted(controller)
    }

    @Test
    fun `battery skipped waits for the pre-step decision`() {
        val controller = newController()
        grantPermissions(controller)

        gateOpen = false
        gateCalls = 0
        controller.onBatteryDialogSkipped()
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.onBatteryDialogSkipped()
        assertRunStarted(controller)
    }

    @Test
    fun `battery return with optimization granted waits for the pre-step decision`() {
        val controller = newController()
        grantPermissions(controller)

        gateOpen = false
        gateCalls = 0
        controller.onBatteryReturn(ignoring = true)
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.onBatteryReturn(ignoring = true)
        assertRunStarted(controller)
    }

    @Test
    fun `battery return without optimization waits for the pre-step decision`() {
        val controller = newController()
        grantPermissions(controller)
        // Ветка «диалог уже показывали» — это флаг, который ставит решение по батарее.
        markBatteryDecided(controller)

        gateOpen = false
        gateCalls = 0
        controller.onBatteryReturn(ignoring = false)
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.onBatteryReturn(ignoring = false)
        assertRunStarted(controller)
    }

    @Test
    fun `battery dialog reshow waits for the pre-step decision`() {
        val controller = newController()
        grantPermissions(controller)
        markBatteryDecided(controller)

        gateOpen = false
        gateCalls = 0
        controller.reshowBatteryDialog()
        assertNoRun(controller)

        gateOpen = true
        gateCalls = 0
        controller.reshowBatteryDialog()
        assertRunStarted(controller)
    }
}