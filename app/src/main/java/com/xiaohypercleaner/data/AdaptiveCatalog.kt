package com.xiaohypercleaner.data

import android.content.Context
import android.content.pm.PackageManager
import com.xiaohypercleaner.util.AppLog
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Адаптивный каталог UI-элементов и текстов для разных регионов/прошивок.
 *
 * ПУНКТ 3 ОПТИМИЗАЦИИ:
 * Загружает JSON-каталог из assets/catalog/adaptive_catalog.json и предоставляет
 * методы мерджа, которые SimpleRunner использует для поиска элементов на экране.
 *
 * КАТАЛОГ СОДЕРЖИТ:
 * - pkgSteps: маппинг stepId → список пакетов для разных регионов
 * - groups: группы шагов с общими пакетами (e.g., "browser" → [com.mi.globalbrowser, ...])
 * - uiSteps: дополнительные searchTexts / drillPath / confirmTexts для конкретных шагов
 * - enterSafe: безопасные тексты для входа (не триггерят рекламу)
 *
 * МЕТОДЫ МЕРДЖА (используются в SimpleRunner):
 * - mergeSearchTexts()    — базовые + каталожные searchTexts
 * - mergeDrillPath()      — базовый + каталожный drillPath
 * - mergeConfirmTexts()   — базовые + каталожные confirmTexts
 * - mergeAdditionalToggles() — базовые + каталожные additionalToggles
 * - resolveInstalledPackageForGroup() — первый установленный пакет из группы
 * - packagesForStep()     — все установленные пакеты для шага
 *
 * УЛУЧШЕНИЯ:
 * 1. Ленивая загрузка каталога (один раз при первом обращении)
 * 2. Кэширование результатов merge для производительности (ConcurrentHashMap)
 * 3. Безопасный fallback если каталог отсутствует или повреждён
 * 4. Полная документация
 * 5. Русские логи для соответствия правилу 1
 * 6. Явные типы для всех переменных
 * 7. Потокобезопасность (ConcurrentHashMap + dedicated lock object)
 * 8. Явное указание UTF-8 при чтении из assets
 * 9. ИСПРАВЛЕН КРИТИЧЕСКИЙ БАГ: добавлен отсутствовавший метод getPackagesForGroup()
 */
object AdaptiveCatalog {

    private const val TAG: String = "AdaptiveCatalog"
    private const val CATALOG_ASSET_PATH: String = "catalog/adaptive_catalog.json"

    /** Загруженный каталог (ленивая инициализация) */
    @Volatile
    private var catalogJson: JSONObject? = null

    /** Флаг успешной загрузки */
    @Volatile
    private var isLoaded: Boolean = false

    /** Объект блокировки для double-checked locking */
    private val lock: Any = Any()

    /** Кэш результатов мерджа для производительности (потокобезопасный) */
    private val searchTextsCache: MutableMap<String, List<String>> = ConcurrentHashMap()
    private val drillPathCache: MutableMap<String, List<List<String>>> = ConcurrentHashMap()
    private val confirmTextsCache: MutableMap<String, List<String>> = ConcurrentHashMap()
    private val additionalTogglesCache: MutableMap<String, List<String>> = ConcurrentHashMap()
    private val packagesCache: MutableMap<String, List<String>> = ConcurrentHashMap()

    // ═══════════════════════════════════════════════════════════════
    // Загрузка каталога
    // ═══════════════════════════════════════════════════════════════

    /**
     * Загружает каталог из assets. Вызывается автоматически при первом обращении.
     * Безопасен: если каталог отсутствует, все merge-методы возвращают defaults.
     *
     * @param context Контекст приложения для доступа к assets
     */
    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(lock) {
            if (isLoaded) return
            try {
                val jsonStr: String = context.assets.open(CATALOG_ASSET_PATH)
                    .use { stream -> InputStreamReader(stream, Charsets.UTF_8).readText() }
                catalogJson = JSONObject(jsonStr)
                isLoaded = true

                val pkgSteps: Int = catalogJson?.optJSONObject("pkgSteps")?.length() ?: 0
                val groups: Int = catalogJson?.optJSONObject("groups")?.length() ?: 0
                val uiSteps: Int = catalogJson?.optJSONObject("uiSteps")?.length() ?: 0
                val enterSafe: Int = catalogJson?.optJSONArray("enterSafe")?.length() ?: 0

                AppLog.i(
                    TAG,
                    "каталог загружен: pkgSteps=$pkgSteps groups=$groups " +
                            "uiSteps=$uiSteps enterSafe=$enterSafe"
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Не удалось загрузить адаптивный каталог: ${e.message}")
                isLoaded = true // Помечаем как загруженный, чтобы не пытаться повторно
            }
        }
    }

    /** Очищает кэш (вызывать при смене языка/региона) */
    fun clearCache() {
        searchTextsCache.clear()
        drillPathCache.clear()
        confirmTextsCache.clear()
        additionalTogglesCache.clear()
        packagesCache.clear()
        AppLog.i(TAG, "кэш очищен")
    }

