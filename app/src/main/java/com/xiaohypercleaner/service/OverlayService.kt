package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.xiaohypercleaner.R

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var robot: ImageView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentView: TextView
    private lateinit var cancelView: TextView
    private var bounce: ObjectAnimator? = null
    private var lastPercent = -1
    private var hintMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildUi()
        val params = baseParams().apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }
        windowManager.addView(root, params)
        startBounce()
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            // Режим подсказки: полупрозрачная карточка с инструкцией поверх настроек
            it.getStringExtra("hint")?.let { hint ->
                enterHintMode(hint)
                return START_NOT_STICKY
            }

            // Обычный режим прогресса — выходим из hint, если были в нём
            if (hintMode) exitHintMode()

            it.getStringExtra("status")?.let { t -> titleView.text = t }
            it.getStringExtra("detail")?.let { d ->
                detailView.text = d
                detailView.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            }
            val p = it.getFloatExtra("progress", -1f)
            if (p >= 0f) {
                val pct = (p * 100).toInt().coerceIn(0, 100)
                if (pct != lastPercent) {
                    lastPercent = pct
                    progressBar.progress = pct
                    percentView.text = "$pct%"
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Включает режим подсказки: карточка поднимается вверх,
     * скрываются прогресс и кнопка отмены — остаётся только инструкция.
     */
    private fun enterHintMode(text: String) {
        hintMode = true
        titleView.text = getString(R.string.hint_title)
        detailView.text = text
        detailView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        percentView.visibility = View.GONE
        cancelView.visibility = View.GONE

        val params = baseParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        root.alpha = 0.92f
    }

    private fun exitHintMode() {
        hintMode = false
        progressBar.visibility = View.VISIBLE
        percentView.visibility = View.VISIBLE
        cancelView.visibility = View.VISIBLE

        val params = baseParams().apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(32)
        }
        runCatching { windowManager.updateViewLayout(root, params) }
        root.alpha = 1f
    }

    override fun onDestroy() {
        bounce?.cancel()
        bounce = null
        if (::root.isInitialized && root.isAttachedToWindow) {
            runCatching { windowManager.removeView(root) }
        }
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121212"))
                cornerRadius = dp(24).toFloat()
            }
        }
        robot = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_companion)
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(110))
        }
        root.addView(robot)
        titleView = TextView(this).apply {
            text = getString(R.string.status_working)
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = matchWrap(dp(8))
        }
        root.addView(titleView)
        detailView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = matchWrap(dp(4))
        }
        root.addView(detailView)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(14) }
        }
        root.addView(progressBar)
        percentView = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
            layoutParams = matchWrap(dp(4))
        }
        root.addView(percentView)
        cancelView = TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E57373"))
                cornerRadius = dp(24).toFloat()
            }
            layoutParams = matchWrap(dp(16))
            setOnClickListener { OverlayController.triggerCancel() }
        }
        root.addView(cancelView)
    }

    private fun startBounce() {
        bounce = ObjectAnimator.ofFloat(robot, "translationY", 0f, -dp(6).toFloat(), 0f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun matchWrap(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}