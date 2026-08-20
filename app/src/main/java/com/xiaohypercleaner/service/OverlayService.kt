package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.xiaohypercleaner.util.AppLog

/**
 * Оверлей с подсказками поверх системных настроек.
 *
 * ИСПРАВЛЕНО (v1.0-beta3):
 * 1. Оверлей теперь ПЕРСИСТЕНТНЫЙ: живёт, пока его явно не скроют
 *    (hideOverlay/stopService). Раньше исчезал из-за авто-стопа в MainActivity.
 * 2. Стрелка увеличена и пульсирует — её невозможно не заметить.
 * 3. Текст карточки масштабируется автоматически (autoSizeText) под любые экраны.
 * 4. Окно полностью прозрачно для касаний (FLAG_NOT_TOUCHABLE) —
 *    пользователь может нажимать на настройки СКВОЗЬ оверлей.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlaySvc"
        const val EXTRA_HINT = "hint"
        const val EXTRA_POINTER_MODE = "pointer_mode"
        const val EXTRA_POINTER_TEXT = "pointer_text"
        const val EXTRA_POINTER_HINT = "pointer_hint"
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
    private var overlayView: View? = null
    private var pulseAnimator: ValueAnimator? = null

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
        val pointerHint = intent?.getStringExtra(EXTRA_POINTER_HINT)

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

                pointerMode != PointerMode.NONE && (pointerText != null || pointerHint != null) ->
                    showPointer(pointerMode, pointerText ?: pointerHint ?: "")

                hint != null -> showHint(hint)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to show overlay: ${e.message}", e)
            cleanup()
        }

        return START_NOT_STICKY
    }

    private fun windowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * НОВОЕ: флаги, при которых оверлей НЕ перехватывает касания.
     * Пользователь взаимодействует с настройками СКВОЗЬ подсказку.
     */
    private fun windowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType(),
            windowFlags(),
            PixelFormat.TRANSLUCENT
        )

    // ═══════════════════════════════════════════════════════════════
    // UI-ФАБРИКИ (plain View, autoSize-текст для любых экранов)
    // ═══════════════════════════════════════════════════════════════

    /** Карточка с текстом; текст сам подбирает размер под ширину экрана */
    private fun buildCard(text: String, textSizeSp: Float = 15f): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xE6_1976D2.toInt())  // полупрозрачный синий
                cornerRadius = dp(16).toFloat()
            }
            // АВТОМАСШТАБ: текст ужимается на маленьких экранах,
            // чтобы ВСЕГДА помещался целиком
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 12, textSizeSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** Большая пульсирующая стрелка */
    private fun buildArrow(symbol: String): TextView {
        return TextView(this).apply {
            this.text = symbol
            setTextColor(Color.parseColor("#FFFFD600"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.DEFAULT_BOLD
            // тень для контраста на любом фоне
            setShadowLayer(dp(6).toFloat(), 0f, 0f, 0xCC000000.toInt())
        }
    }

    /** Запускает пульсацию стрелки (внимание пользователя) */
    private fun startPulse(target: View) {
        pulseAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun showHint(text: String) {
        val card = buildCard(text).apply { gravity = Gravity.CENTER }
        val container = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(120))
            addView(
                card, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        addOverlay(container)
    }

    private fun showGenericBottomCard(text: String) {
        val card = buildCard(text)
        val container = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(100))
            addView(
                card, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        addOverlay(container)
    }

    private fun showPointer(mode: PointerMode, text: String) {
        val root = FrameLayout(this)

        val arrowSymbol = when (mode) {
            PointerMode.TOP_RIGHT -> "⬆"
            PointerMode.BOTTOM_LIST -> "⬇"
            PointerMode.SWITCH_RIGHT -> "➡"
            PointerMode.LIST_ITEM_CENTER -> "⬅"
            else -> "⬆"
        }

        // ── Стрелка (пульсирует) ─────────────────────────────────────
        val arrow = buildArrow(arrowSymbol)
        val arrowParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            when (mode) {
                PointerMode.TOP_RIGHT -> {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(24); marginEnd = dp(16)
                }

                PointerMode.BOTTOM_LIST -> {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(160)
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
        root.addView(arrow, arrowParams)
        startPulse(arrow)

        // ── Карточка с текстом (всегда целиком, autoSize) ────────────
        val card = buildCard("$arrowSymbol  $text")
        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val h = dp(16)
            leftMargin = h; rightMargin = h
            when (mode) {
                PointerMode.TOP_RIGHT -> {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(96)
                }

                PointerMode.BOTTOM_LIST -> {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(240)
                }

                PointerMode.SWITCH_RIGHT -> {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                }

                PointerMode.LIST_ITEM_CENTER -> {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                }

                else -> gravity = Gravity.CENTER
            }
        }
        root.addView(card, cardParams)

        addOverlay(root)
    }

    private fun addOverlay(view: View) {
        windowManager?.addView(view, baseParams())
        overlayView = view
        AppLog.i(TAG, "overlay shown (persistent until hideOverlay)")
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun cleanup() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy — overlay removed")
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}