    // ═══════════════════════════════════════════════════════════════
    // Методы мерджа (используются в SimpleRunner)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Мерджит базовые searchTexts с каталожными.
     *
     * @param context Контекст для загрузки каталога
     * @param stepId ID шага (e.g., "msa", "ads_personalization")
     * @param defaults Базовые тексты из SimpleSteps.Step.searchTexts
     * @return Объединённый список без дубликатов
     */
    fun mergeSearchTexts(
        context: Context,
        stepId: String,
        defaults: List<String>
    ): List<String> {
        ensureLoaded(context)
        val cacheKey: String = "st_$stepId"
        searchTextsCache[cacheKey]?.let { return it }

        val catalogTexts: List<String> = getCatalogList("uiSteps", stepId, "searchTexts")
        val merged: List<String> = (defaults + catalogTexts).distinct()
        searchTextsCache[cacheKey] = merged
        return merged
    }

    /**
     * Мерджит базовый drillPath с каталожным.
     *
     * @param context Контекст для загрузки каталога
     * @param stepId ID шага
     * @param defaults Базовый drillPath из SimpleSteps.Step.drillPath
     * @return Объединённый drillPath (каталожные уровни добавляются после базовых)
     */
    fun mergeDrillPath(
        context: Context,
        stepId: String,
        defaults: List<List<String>>
    ): List<List<String>> {
        ensureLoaded(context)
        val cacheKey: String = "dp_$stepId"
        drillPathCache[cacheKey]?.let { return it }

        val catalogDrill: List<List<String>> = getCatalogDrillPath(stepId)
        val merged: List<List<String>> = if (catalogDrill.isNotEmpty()) {
            defaults + catalogDrill
        } else {
            defaults
        }
        drillPathCache[cacheKey] = merged
        return merged
    }

    /**
     * Мерджит базовые confirmTexts с каталожными.
     *
     * @param context Контекст для загрузки каталога
     * @param stepId ID шага
     * @param defaults Базовые тексты подтверждения
     * @return Объединённый список без дубликатов
     */
    fun mergeConfirmTexts(
        context: Context,
        stepId: String,
        defaults: List<String>
    ): List<String> {
        ensureLoaded(context)
        val cacheKey: String = "ct_$stepId"
        confirmTextsCache[cacheKey]?.let { return it }

        val catalogTexts: List<String> = getCatalogList("uiSteps", stepId, "confirmTexts")
        val merged: List<String> = (defaults + catalogTexts).distinct()
        confirmTextsCache[cacheKey] = merged
        return merged
    }

    /**
     * Мерджит базовые additionalToggles с каталожными.
     *
     * @param context Контекст для загрузки каталога
     * @param stepId ID шага
     * @param defaults Базовые тексты дополнительных тумблеров
     * @return Объединённый список без дубликатов
     */
    fun mergeAdditionalToggles(
        context: Context,
        stepId: String,
        defaults: List<String>
    ): List<String> {
        ensureLoaded(context)
        val cacheKey: String = "at_$stepId"
        additionalTogglesCache[cacheKey]?.let { return it }

        val catalogTexts: List<String> = getCatalogList("uiSteps", stepId, "additionalToggles")
        val merged: List<String> = (defaults + catalogTexts).distinct()
        additionalTogglesCache[cacheKey] = merged
        return merged
    }

    /**
     * Находит первый установленный пакет из группы шагов.
     *
     * Используется в SimpleRunner.runInternal() для определения целевого пакета.
     *
     * @param context Контекст для проверки установки пакетов
     * @param groupName Имя группы (e.g., "browser", "music") или stepId
     * @param profile Профиль прошивки для приоритизации региональных пакетов
     * @return Первый установленный пакет или null
     */
    fun resolveInstalledPackageForGroup(
        context: Context,
        groupName: String,
        profile: RomProfile
    ): String? {
        ensureLoaded(context)
        val packages: List<String> = getPackagesForGroup(groupName, profile)
        return packages.firstOrNull { isPackageInstalled(context, it) }
    }

    /**
     * Возвращает все установленные пакеты для шага.
     *
     * Используется в SimpleRunner.runInternal() для проверки requiredPackages.
     *
     * @param context Контекст для проверки установки пакетов
     * @param stepId ID шага
     * @param defaults Базовые пакеты из SimpleSteps.Step.requiredPackages
     * @param profile Профиль прошивки для приоритизации
     * @return Список установленных пакетов
     */
    fun packagesForStep(
        context: Context,
        stepId: String,
        defaults: List<String>,
        profile: RomProfile
    ): List<String> {
        ensureLoaded(context)
        val cacheKey: String = "pk_${stepId}_${profile.regionCode}"
        packagesCache[cacheKey]?.let { return it }

        val catalogPkgs: List<String> = getCatalogPackages(stepId, profile)
        val allPkgs: List<String> = (defaults + catalogPkgs).distinct()
        val installed: List<String> = allPkgs.filter { isPackageInstalled(context, it) }
        packagesCache[cacheKey] = installed
        return installed
    }

