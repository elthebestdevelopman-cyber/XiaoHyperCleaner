package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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

/**
 * Оверлей прогресса: персонаж, описание текущего действия,
 * прогресс-бар и кнопка отмены.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var robot: ImageView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var percentView: TextView
    private var bounce: ObjectAnimator? = null

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
            y = dp(32)
            horizontalMargin = 0.06f
        }

        windowManager.addView(root, params)
        startBounce()
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        root.addView(titleView)

        detailView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(detailView)

        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(14) }
        }
        root.addView(progressBar)

        percentView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener { OverlayController.onCancel?.invoke() }
        }
        root.addView(cancel)
    }

    private fun startBounce() {
        bounce = ObjectAnimator.ofFloat(robot, "translationY", 0f, -dp(6).toFloat(), 0f).apply