package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

/**
 * Оверлей с 4 режимами.
 *
 * ИСПРАВЛЕНО в этой версии:
 *  1. Робокот = ImageView(ic_robot_companion) — ТОТ ЖЕ кот, что на сплеше,
 *     с анимацией «умывания» (покачивание + клубок крутится)
 *  2. HINT/POINTER — маленькие НЕ-блокирующие пузыри (NOT_TOUCHABLE)
 *     + АВТО-СКРЫТИЕ через 8–10 сек (проблема «экран не кликабелен»)
 *  3. hide() теперь останавливает сервис (stopSelf) — оверлей исчезает сразу
 *  4. Кнопка «Отменить» прячет оверлей мгновенно + дёргает triggerCancel
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlaySvc"
        const val ACTION_HINT = "hint"
        const val ACTION_POINTER = "pointer"
        const val ACTION_AUTO_START = "auto_start"
        const val ACTION_AUTO_UPDATE = "auto_update"
        const val ACTION_AUTO_STATUS = "auto_status"
        const val ACTION_RESULT = "result"
        const val ACTION_HIDE = "hide"

        const val EXTRA_HINT = "hint"
        const val EXTRA_POINTER_MODE = "pointer_mode"
        const val EXTRA_POINTER_TEXT = "pointer_text"
        const val EXTRA_STEP = "step"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STATUS = "status"
        const val EXTRA_COMPLETED = "completed"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_SKIPPED = "skipped"
        const val EXTRA_POINTER_HINT = EXTRA_POINTER_TEXT

        private const val HINT_AUTO_HIDE_MS = 8000L    // подсказка исчезает сама
        private const val POINTER_AUTO_HIDE_MS = 10000L
    }

    enum class PointerMode { TOP_RIGHT, BOTTOM_LIST, SWITCH_RIGHT, LIST_ITEM_CENTER, GENERIC_BOTTOM }

    private var wm: WindowManager? = null
    private var root: View? = null
    private val animators = mutableListOf<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hide() }

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
            ACTION_HINT -> showHint(intent.getStringExtra(EXTRA_HINT) ?: "")
            ACTION_POINTER -> showPointer(
                intent.getStringExtra(EXTRA_POINTER_MODE) ?: "LIST_ITEM_CENTER",
                intent.getStringExtra(EXTRA_POINTER_TEXT) ?: ""
            )

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
                intent.getIntExtra(EXTRA_SKIPPED, 0)
            )
        }
        return START_NOT_STICKY
    }

    // ═══════════════════════════════════════════════════════════════
    // HINT: маленький пузырь внизу, НЕ блокирует клики, авто-скрытие
    // ═══════════════════════════════════════════════════════════════

    private fun showHint(text: String) {
        hide()
        val card = TextView(this).apply {
            this.text = "💡 $text"
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundBg(0xE61976D2.toInt())
            autoSize()
        }
        // НЕ-блокирующий: NOT_TOUCHABLE — клики проходят сквозь подсказку
        addRoot(touchable = false, fullScreen = false).apply {
            addView(card, flParams(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        }
        scheduleAutoHide(HINT_AUTO_HIDE_MS)
        AppLog.i(TAG, "hint shown (non-blocking, auto-hide)")
    }

    // ═══════════════════════════════════════════════════════════════
    // POINTER: пульсирующая стрелка + пузырь, НЕ блокирует, авто-скрытие
    // ═══════════════════════════════════════════════════════════════

    private fun showPointer(mode: String, text: String) {
        hide()
        val arrowSymbol = when (mode) {
            "TOP_RIGHT" -> "⬆"; "BOTTOM_LIST", "GENERIC_BOTTOM" -> "⬇"
            "SWITCH_RIGHT" -> "➡"; else -> "⬅"
        }
        val arrow = TextView(this).apply {
            this.text = arrowSymbol
            setTextColor(Color.parseColor("#FFFFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0xCC000000.toInt())
        }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundBg(0xE61976D2.toInt())
            autoSize()
        }
        val arrowGravity = when (mode) {
            "TOP_RIGHT" -> Gravity.TOP or Gravity.END
            "BOTTOM_LIST", "GENERIC_BOTTOM" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            "SWITCH_RIGHT" -> Gravity.CENTER_VERTICAL or Gravity.END
            else -> Gravity.CENTER_VERTICAL or Gravity.START
        }
        val bubbleGravity = when (mode) {
            "TOP_RIGHT" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "BOTTOM_LIST", "GENERIC_BOTTOM" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
        addRoot(touchable = false, fullScreen = true).apply {
            setPadding(dp(16), dp(24), dp(16), dp(140))
            addView(arrow, flParams(arrowGravity))
            addView(bubble, flParams(bubbleGravity))
        }
        pulse(arrow)
        scheduleAutoHide(POINTER_AUTO_HIDE_MS)
        AppLog.i(TAG, "pointer shown mode=$mode (non-blocking, auto-hide)")
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTO: ТОТ ЖЕ робокот, что на сплеше, «умывается»
    // ═══════════════════════════════════════════════════════════════

    private fun showAutomation(total: Int) {
        hide()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = roundBg(0xF0202020.toInt(), radiusDp = 24)
        }

        // ИСПРАВЛЕНО: настоящий кот со сплеша + клубок, а не Lottie-круг
        val catWrap = FrameLayout(this)
        val cat = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_companion)
        }
        val yarn = ImageView(this).apply {
            setImageResource(R.drawable.ic_yarn_ball)
        }
        catWrap.addView(cat, FrameLayout.LayoutParams(dp(110), dp(110)))
        catWrap.addView(yarn, FrameLayout.LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        })
        layout.addView(catWrap, LinearLayout.LayoutParams(dp(120), dp(120)))

        // «Умывание»: кот покачивается, клубок крутится
        wobble(cat)
        spin(yarn)

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
        layout.addView(
            progressBar, LinearLayout.LayoutParams(
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
        layout.addView(
            cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(12) })
        cancel.setOnClickListener {
            AppLog.i(TAG, "automation cancelled by user")
            hide()                              // ИСПРАВЛЕНО: оверлей исчезает сразу
            OverlayController.triggerCancel()
        }

        addRoot(touchable = true, fullScreen = true).apply {
            addView(layout, flParams(Gravity.CENTER))
        }
        updateAutomation(0, total, "")
        AppLog.i(TAG, "automation overlay shown, total=$total")
    }

    private fun updateAutomation(step: Int, total: Int, title: String) {
        tvStep?.text = getString(R.string.automation_step, step, total)
        progressBar?.max = total.coerceAtLeast(1)
        progressBar?.progress = step
        if (title.isNotEmpty()) tvTitle?.text = title
    }

    // ═══════════════════════════════════════════════════════════════
    // RESULT: довольный кот + мягкая просьба
    // ═══════════════════════════════════════════════════════════════

    private fun showResult(completed: Int, total: Int, failed: Int, skipped: Int) {
        hide()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = roundBg(0xF5202020.toInt(), radiusDp = 24)
        }

        val cat = ImageView(this).apply { setImageResource(R.drawable.ic_robot_companion) }
        layout.addView(cat, LinearLayout.LayoutParams(dp(120), dp(120)))

        layout += titleText(getString(R.string.result_title), 20f, bold = true)
        layout += bodyText(getString(R.string.result_summary, completed, total))
        if (skipped > 0) layout += bodyText(
            getString(R.string.result_skipped, skipped),
            small = true
        )
        if (failed > 0) layout += bodyText(getString(R.string.result_failed, failed), small = true)
        layout += bodyText(getString(R.string.result_soft), small = true).apply {
            setPadding(0, dp(10), 0, 0)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        row += textBtn(getString(R.string.result_rate)) { openRate() }
        row += textBtn(getString(R.string.result_support)) { openSupport() }
        row += textBtn(getString(R.string.result_close)) {
            hide()
            OverlayController.triggerResultClose()
        }
        layout.addView(row, llWrap().apply { topMargin = dp(10) })

        addRoot(touchable = true, fullScreen = true).apply {
            addView(layout, flParams(Gravity.CENTER))
        }
        AppLog.i(TAG, "result shown: $completed/$total, failed=$failed, skipped=$skipped")
    }

    // ═══════════════════════════════════════════════════════════════
    // Вспомогательные
    // ═══════════════════════════════════════════════════════════════

    private fun scheduleAutoHide(ms: Long) {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, ms)
    }

    private operator fun LinearLayout.plusAssign(v: View) {
        addView(v, llWrap().apply { topMargin = dp(6) })
    }

    private fun llWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

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
        gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    private fun openRate() {
        try {
            val pkg = packageName
            for (s in listOf("market://details?id=$pkg", "rustore://application/$pkg")) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(s))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ); return
                } catch (_: Exception) {
                }
            }
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "openRate failed: ${e.message}")
        }
    }

    private fun openSupport() {
        try {
            startActivity(
                Intent(this, com.xiaohypercleaner.ui.WebViewActivity::class.java)
                    .putExtra(
                        com.xiaohypercleaner.ui.WebViewActivity.EXTRA_URL,
                        "https://yoomoney.ru/to/410011379195150"
                    )
                    .putExtra(com.xiaohypercleaner.ui.WebViewActivity.EXTRA_TITLE, "ЮMoney")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "openSupport failed: ${e.message}")
        }
    }

    /**
     * fullScreen=false → маленький пузырь внизу (hint) — никогда не мешает.
     * fullScreen=true + touchable=false → pointer (проходит сквозь).
     * fullScreen=true + touchable=true → automation/result (только кнопка активна).
     */
    private fun addRoot(touchable: Boolean, fullScreen: Boolean): FrameLayout {
        val v = FrameLayout(this)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (touchable) flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        ).apply {
            if (touchable) dimAmount = 0.55f
            if (!fullScreen) gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        wm?.addView(v, params)
        root = v
        return v
    }

    private fun pulse(view: View) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 800; repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator(); start()
        }.also { animators.add(it) }
    }

    /** Покачивание кота — «умывается» */
    private fun wobble(view: View) {
        ObjectAnimator.ofFloat(view, View.ROTATION, -5f, 5f, -5f).apply {
            duration = 1400; repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator(); start()
        }.also { animators.add(it) }
    }

    private fun spin(view: View) {
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = 2500; repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator(); start()
        }.also { animators.add(it) }
    }

    private fun roundBg(color: Int, radiusDp: Int = 16) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun TextView.autoSize() =
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 12, 16, 1, TypedValue.COMPLEX_UNIT_SP
        )

    private fun flParams(gravity: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT, gravity
    )

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** ИСПРАВЛЕНО: убираем окно И останавливаем сервис */
    private fun hide() {
        handler.removeCallbacks(autoHideRunnable)
        animators.forEach { it.cancel() }; animators.clear()
        root?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        tvStep = null; tvTitle = null; tvStatus = null; progressBar = null
        stopSelf()
        AppLog.i(TAG, "overlay hidden, service stopped")
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoHideRunnable)
        animators.forEach { it.cancel() }; animators.clear()
        root?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}