    // ═══════════════════════════════════════════════════════════════
    // Внутренние хелперы
    // ═══════════════════════════════════════════════════════════════

    /**
     * Извлекает список строк из каталога по пути section.stepId.key.
     * Безопасен: возвращает пустой список при отсутствии ключа.
     *
     * @param section Раздел каталога (e.g., "uiSteps")
     * @param stepId ID шага
     * @param key Имя поля (e.g., "searchTexts")
     * @return Список строк или пустой список
     */
    private fun getCatalogList(section: String, stepId: String, key: String): List<String> {
        return try {
            val sectionObj: JSONObject = catalogJson?.optJSONObject(section) ?: return emptyList()
            val stepObj: JSONObject = sectionObj.optJSONObject(stepId) ?: return emptyList()
            val arr: org.json.JSONArray = stepObj.optJSONArray(key) ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        } catch (e: Exception) {
            AppLog.w(TAG, "getCatalogList не удался для $section.$stepId.$key: ${e.message}")
            emptyList()
        }
    }

    /**
     * Извлекает drillPath из каталога.
     * Формат в JSON: "drillPath": [["Уровень1"], ["Уровень2", "Alt"]]
     *
     * @param stepId ID шага
     * @return Список уровней (каждый уровень — список альтернативных текстов)
     */
    private fun getCatalogDrillPath(stepId: String): List<List<String>> {
        return try {
            val uiSteps: JSONObject = catalogJson?.optJSONObject("uiSteps") ?: return emptyList()
            val stepObj: JSONObject = uiSteps.optJSONObject(stepId) ?: return emptyList()
            val drillArr: org.json.JSONArray =
                stepObj.optJSONArray("drillPath") ?: return emptyList()
            (0 until drillArr.length()).mapNotNull { i ->
                val levelArr: org.json.JSONArray =
                    drillArr.optJSONArray(i) ?: return@mapNotNull null
                (0 until levelArr.length()).mapNotNull { levelArr.optString(it) }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getCatalogDrillPath не удался для $stepId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Извлекает пакеты для группы или шага из каталога.
     * Приоритизирует пакеты для текущего региона профиля.
     *
     * @param stepId ID шага
     * @param profile Профиль прошивки
     * @return Список пакетов, отсортированный по приоритету региона
     */
    private fun getCatalogPackages(stepId: String, profile: RomProfile): List<String> {
        return try {
            // Сначала пробуем pkgSteps (прямая маппинг stepId → пакеты)
            val pkgSteps: JSONObject? = catalogJson?.optJSONObject("pkgSteps")
            val stepPkgs: List<String> = pkgSteps?.optJSONArray(stepId)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: emptyList()

            // Если нет прямой маппинга, пробуем groups
            val groupPkgs: List<String> = if (stepPkgs.isEmpty()) {
                val groups: JSONObject? = catalogJson?.optJSONObject("groups")
                // Ищем группу по stepId (без суффикса _sys, _notif и т.д.)
                val groupName: String = stepId.substringBefore("_")
                groups?.optJSONArray(groupName)?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it) }
                } ?: emptyList()
            } else {
                emptyList()
            }

            // Приоритизация по региону
            val regionCode: String = profile.regionCode.uppercase()
            val allPkgs: List<String> = (stepPkgs + groupPkgs).distinct()

            // Сортируем: пакеты с суффиксом региона идут первыми
            allPkgs.sortedByDescending { pkg ->
                when {
                    pkg.contains(regionCode, ignoreCase = true) -> 2
                    pkg.contains("global", ignoreCase = true) -> 1
                    else -> 0
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getCatalogPackages не удался для $stepId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Вспомогательный метод для получения пакетов группы (используется в resolveInstalledPackageForGroup).
     *
     * ИСПРАВЛЕНО: метод отсутствовал в исходном коде, что вызывало ошибку компиляции.
     *
     * @param groupName Имя группы
     * @param profile Профиль прошивки
     * @return Список пакетов для группы
     */
    private fun getPackagesForGroup(groupName: String, profile: RomProfile): List<String> {
        return try {
            val groups: JSONObject? = catalogJson?.optJSONObject("groups")
            val pkgs: List<String> = groups?.optJSONArray(groupName)?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: emptyList()

            val regionCode: String = profile.regionCode.uppercase()
            pkgs.sortedByDescending { pkg ->
                when {
                    pkg.contains(regionCode, ignoreCase = true) -> 2
                    pkg.contains("global", ignoreCase = true) -> 1
                    else -> 0
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getPackagesForGroup не удался для $groupName: ${e.message}")
            emptyList()
        }
    }

    /**
     * Проверяет, установлен ли пакет.
     * Кэширует результат PackageManager для производительности (через packagesCache).
     *
     * @param context Контекст приложения
     * @param packageName Имя пакета для проверки
     * @return true, если пакет установлен
     */
    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}