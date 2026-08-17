package com.xiaohypercleaner.service

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.xiaohypercleaner.R
import com.xiaohypercleaner.util.AppLog

/** Позиция стрелки относительно текста подсказки */
enum class ArrowPosition { TOP, BOTTOM, LEFT, RIGHT }

/** Данные для интерактивной подсказки */
data class InteractiveHint(
    val text: String,
    val targetRect: Rect?,  // Координаты элемента (null = искать автоматически)
    val highlightColor: Int = Color.YELLOW,
    val arrowPosition: ArrowPosition = ArrowPosition.BOTTOM
)

@Suppress("DEPRECATION")
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
    
    // Интерактивные подсказки
    private var interactiveOverlayView: View? = null
    private var currentHint: InteractiveHint? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null

    companion object {
        private const val TAG = "OverlayService"
    }

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
     * При повороте экрана обновляем позицию оверлея,
     * чтобы он не "улетал" вверх/вниз при смене ориентации.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::root.isInitialized && root.isAttachedToWindow) {
            runCatching {
                val params = baseParams().apply {
                    gravity = if (hintMode) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(if (hintMode) 16 else 32)
                }
                windowManager.updateViewLayout(root, params)
            }
        }
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

    // ═══════════════════════════════════════════════════════════════
    // ИНТЕРАКТИВНЫЕ ПОДСКАЗКИ — поверх настроек Android
    // ═══════════════════════════════════════════════════════════════

    /**
     * Показывает интерактивную подсказку с полупрозрачным фоном,
     * мигающей рамкой вокруг целевого элемента и стрелкой.
     */
    fun showInteractiveHint(hint: InteractiveHint) {
        hideInteractiveHint()  // Скрыть предыдущую
        currentHint = hint

        val overlay = createInteractiveOverlay(hint)
        interactiveOverlayView = overlay

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(overlay, params)
            startPulseAnimation(overlay)
            
            // Авто-скрытие через 10 секунд
            handler.postDelayed({ hideInteractiveHint() }, 10000L)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to show interactive hint: ${e.message}")
        }
    }

    /** Скрывает интерактивную подсказку */
    fun hideInteractiveHint() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        
        interactiveOverlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to remove interactive overlay: ${e.message}")
            }
        }
        interactiveOverlayView = null
        currentHint = null
    }

    /** Создаёт View для интерактивной подсказки */
    private fun createInteractiveOverlay(hint: InteractiveHint): View {
        return object : View(this) {
            private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CC000000")  // Полупрозрачный чёрный
            }
            private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hint.highlightColor
                strokeWidth = dp(4).toFloat()
                style = Paint.Style.STROKE
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dp(16).toFloat()
                textAlign = Paint.Align.CENTER
            }
            private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = hint.highlightColor
            }
            private val rectForClearing = Rect()
            private var pulseAlpha = 255

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                
                // 1. Рисуем полупрозрачный фон
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

                // 2. Определяем целевой прямоугольник
                val targetRect = hint.targetRect ?: run {
                    // Если targetRect не указан, используем центр экрана как fallback
                    Rect(width / 4, height / 3, width * 3 / 4, height / 2)
                }

                // Сохраняем для анимации пульсации
                rectForClearing.set(targetRect)

                // 3. Вырезаем «дырку» в фоне (с небольшим отступом)
                val margin = dp(8)
                val clearRect = Rect(
                    targetRect.left - margin,
                    targetRect.top - margin,
                    targetRect.right + margin,
                    targetRect.bottom + margin
                )
                
                // Ограничиваем размер дырки
                val maxHoleSize = dp(120)
                val centerX = clearRect.centerX()
                val centerY = clearRect.centerY()
                val clampedRect = Rect(
                    (centerX - (clearRect.width() / 2).coerceAtMost(maxHoleSize / 2)).toInt(),
                    (centerY - (clearRect.height() / 2).coerceAtMost(maxHoleSize / 2)).toInt(),
                    (centerX + (clearRect.width() / 2).coerceAtMost(maxHoleSize / 2)).toInt(),
                    (centerY + (clearRect.height() / 2).coerceAtMost(maxHoleSize / 2)).toInt()
                )

                canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
                canvas.drawRect(clampedRect, clearPaint)
                canvas.restore()

                // 4. Рисуем мигающую рамку вокруг целевого элемента
                borderPaint.alpha = pulseAlpha
                canvas.drawRect(clampedRect, borderPaint)

                // 5. Рисуем стрелку от текста к целевому элементу
                drawArrow(canvas, clampedRect, hint.arrowPosition)

                // 6. Рисуем текст инструкции
                drawInstructionText(canvas, clampedRect, hint.text, hint.arrowPosition)
            }

            private fun drawArrow(canvas: Canvas, targetRect: Rect, position: ArrowPosition) {
                val path = Path()
                val arrowSize = dp(12).toFloat()
                val gap = dp(8).toFloat()  // Отступ от рамки

                when (position) {
                    ArrowPosition.TOP -> {
                        // Стрелка снизу текста, указывает вверх на target
                        val textY = targetRect.top - dp(60)
                        path.moveTo(targetRect.centerX().toFloat(), (targetRect.top - gap).toFloat())
                        path.lineTo((targetRect.centerX() - arrowSize).toFloat(), textY)
                        path.lineTo((targetRect.centerX() + arrowSize).toFloat(), textY)
                        path.close()
                    }
                    ArrowPosition.BOTTOM -> {
                        // Стрелка сверху текста, указывает вниз на target
                        val textY = targetRect.bottom + dp(60)
                        path.moveTo(targetRect.centerX().toFloat(), (targetRect.bottom + gap).toFloat())
                        path.lineTo((targetRect.centerX() - arrowSize).toFloat(), textY)
                        path.lineTo((targetRect.centerX() + arrowSize).toFloat(), textY)
                        path.close()
                    }
                    ArrowPosition.LEFT -> {
                        val textX = targetRect.left - dp(60)
                        path.moveTo((targetRect.left - gap).toFloat(), targetRect.centerY().toFloat())
                        path.lineTo(textX, (targetRect.centerY() - arrowSize).toFloat())
                        path.lineTo(textX, (targetRect.centerY() + arrowSize).toFloat())
                        path.close()
                    }
                    ArrowPosition.RIGHT -> {
                        val textX = targetRect.right + dp(60)
                        path.moveTo((targetRect.right + gap).toFloat(), targetRect.centerY().toFloat())
                        path.lineTo(textX, (targetRect.centerY() - arrowSize).toFloat())
                        path.lineTo(textX, (targetRect.centerY() + arrowSize).toFloat())
                        path.close()
                    }
                }
                canvas.drawPath(path, arrowPaint)
            }

            private fun drawInstructionText(
                canvas: Canvas,
                targetRect: Rect,
                text: String,
                position: ArrowPosition
            ) {
                val lines = text.split("\n")
                val maxWidth = dp(280).toFloat()
                
                // Позиция текста зависит от направления стрелки
                val textX = targetRect.centerX().toFloat()
                val textY = when (position) {
                    ArrowPosition.TOP -> targetRect.top - dp(70)
                    ArrowPosition.BOTTOM -> targetRect.bottom + dp(80)
                    ArrowPosition.LEFT -> targetRect.centerY().toFloat()
                    ArrowPosition.RIGHT -> targetRect.centerY().toFloat()
                }

                // Рисуем каждую строку
                lines.forEachIndexed { index, line ->
                    val y = textY + (index - lines.size / 2f) * dp(20)
                    
                    // Для LEFT/RIGHT позиций смещаем текст
                    if (position == ArrowPosition.LEFT) {
                        textPaint.textAlign = Paint.Align.RIGHT
                        canvas.drawText(line, targetRect.left - dp(20), y, textPaint)
                    } else if (position == ArrowPosition.RIGHT) {
                        textPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText(line, targetRect.right + dp(20), y, textPaint)
                    } else {
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(line, textX, y, textPaint)
                    }
                }
            }
        }
    }

    /** Запускает анимацию пульсации рамки */
    private fun startPulseAnimation(overlay: View) {
        pulseAnimator = ObjectAnimator.ofInt(overlay, "pulseAlpha", 255, 128, 255).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Int
                overlay.invalidate()  // Перерисовать с новым alpha
            }
            start()
        }
    }
}