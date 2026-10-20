package com.xiaohypercleaner.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Паритет TRANSLATABLE-ключей строк между семью `values*`-каталогами.
 *
 * Правило «Строки UI — только strings.xml во всех 7 локалях» проверяется здесь, а не глазами:
 * ключи с `translatable="false"` вне паритета — это символы («←», «⋮», «👆 ») и ссылки, они не
 * переводятся. Тест падает на непустой симметричной разности и ловит опечатку в любом файле
 * при добавлении строки (например `restore_outcome_*` только в `values/`).
 *
 * Ищем res-каталог от каталога модуля вверх: тест не зависит от рабочего каталога Gradle.
 */
class StringsLocaleParityTest {

    private val locales = listOf(
        "values",
        "values-en",
        "values-es",
        "values-hi",
        "values-in",
        "values-pt",
        "values-zh"
    )

    @Test
    fun `translatable keys are the same in all seven locale catalogues`() {
        val base = translatableKeys(resFile("values/strings.xml"))
        assertTrue("базовый values/strings.xml пуст — сломан разбор, а не локали", base.isNotEmpty())
        assertTrue(
            "разбор вернул ${base.size} ключей, а в values/ их не меньше $MIN_EXPECTED_KEYS — " +
                "регексп сломан, и симметричная разность ниже ничего не значит",
            base.size >= MIN_EXPECTED_KEYS
        )

        val problems = locales.drop(1).mapNotNull { locale ->
            val keys = translatableKeys(resFile("$locale/strings.xml"))
            val missing = (base - keys).sorted()
            val extra = (keys - base).sorted()
            when {
                missing.isEmpty() && extra.isEmpty() -> null
                else -> "$locale: нет ключей $missing; лишние ключи $extra"
            }
        }

        assertTrue(
            "паритет translatable-ключей нарушен:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun `rollback report states are present in every locale`() {
        val required = listOf(
            "restore_outcome_applied",
            "restore_outcome_already_original",
            "restore_outcome_unreadable",
            "restore_outcome_failed",
            "restore_result_unreadable_list",
            "restore_result_failed_list",
            "restore_result_unreadable_hint",
            "restore_result_failed_hint"
        )
        val missing = locales.flatMap { locale ->
            val keys = translatableKeys(resFile("$locale/strings.xml"))
            required.filterNot { it in keys }.map { "$locale/$it" }
        }
        assertTrue(
            "отчёт отката не покрыт локалями (иначе состояние снова станет невидимым): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `unconfirmed bucket strings are present in every locale`() {
        // Ведро «не подтверждено» — третье состояние отчёта: строка без локали снова
        // превратила бы шаг либо в провал, либо в невидимый (решение владельца по rmvgvhbzu).
        val required = listOf(
            "result_unconfirmed",
            "result_unconfirmed_list",
            "result_unconfirmed_hint"
        )
        val missing = locales.flatMap { locale ->
            val keys = translatableKeys(resFile("$locale/strings.xml"))
            required.filterNot { it in keys }.map { "$locale/$it" }
        }
        assertTrue(
            "строка «не подтверждено» не покрыта локалями: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `repeat optimization strings are present in every locale`() {
        // Повтор оптимизации — кнопка на карточке результата и в окне результата: без строки
        // в локали пользователь увидит либо пустую кнопку, либо откат к русскому тексту.
        val required = listOf("result_repeat", "btn_repeat_optimization")
        val missing = locales.flatMap { locale ->
            val keys = translatableKeys(resFile("$locale/strings.xml"))
            required.filterNot { it in keys }.map { "$locale/$it" }
        }
        assertTrue(
            "строки повтора оптимизации не покрыты локалями: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `forbidden ad lexicon is absent from user-facing strings in every locale`() {
        // Нейтральная лексика: пользовательские строки не содержат токенов про рекламные
        // блокировщики и «уборку». Проверяем ЗНАЧЕНИЯ <string>, а не сырые строки файла:
        // в values-es есть комментарий «ADVERTENCIA DNS» (исп. «предупреждение»), и греп
        // по файлу давал бы ложное срабатывание.
        val forbidden = listOf("реклам", "advert", "ad block", "adblock", "уборк")
        val problems = locales.flatMap { locale ->
            val values = stringValues(resFile("$locale/strings.xml"))
            assertTrue(
                "$locale: разбор вернул ${values.size} значений — сломан регексп, и проверка ниже вакуумна",
                values.size >= MIN_EXPECTED_KEYS
            )
            values.flatMap { value ->
                forbidden.filter { value.contains(it, ignoreCase = true) }
                    .map { "$locale: токен '$it' в значении строки" }
            }
        }
        assertTrue("запрещённая лексика в строках UI: $problems", problems.isEmpty())
    }

    private fun stringValues(file: File): List<String> =
        Regex("<string\\s+[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toList()

    private fun translatableKeys(file: File): Set<String> =
        Regex("<string\\s+([^>]*)>")
            .findAll(file.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            // Флаг ищем в ПОЛНОМ теге, а не в хвосте после name: иначе перестановка атрибутов
            // (translatable перед name) молча вернёт символы «←», «⋮», «👆 » в паритет.
            .filterNot { attrs -> attrs.contains(TRANSLATABLE_FALSE) }
            .mapNotNull { attrs -> NAME.find(attrs)?.groupValues?.get(1) }
            .toSet()

    private fun resFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            File(dir, "app/src/main/res/$rel").takeIf { it.isFile }?.let { return it }
            File(dir, "src/main/res/$rel").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("не найден res-файл $rel от ${System.getProperty("user.dir")}")
    }
private companion object {
        /** Ниже этого числа ключей разбор сломан: паритет зеленел бы вакуумно. */
        const val MIN_EXPECTED_KEYS = 200

        const val TRANSLATABLE_FALSE = "translatable=\"false\""
        val NAME = Regex("name=\"([^\"]+)\"")
    }
}