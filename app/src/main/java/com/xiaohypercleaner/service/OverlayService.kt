package com.xiaohypercleaner.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xiaohypercleaner.util.AppLog

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlaySvc"
        const val EXTRA_HINT = "hint"
        const val EXTRA_POINTER_MODE = "pointer_mode"
        const val EXTRA_POINTER_TEXT = "pointer_text"
    }

    enum class PointerMode {
        NONE,
        GENERIC_BOTTOM,
        TOP_RIGHT,
        BOTTOM_LIST,
        SWITCH_RIGHT,
        LIST_ITEM_CENTER
    }

    private var windowManager: WindowManager? = null
    private var hintView: View? = null
    private var pointerView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "onStartCommand: overlay permission not granted, skipping")
            stopSelf()
            return START_NOT_STICKY
        }

        val hint = intent?.getStringExtra(EXTRA_HINT)
        val pointerModeName = intent?.getStringExtra(EXTRA_POINTER_MODE)
        val pointerText = intent?.getStringExtra(EXTRA_POINTER_TEXT)

        val pointerMode = pointerModeName?.let {
            try {
                PointerMode.valueOf(it)
            } catch (e: Exception) {
                PointerMode.NONE
            }
        } ?: PointerMode.NONE

        AppLog.i(TAG, "onStartCommand hint=$hint pointer=$pointerMode")

        cleanup()

        try {
            when {
                pointerMode == PointerMode.GENERIC_BOTTOM && pointerText != null ->
                    showGenericBottomCard(pointerText)

                pointerMode != PointerMode.NONE && pointerText != null ->
                    showPointer(pointerMode, pointerText)

                hint != null -> showHint(hint)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to show overlay: ${e.message}", e)
            cleanup()
        }

        return START_NOT_STICKY
    }

    private fun windowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun buildCard(context: Context): TextView {
        return TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF1976D2"))
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    private fun showHint(text: String) {
        val card = buildCard(this).apply {
            this.text = text
            gravity = Gravity.CENTER
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(
                card, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }

        windowManager?.addView(container, params)
        hintView = container
    }

    private fun showGenericBottomCard(text: String) {
        val card = buildCard(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(
                card, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(100)
        }

        windowManager?.addView(container, params)
        pointerView = container
    }

    private fun showPointer(mode: PointerMode, text: String) {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val arrowIcon = when (mode) {
            PointerMode.TOP_RIGHT -> "⬆"
            PointerMode.BOTTOM_LIST -> "⬇"
            PointerMode.SWITCH_RIGHT -> "➡"
            PointerMode.LIST_ITEM_CENTER -> "⬅"
            else -> "⬆"
        }

        val arrowView = TextView(this).apply {
            this.text = arrowIcon
            setTextColor(Color.parseColor("#FFFFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.DEFAULT_BOLD
        }

        val arrowParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            when (mode) {
                PointerMode.TOP_RIGHT -> {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(32)
                    marginEnd = dp(24)
                }

                PointerMode.BOTTOM_LIST -> {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(180)
                }

                PointerMode.SWITCH_RIGHT -> {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    marginEnd = dp(24)
                }

                PointerMode.LIST_ITEM_CENTER -> {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    marginStart = dp(24)
                }

                else -> gravity = Gravity.CENTER
            }
        }
        root.addView(arrowView, arrowParams)

        val cardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF1976D2"))
                cornerRadius = dp(16).toFloat()
            }
        }
        val smallArrow = TextView(this).apply {
            this.text = arrowIcon
            setTextColor(Color.parseColor("#FFFFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
        }
        cardRow.addView(
            smallArrow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT
            setPadding(dp(12), 0, 0, 0)
        }
        cardRow.addView(
            textView, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val cardParams =
            FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                when (mode) {
                    PointerMode.TOP_RIGHT -> {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(96)
                        marginEnd = dp(16)
                    }

                    PointerMode.BOTTOM_LIST -> {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(260)
                    }

                    PointerMode.SWITCH_RIGHT -> {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        marginEnd = dp(16)
                    }

                    PointerMode.LIST_ITEM_CENTER -> {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        marginStart = dp(16)
                    }

                    else -> gravity = Gravity.CENTER
                }
            }
        root.addView(cardRow, cardParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(root, params)
        pointerView = root
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun cleanup() {
        hintView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        pointerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        hintView = null
        pointerView = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}