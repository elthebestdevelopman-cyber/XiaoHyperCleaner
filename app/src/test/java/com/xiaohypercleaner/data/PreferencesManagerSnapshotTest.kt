package com.xiaohypercleaner.data

import androidx.test.core.app.ApplicationProvider
import com.xiaohypercleaner.XiaoHyperApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Правила снапшота отката на уровне хранилища (D2, прогон rmvgvhbzu): приоритет сохранённого
 * значения над зеркалом, объединение трёх источников списка отката, изоляция доп. целей и
 * порядок очистки зеркал.
 *
 * Экземпляр берётся у приложения: делегат `preferencesDataStore` один на файл, второй экземпляр
 * на тот же файл создать нельзя. Состояние чистится в `@Before`/`@After` — DataStore живёт
 * в пределах JVM-форка тестов.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreferencesManagerSnapshotTest {

    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() = runBlocking {
        prefs = ApplicationProvider.getApplicationContext<XiaoHyperApp>().preferencesManager
        clearState()
    }

    @After
    fun tearDown() = runBlocking {
        clearState()
    }

    private suspend fun clearState() {
        prefs.clear()
        prefs.clearSimpleToggledSteps()
    }

    @Test
    fun `stored checked_before wins over the mirror`() = runBlocking {
        // Снапшот первого прогона: тумблер был включён. Второй прогон видит его выключенным,
        // но исходное значение перезаписываться не должно (D2 п.2). Фоновая запись зеркала
        // значение не меняет: ключ в снапшоте уже есть, поэтому проверка детерминирована.
        prefs.persistSimpleToggleState("msa", checkedBefore = true, markToggled = true)
        prefs.recordSimpleToggleState("msa", checkedBefore = false)

        assertEquals(
            "чтение отдаёт приоритет снапшоту, а не зеркалу",
            true,
            prefs.getSimpleToggleStates()["msa"]
        )
        assertEquals("зеркало при этом помнит значение второго прогона", false, prefs.toggleStateMirrorSnapshot["msa"])
    }

    @Test
    fun `reverse list unions snapshot, legacy key and mirror`() = runBlocking {
        // Три источника списка отката: новое множество снапшота, легаси-ключ (пишет
        // MainViewModel) и in-memory зеркало (шаг не ждёт фоновой записи).
        prefs.persistSimpleToggleState("msa", checkedBefore = true, markToggled = true)
        prefs.addSimpleToggledStep("carousel")
        prefs.recordSimpleToggleState("downloads", checkedBefore = false, markToggled = true)

        val steps = prefs.getSimpleToggledSteps()

        assertTrue("снапшот: msa в списке отката", "msa" in steps)
        assertTrue("легаси-ключ: carousel в списке отката", "carousel" in steps)
        assertTrue("зеркало: downloads в списке отката", "downloads" in steps)
    }

    @Test
    fun `extra target key stays out of the reverse list`() = runBlocking {
        // Доп. цель пишет checked_before без признака «шаг тумблерили»: значение читается
        // (возврат идёт внутри своего шага через ReverseExtras), но в список шагов отката
        // не попадает — иначе reverseSimpleToggles обработал бы её как отдельный шаг.
        val extraKey = "carousel#extra:Реклама на экране блокировки"
        prefs.persistSimpleToggleState(extraKey, checkedBefore = true, markToggled = false)
        prefs.addSimpleToggledStep("carousel")

        val states = prefs.getSimpleToggleStates()
        val steps = prefs.getSimpleToggledSteps()

        assertTrue("значение доп. цели сохранено", extraKey in states)
        assertFalse("доп. цель не шаг отката", extraKey in steps)
        assertTrue("родительский шаг в списке отката", "carousel" in steps)
    }

    @Test
    fun `mirrors survive a failed write and are cleared after a successful one`() = runBlocking {
        // Инвариант целостности: при сбое DataStore зеркало не чистится, иначе следующая
        // попытка отката теряет исходные значения.
        prefs.recordSimpleToggleState("msa", checkedBefore = true, markToggled = true)
        assertTrue("зеркало заполнено", "msa" in prefs.toggleStateMirrorSnapshot)
        assertTrue("признак тумблера в зеркале", "msa" in prefs.toggledMirrorSnapshot)

        val failed = prefs.clearToggledState { false }
        assertFalse("сбой записи не считается снятием", failed)
        assertTrue("при сбое зеркало сохраняет значения", "msa" in prefs.toggleStateMirrorSnapshot)
        assertTrue("при сбое признак тумблера остаётся", "msa" in prefs.toggledMirrorSnapshot)

        val ok = prefs.clearToggledState { true }
        assertTrue("успешная запись снимает состояние", ok)
        assertTrue("зеркало очищено после успеха", prefs.toggleStateMirrorSnapshot.isEmpty())
        assertTrue("признак тумблера снят", prefs.toggledMirrorSnapshot.isEmpty())
    }
}