package com.xiaohypercleaner.util

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Process
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppOpsManager
import org.robolectric.shadows.ShadowSettings

/**
 * P0 (прогон rmuu7xcch): разрешение «Поверх других окон» читается ДВУМЯ источниками.
 * MIUI умеет сбросить `Settings.canDrawOverlays` при живом app-op — тогда окно
 * рисуется, и объявлять разрешение потерянным нельзя (иначе сервис уходит в stopSelf,
 * а прогон падает каскадом `overlay_not_attached`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class OverlayPermissionProbeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
        ShadowAppOpsManager.reset()
    }

    private fun setAppOpMode(mode: Int) {
        val mgr = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        Shadows.shadowOf(mgr).setMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            Process.myUid(),
            app.packageName,
            mode
        )
    }

    @Test
    fun `granted when the system setting allows`() {
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(OverlayPermissionProbe.isGranted(app))
        assertTrue(OverlayPermissionProbe.describe(app).contains("canDraw=true"))
    }

    @Test
    fun `not granted when both sources deny`() {
        setAppOpMode(AppOpsManager.MODE_IGNORED)
        assertFalse(OverlayPermissionProbe.canDraw(app))
        assertFalse(
            "оба источника против — разрешения нет",
            OverlayPermissionProbe.isGranted(app)
        )
    }

    @Test
    fun `granted when only the app-op allows it (MIUI false negative)`() {
        setAppOpMode(AppOpsManager.MODE_ALLOWED)
        assertFalse("Settings соврал в минус", OverlayPermissionProbe.canDraw(app))
        assertTrue(
            "app-op разрешает — окно добавляется, разрешение не потеряно",
            OverlayPermissionProbe.isGranted(app)
        )
        assertTrue(OverlayPermissionProbe.describe(app).contains("appops=true"))
    }
}