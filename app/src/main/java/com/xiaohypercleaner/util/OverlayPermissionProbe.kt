package com.xiaohypercleaner.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings

/**
 * Разрешение «Поверх других окон» по ДВУМ независимым источникам.
 *
 * [Settings.canDrawOverlays] и app-op `android:system_alert_window` хранят состояние
 * раздельно: после ухода приложения в фон MIUI успевает сбросить одно и вернуть другое,
 * и `canDrawOverlays=false` при живом app-op не означает потерю разрешения — окно
 * добавляется, `WindowManager.addView` проходит. Прогон rmuu7xcch: единственный
 * источник объявил разрешение пропавшим, сервис ушёл в `stopSelf`, и 17 шагов упали
 * каскадом `overlay_not_attached`.
 *
 * Оба чтения по публичному API без deprecated-вызовов: `AppOpsManager.checkOpNoThrow`
 * (актуальный метод) + `OPSTR_SYSTEM_ALERT_WINDOW`.
 */
object OverlayPermissionProbe {

    /** Разрешение живо, если его подтверждает хотя бы один источник. */
    fun isGranted(ctx: Context): Boolean = canDraw(ctx) || appOpsAllowed(ctx)

    /** `Settings.canDrawOverlays` — то же чтение, что делает система перед показом окна. */
    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** Состояние app-op SYSTEM_ALERT_WINDOW для собственного uid приложения. */
    fun appOpsAllowed(ctx: Context): Boolean = runCatching {
        val mgr = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (mgr == null) false else
            mgr.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                ctx.packageName
            ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Диагноз для лога/снимка: что говорит каждый источник. */
    fun describe(ctx: Context): String =
        "canDraw=${canDraw(ctx)} appops=${appOpsAllowed(ctx)}"
}