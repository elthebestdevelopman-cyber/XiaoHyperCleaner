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

    private fun translatableKeys(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"([^>]*)>")
            .findAll(file.readText(Charsets.UTF_8))
            .filter { !it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
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
}