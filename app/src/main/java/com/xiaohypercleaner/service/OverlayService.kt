package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.xiaohypercleaner.R

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var robot: ImageView
    private lateinit var yarn: ImageView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentView: TextView
    private val animators = mutableListOf<ObjectAnimator>()
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildUi()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            horizontalMargin = 0.06f
            y = dp(32)
        }
        windowManager.addView(root, params)
        startPlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
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

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        animators.clear()
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
        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(120))
        }
        robot = ImageView(this).apply {
            setImageResource(R.drawable.ic_robot_companion)
            layoutParams = FrameLayout.LayoutParams(dp(110), dp(110)).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            }
        }
        yarn = ImageView(this).apply {
            setImageResource(R.drawable.ic_yarn_ball)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        }
        stage.addView(robot)
        stage.addView(yarn)
        root.addView(stage)

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

        val cancel = TextView(this).apply {
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
            setOnClickListener { OverlayController.onCancel?.invoke() }
        }
        root.addView(cancel)
    }

    private fun startPlay() {
        animators += ObjectAnimator.ofFloat(robot, "translationY", 0f, -dp(6).toFloat(), 0f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(
            yarn,
            "translationX",
            dp(4).toFloat(),
            dp(106).toFloat()
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(yarn, "rotation", 0f, 360f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun matchWrap(topMargin: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}