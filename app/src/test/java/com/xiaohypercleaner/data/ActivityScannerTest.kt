package com.xiaohypercleaner.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Discovery-скан: ключ кэша активностей, скоринг кандидатов и разбор кэша.
 *
 * Ключ обязан включать versionCode и incremental прошивки (иначе после
 * обновления приложения или прошивки остаётся протухший маршрут), а при
 * отсутствии incremental — суффикс `unknown`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ActivityScannerTest {

    @Test
    fun `cache key includes package version and incremental`() {
        val key = ActivityScanner.cacheKey("com.miui.player", 42L, "V14.0.3.0.SKHMIXM")
        assertEquals("act_cache_com.miui.player_42_V14.0.3.0.SKHMIXM", key)
    }

    @Test
    fun `cache key falls back to unknown suffix when incremental missing`() {
        assertEquals(
            "act_cache_com.miui.player_42_unknown",
            ActivityScanner.cacheKey("com.miui.player", 42L, null)
        )
        assertEquals(
            "act_cache_com.miui.player_42_unknown",
            ActivityScanner.cacheKey("com.miui.player", 42L, "   ")
        )
    }

    @Test
    fun `label match scores higher than class name match`() {
        val labelScore = ActivityScanner.scoreCandidate(
            className = "com.miui.player.ui.MainActivity",
            label = "Музыка",
            keywords = listOf("Музыка")
        )
        val classScore = ActivityScanner.scoreCandidate(
            className = "com.miui.player.ui.MusicActivity",
            label = "Настройки",
            keywords = listOf("music")
        )
        assertTrue("совпадение по подписи должно быть увереннее", labelScore > classScore)
        assertTrue("совпадение по классу должно находиться", classScore > 0)
    }

    @Test
    fun `unrelated activity scores zero`() {
        assertEquals(
            0,
            ActivityScanner.scoreCandidate(
                className = "com.miui.player.ui.SplashActivity",
                label = "Заставка",
                keywords = listOf("recommendations")
            )
        )
    }

    @Test
    fun `cache json round trip keeps candidates`() {
        val candidates = listOf(
            ActivityScanner.ActivityCandidate("com.miui.player.ui.MusicActivity", "Музыка", 90),
            ActivityScanner.ActivityCandidate("com.miui.player.ui.SettingsActivity", "Настройки", 50)
        )

        val restored = ActivityScanner.fromJson(ActivityScanner.toJson(candidates))

        assertEquals(candidates, restored)
    }

    @Test
    fun `cache with unknown schema is discarded`() {
        val foreign = """{"schema":99,"candidates":[{"class":"a.B","label":"","score":10}]}"""
        assertNull("протухший кэш должен приводить к перескану", ActivityScanner.fromJson(foreign))
    }

    @Test
    fun `broken cache json is discarded`() {
        assertNull(ActivityScanner.fromJson("{not-a-json"))
    }

    @Test
    fun `scan uses cache store and reports cache hit`() = runTest {
        val cached = listOf(ActivityScanner.ActivityCandidate("a.B", "label", 10))
        val store = object : ActivityCacheStore {
            var saved: String? = null
            override suspend fun loadActivityCache(cacheKey: String): String =
                ActivityScanner.toJson(cached)

            override suspend fun saveActivityCache(cacheKey: String, json: String) {
                saved = json
            }

            override suspend fun clearActivityCache(pkg: String) = Unit
        }

        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val result = ActivityScanner.scan(
            context = context,
            pkg = "com.miui.player",
            keywords = listOf("music"),
            cache = store,
            incremental = "V14"
        )

        assertTrue("кэш должен отдаваться без запроса к PackageManager", result.fromCache)
        assertEquals(cached, result.candidates)
        assertNull("кэш не перезаписывается при hit", store.saved)
    }
}