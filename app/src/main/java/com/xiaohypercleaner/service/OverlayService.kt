package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.xiaohypercleaner.AppConstants
import com.xiaohypercleaner.R
import com.xiaohypercleaner.data.SemanticCatalog
import com.xiaohypercleaner.ui.openUrl
import com.xiaohypercleaner.util.AppLog

/**
 * Оверлей с heartbeat-защитой от пропадания.
 *
 * Правило «один show/hide на фазу» обеспечивается OverlayController.
 * Heartbeat каждые 500мс проверяет attached+visible+rect; при потере — re-add.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlaySvc"
        const val ACTION_SET_BLOCKING = "set_blocking"
        const val EXTRA_BLOCKING = "blocking"
        const val ACTION_AUTO_START = "auto_start"
        const val ACTION_AUTO_UPDATE = "auto_update"
        const val ACTION_AUTO_STATUS = "auto_status"
        const val ACTION_RESULT = "result"
        const val ACTION_HIDE = "hide"

        const val EXTRA_STEP = "step"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STATUS = "status"
        const val EXTRA_COMPLETED = "completed"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_SKIPPED = "skipped"
        const val EXTRA_NOTIF_STEPS = "notif_steps"
        private const val HEARTBEAT_INTERVAL_MS = 500L

        /** Каждый N-й удар heartbeat пишет alive-строку в лог (2 с при 500 мс). */
        private const val HEARTBEAT_ALIVE_LOG_EVERY = 4

        /** Потолок строк в списке отключённых уведомлений на экране результатов. */
        private const val NOTIF_LIST_MAX = 8

        /** Потолок строк ручной памятки на экране результатов. */
        private const val MANUAL_LIST_MAX = 7
    }

    enum class PointerMode { TOP_RIGHT, BOTTOM_LIST, SWITCH_RIGHT, LIST_ITEM_CENTER, GENERIC_BOTTOM }

    private var wm: WindowManager? = null

    /** Сколько пунктов ручной памятки показано на экране результатов (для лога). */
    private var manualShown: Int = 0
    private var root: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isBlocking = true
    private var expectingDetach = false
    private val animators = mutableListOf<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())

    /** Окно добавлено через AccessibilityService (TYPE_ACCESSIBILITY_OVERLAY). */
    private var addedViaAccService = false

    /** Ожидаемая геометрия оверлея (полный размер дисплея, включая системные панели). */
    private var expectedWidth = 0
    private var expectedHeight = 0

    /** Логи-однократники: не спамим при каждой проверке heartbeat. */
    private var geometryMismatchLogged = false
    private var zOrderBelowLogged = false

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    /** Счётчик ударов heartbeat (для периодического alive-лога). */
    private var heartbeatTicks = 0

    /** Корневое окно оверлея: логирует все смены attach/detach/visibility. */
    private inner class OverlayRootView(context: android.content.Context) : FrameLayout(context) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            AppLog.i(TAG, "overlay: root attached ts=${System.currentTimeMillis()}")
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            AppLog.i(TAG, "overlay: root detached ts=${System.currentTimeMillis()}")
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            AppLog.i(TAG, "overlay: visibility changed to $visibility ts=${System.currentTimeMillis()}")
            if (visibility != View.VISIBLE) restoreIfIllegal("viewVisibility=$visibility")
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            AppLog.i(TAG, "overlay: windowVisibility=$visibility ts=${System.currentTimeMillis()}")
            OverlayController.markVisible(visibility == View.VISIBLE && isAttachedToWindow)
            if (visibility != View.VISIBLE) restoreIfIllegal("windowVisibility=$visibility")
        }

        /**
         * В защищённой фазе (STEPS) окно не имеет права становиться невидимым:
         * любое скрытие, кроме user cancel/close, логируется как illegal и откатывается.
         */
        private fun restoreIfIllegal(reason: String) {
            if (!OverlayController.phaseRunning || expectingDetach) return
            AppLog.e(TAG, "overlay: illegal $reason during phase — restoring")
            handler.post {
                val v = root ?: return@post
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                val p = layoutParams ?: return@post
                try { wm?.updateViewLayout(v, p) } catch (e: Exception) {
                    AppLog.w(TAG, "overlay: restore update failed: ${e.message}")
                }
                OverlayController.markVisible(true)
            }
        }
    }

    private var tvStep: TextView? = null
    private var tvTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_HIDE -> hide()
            ACTION_SET_BLOCKING -> setBlocking(intent.getBooleanExtra(EXTRA_BLOCKING, true))
            ACTION_AUTO_START -> showAutomation(intent.getIntExtra(EXTRA_TOTAL, 0))
            ACTION_AUTO_UPDATE -> updateAutomation(
                intent.getIntExtra(EXTRA_STEP, 0),
                intent.getIntExtra(EXTRA_TOTAL, 0),
                intent.getStringExtra(EXTRA_TITLE) ?: ""
            )
            ACTION_AUTO_STATUS -> tvStatus?.text = intent.getStringExtra(EXTRA_STATUS) ?: ""
            ACTION_RESULT -> showResult(
                intent.getIntExtra(EXTRA_COMPLETED, 0),
                intent.getIntExtra(EXTRA_TOTAL, 0),
                intent.getIntExtra(EXTRA_FAILED, 0),
                intent.getIntExtra(EXTRA_SKIPPED, 0),
                intent.getStringExtra(EXTRA_NOTIF_STEPS).orEmpty()
            )
        }
        return START_NOT_STICKY
    }

    private fun setBlocking(blocking: Boolean) {
        if (isBlocking == blocking) return
        isBlocking = blocking
        val r = root ?: return
        val p = r.layoutParams as? WindowManager.LayoutParams ?: return
        p.flags = if (blocking) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm?.updateViewLayout(r, p)
            AppLog.i(TAG, "overlay blocking=$blocking")
        } catch (e: Exception) {
            AppLog.w(TAG, "setBlocking update failed: ${e.message}")
        }
    }

    // ─── AUTOMATION ───

    private fun showAutomation(total: Int) {
        hide()
        isBlocking = true
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = roundBg(0x99000000.toInt(), radiusDp = 24)
        }
        val cat = ImageView(this).apply {
            val d = ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_robot_washing_avd)
                ?: ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_robot_companion)
            setImageDrawable(d)
            (d as? Animatable)?.start()
        }
        layout.addView(cat, LinearLayout.LayoutParams(dp(120), dp(120)))
        if (cat.drawable !is Animatable) washWobble(cat)

        tvTitle = TextView(this).apply {
            setText(R.string.automation_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
        }
        layout.addView(tvTitle, llWrap().apply { topMargin = dp(8) })

        tvStep = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        layout.addView(tvStep, llWrap().apply { topMargin = dp(4) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total.coerceAtLeast(1); progress = 0
        }
        layout.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        ).apply { topMargin = dp(12) })

        tvStatus = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        layout.addView(tvStatus, llWrap().apply { topMargin = dp(8) })

        val cancel = Button(this).apply {
            setText(R.string.automation_cancel)
            setTextColor(0xFF64B5F6.toInt())
            setBackgroundColor(Color.TRANSPARENT)
        }
        layout.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(12) })
        cancel.setOnClickListener {
            AppLog.i(TAG, "automation cancelled by user")
            OverlayController.endPhase()
            hide()
            AdbEnablerService.instance?.cancelRunner()
            OverlayController.triggerCancel()
        }

        addRoot(touchable = true, fullScreen = true).apply {
            addView(layout, flParams(Gravity.CENTER))
        }
        updateAutomation(0, total, "")
        startHeartbeat()
        AppLog.i(TAG, "automation overlay shown (blocking), total=$total")
    }

    private fun updateAutomation(step: Int, total: Int, title: String) {
        tvStep?.text = getString(R.string.automation_step, step, total)
        progressBar?.max = total.coerceAtLeast(1)
        progressBar?.progress = step
        if (title.isNotEmpty()) tvTitle?.text = title
    }

    // ═══ RESULT ═══

    private fun showResult(
        completed: Int,
        total: Int,
        failed: Int,
        skipped: Int,
        notifSteps: String
    ) {
        stopHeartbeat()
        hide()
        isBlocking = true
        manualShown = 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = roundBg(0xF5202020.toInt(), radiusDp = 24)
        }
        val cat = ImageView(this).apply { setImageResource(R.drawable.ic_robot_companion) }
        layout.addView(cat, LinearLayout.LayoutParams(dp(120), dp(120)))
        layout.addView(titleText(getString(R.string.result_title), 20f, bold = true), llWrap().apply { topMargin = dp(12) })
        layout.addView(bodyText(getString(R.string.result_summary, completed, total)), llWrap().apply { topMargin = dp(6) })
        if (skipped > 0) layout.addView(bodyText(getString(R.string.result_skipped, skipped), small = true), llWrap().apply { topMargin = dp(4) })
        if (failed > 0) layout.addView(bodyText(getString(R.string.result_failed, failed), small = true), llWrap().apply { topMargin = dp(4) })
        // Прозрачность notif_*: что именно отключено (Аддендум C3).
        val notifTitles = notifSteps.split('\n').filter { it.isNotBlank() }.take(NOTIF_LIST_MAX)
        if (notifTitles.isNotEmpty()) {
            layout.addView(
                titleText(getString(R.string.result_notif_title), 14f, bold = true),
                llWrap().apply { topMargin = dp(10) }
            )
            notifTitles.forEach { title ->
                layout.addView(bodyText("• $title", small = true), llWrap().apply { topMargin = dp(2) })
            }
        }
        layout.addView(bodyText(getString(R.string.result_soft), small = true).apply { setPadding(0, dp(10), 0, 0) }, llWrap())
        // Ручная памятка: свёрнутый блок «Для максимального результата вручную» (≤7 строк).
        val manualBlock = buildManualBlock()
        if (manualBlock != null) layout.addView(manualBlock, llWrap().apply { topMargin = dp(10) })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val btnParams = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        row1.addView(textBtn(getString(R.string.result_rate)) { hide(); returnToApp(); openRate() }, btnParams())
        row1.addView(textBtn(getString(R.string.result_support)) { hide(); returnToApp(); openSupport() }, btnParams())
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(textBtn(getString(R.string.result_share_log)) { hide(); shareLogFromOverlay(); OverlayController.triggerResultClose() }, btnParams())
        row2.addView(textBtn(getString(R.string.result_close)) { hide(); returnToApp(); OverlayController.triggerResultClose() }, btnParams())
        layout.addView(row1, llWrap().apply { topMargin = dp(10) })
        layout.addView(row2, llWrap().apply { topMargin = dp(4) })
        returnToApp()
        addRoot(touchable = true, fullScreen = true).apply { addView(layout, flParams(Gravity.CENTER)) }
        AppLog.i(
            TAG,
            "result shown: $completed/$total, failed=$failed, skipped=$skipped " +
                "notif=${notifTitles.size} manual=$manualShown"
        )
    }

    /**
     * Сворачиваемый блок ручной памятки: свёрнут по умолчанию (список GONE), клик по
     * строке-заголовку разворачивает тексты. Блок ничего не запускает сам и не меняет
     * геометрию/поглощение тапов оверлея — окно и параметры остаются прежними.
     */
    private fun buildManualBlock(): View? {
        SemanticCatalog.ensureLoaded(this)
        val manual = SemanticCatalog.manualSteps().take(MANUAL_LIST_MAX)
        if (manual.isEmpty()) return null

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = titleText("", 13f, bold = true)
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        manual.forEach { item ->
            val prefix = if (item.warning) getString(R.string.result_manual_warning) else ""
            items.addView(
                titleText("$prefix${item.title}", 12f, bold = true),
                llWrap().apply { topMargin = dp(6) }
            )
            items.addView(
                bodyText(item.body, small = true).apply {
                    gravity = Gravity.START
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                },
                llWrap().apply { topMargin = dp(1) }
            )
        }

        var expanded = false
        fun applyHeader() {
            val action = getString(
                if (expanded) R.string.result_manual_collapse else R.string.result_manual_expand
            )
            header.text = "${getString(R.string.result_manual_title)} $action"
        }
        applyHeader()
        header.setOnClickListener {
            expanded = !expanded
            items.visibility = if (expanded) View.VISIBLE else View.GONE
            applyHeader()
            AppLog.i(TAG, "result manual block: expanded=$expanded items=${manual.size}")
        }

        box.addView(header, llWrap())
        box.addView(items, llWrap())
        manualShown = manual.size
        return box
    }

    // ═══ HEARTBEAT ═══

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTicks = 0
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!OverlayController.phaseRunning) {
                    AppLog.i(TAG, "heartbeat idle: phase not running")
                    stopHeartbeat()
                    return
                }
                heartbeatTicks++
                val v = root
                if (v == null) { AppLog.w(TAG, "overlay: heartbeat recovered reason=root-null"); return }
                val attached = v.isAttachedToWindow
                val visible = attached && v.windowVisibility == View.VISIBLE && v.getGlobalVisibleRect(Rect())
                if (visible) verifyGeometry(v)
                // Реальная отрисовка: сверяем z-order нашего окна с фоновым приложением.
                checkZOrder()
                OverlayController.markVisible(visible)
                if (visible && heartbeatTicks % HEARTBEAT_ALIVE_LOG_EVERY == 0) {
                    val rect = Rect().also { v.getGlobalVisibleRect(it) }
                    AppLog.d(
                        TAG,
                        "heartbeat alive attached=$attached visible=$visible " +
                            "rect=${rect.width()}x${rect.height()} tick=$heartbeatTicks"
                    )
                }
                if (!attached || !visible) {
                    val reason = when {
                        !attached -> "detached"
                        v.windowVisibility != View.VISIBLE -> "invisible"
                        else -> "zero-rect"
                    }
                    AppLog.w(TAG, "overlay: heartbeat recovered reason=$reason")
                    val params = layoutParams
                    if (params != null && !attached) {
                        reAddView(v, params, "heartbeat")
                    } else if (params != null) {
                        try { wm?.updateViewLayout(v, params); AppLog.i(TAG, "overlay: heartbeat updateViewLayout after $reason") }
                        catch (e: Exception) { AppLog.w(TAG, "overlay: heartbeat update failed: ${e.message}") }
                    }
                }
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
        AppLog.i(TAG, "heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ═══ Вспомогательные ═══

    private fun returnToApp() {
        try {
            val intent = Intent(this, com.xiaohypercleaner.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            AppLog.w(TAG, "returnToApp failed: ${e.message}")
        }
    }

    private fun shareLogFromOverlay() {
        returnToApp()
        try { com.xiaohypercleaner.ui.shareLog(this) }
        catch (e: Exception) { AppLog.w(TAG, "shareLogFromOverlay failed: ${e.message}") }
    }

    private fun llWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun titleText(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) paint.isFakeBoldText = true; gravity = Gravity.CENTER
    }

    private fun bodyText(s: String, small: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 15f); gravity = Gravity.CENTER
    }

    private fun textBtn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; setTextColor(0xFF64B5F6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
        setOnClickListener { onClick() }
    }

    private fun openRate() {
        try {
            val pkg = packageName
            for (s in listOf("market://details?id=$pkg", "rustore://application/$pkg")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(s)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
                catch (_: Exception) {}
            }
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { AppLog.w(TAG, "openRate failed: ${e.message}") }
    }

    private fun openSupport() {
        try { openUrl(this, AppConstants.SUPPORT_PAGE_URL) }
        catch (e: Exception) { AppLog.w(TAG, "openSupport failed: ${e.message}") }
    }

    private fun addRoot(touchable: Boolean, fullScreen: Boolean): FrameLayout {
        val v = OverlayRootView(this).apply {
            // Панели не прячем — окно их перекрывает целиком.
            fitsSystemWindows = false
            isFocusable = false
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (touchable) flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND

        // Тип окна: из AccessibilityService — TYPE_ACCESSIBILITY_OVERLAY (выше фонового
        // приложения). Fallback без сервиса — TYPE_APPLICATION_OVERLAY (token=null),
        // поэтому дополнительно SHORT_EDGES, чтобы окно шло под вырез экрана.
        val accService = AdbEnablerService.instance
        val windowType = if (accService != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            if (touchable) dimAmount = 0.12f
            if (!fullScreen) gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        addedViaAccService = accService?.attachOverlay(v, params) == true
        if (!addedViaAccService) wm?.addView(v, params)

        updateExpectedGeometry()
        root = v
        layoutParams = params
        isBlocking = touchable
        expectingDetach = false
        geometryMismatchLogged = false
        zOrderBelowLogged = false

        AppLog.i(
            TAG,
            "overlay: add type=${windowTypeName(windowType)} token=${params.token != null} " +
                "flags=0x${Integer.toHexString(params.flags)} viaAcc=$addedViaAccService " +
                "expected=${expectedWidth}x${expectedHeight}"
        )

        // Логируем все смены attach/detach
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                OverlayController.markAttached()
                OverlayController.markVisible(view.windowVisibility == View.VISIBLE)
                AppLog.i(TAG, "overlay: viewAttachedToWindow ts=${System.currentTimeMillis()}")
            }
            override fun onViewDetachedFromWindow(view: View) {
                OverlayController.markDetached()
                AppLog.i(TAG, "overlay: viewDetachedFromWindow ts=${System.currentTimeMillis()}")
                if (expectingDetach) return
                // Вне защищённой фазы (например, DONE после showResult) re-add не нужен.
                if (!OverlayController.phaseRunning) {
                    AppLog.i(TAG, "overlay: detach ignored (phase not running, no re-add)")
                    return
                }
                AppLog.w(TAG, "overlay detached unexpectedly, re-adding via watchdog")
                handler.postDelayed({
                    if (root == v && !OverlayController.isAttached && !expectingDetach) {
                        reAddView(v, params, "watchdog")
                    }
                }, 100)
            }
        })

        // Тапы поглощаем по всей площади, включая системные панели: пользовательские
        // Back/Home не должны прерывать автоматизацию.
        if (touchable) {
            v.setOnTouchListener { _, event ->
                AppLog.d(TAG, "touch intercepted: x=${event.x}, y=${event.y}")
                true
            }
        }
        return v
    }

    /** Тип окна для лога. */
    private fun windowTypeName(type: Int): String = when (type) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> "APPLICATION_OVERLAY"
        else -> "type=$type"
    }

    /** Полный размер дисплея (с системными панелями) — эталон геометрии оверлея. */
    private fun updateExpectedGeometry() {
        val bounds = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm?.currentWindowMetrics?.bounds
            } else {
                null
            }
        }.getOrNull()
        if (bounds != null && !bounds.isEmpty) {
            expectedWidth = bounds.width()
            expectedHeight = bounds.height()
            return
        }
        val dm = resources.displayMetrics
        expectedWidth = dm.widthPixels
        expectedHeight = dm.heightPixels
    }

    /** Сверяет фактический rect оверлея с ожидаемым; расхождение — в лог. */
    private fun verifyGeometry(view: View): Boolean {
        val rect = Rect().also { view.getGlobalVisibleRect(it) }
        val ok = rect.width() >= expectedWidth && rect.height() >= expectedHeight
        if (!ok && !geometryMismatchLogged) {
            geometryMismatchLogged = true
            AppLog.w(
                TAG,
                "overlay: geometry mismatch rect=${rect.width()}x${rect.height()} " +
                    "expected=${expectedWidth}x${expectedHeight}"
            )
        }
        return ok
    }

    /** Добавляет окно тем же путём, что и [addRoot] (AccessibilityService или WM). */
    private fun reAddView(view: View, params: WindowManager.LayoutParams, caller: String): Boolean {
        val acc = AdbEnablerService.instance
        if (acc != null) {
            addedViaAccService = acc.attachOverlay(view, params)
            if (addedViaAccService) {
                AppLog.i(TAG, "$caller: overlay re-added via acc service")
                return true
            }
        }
        return try {
            wm?.addView(view, params)
            addedViaAccService = false
            AppLog.i(TAG, "$caller: overlay re-added via window manager")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "$caller re-add failed: ${e.message}")
            false
        }
    }

    /** Проверка z-order: наш слой против слоя фонового app-окна. */
    private fun checkZOrder() {
        val state = AdbEnablerService.instance?.overlayLayerState() ?: return
        val (ourLayer, fgPkg, fgLayer) = state
        val below = ourLayer < fgLayer
        if (below) {
            if (!zOrderBelowLogged) {
                zOrderBelowLogged = true
                AppLog.w(TAG, "overlay: zorder-below fg=$fgPkg ourLayer=$ourLayer fgLayer=$fgLayer")
            }
            OverlayController.markVisible(false)
        } else if (zOrderBelowLogged) {
            zOrderBelowLogged = false
            AppLog.i(TAG, "overlay: zorder restored ourLayer=$ourLayer fgLayer=$fgLayer")
            OverlayController.markVisible(true)
        }
    }

    private fun washWobble(view: View) {
        ObjectAnimator.ofFloat(view, View.ROTATION, -12f, 8f, -12f).apply {
            duration = 700; repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator(); start()
        }.also { animators.add(it) }
    }

    private fun roundBg(color: Int, radiusDp: Int = 16) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun flParams(gravity: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity)

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun hide() {
        stopHeartbeat()
        expectingDetach = true
        animators.forEach { it.cancel() }; animators.clear()
        removeRootView()
        root = null
        layoutParams = null
        isBlocking = true
        tvStep = null; tvTitle = null; tvStatus = null; progressBar = null
        OverlayController.markDetached()
        AppLog.i(TAG, "overlay hidden")
    }

    /** Снимает окно тем же путём, каким оно было добавлено. */
    private fun removeRootView() {
        val v = root ?: return
        if (addedViaAccService && AdbEnablerService.instance?.detachOverlay(v) == true) {
            addedViaAccService = false
            return
        }
        try { wm?.removeView(v) } catch (_: Exception) {}
        addedViaAccService = false
    }

    override fun onDestroy() {
        stopHeartbeat()
        expectingDetach = true
        animators.forEach { it.cancel() }; animators.clear()
        removeRootView()
        root = null
        layoutParams = null
        isBlocking = true
        OverlayController.markDetached()
        super.onDestroy()
        AppLog.i(TAG, "onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
