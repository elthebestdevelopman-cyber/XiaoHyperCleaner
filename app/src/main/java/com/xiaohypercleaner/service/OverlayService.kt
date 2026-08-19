package com.xiaohypercleaner.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var hintView: ComposeView? = null
    private var pointerView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        when {
            pointerMode == PointerMode.GENERIC_BOTTOM && pointerText != null -> {
                showGenericBottomCard(pointerText)
            }

            pointerMode != PointerMode.NONE && pointerText != null -> {
                showPointer(pointerMode, pointerText)
            }

            hint != null -> {
                showHint(hint)
            }
        }

        return START_NOT_STICKY
    }

    private fun showHint(text: String) {
        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    HintCard(text = text)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        windowManager?.addView(view, params)
        hintView = view
    }

    private fun showGenericBottomCard(text: String) {
        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    GenericBottomCard(text = text)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        windowManager?.addView(view, params)
        pointerView = view
    }

    private fun showPointer(mode: PointerMode, text: String) {
        val view = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    PointerOverlay(mode = mode, text = text)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(view, params)
        pointerView = view
    }

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

@Composable
private fun HintCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF1976D2),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun GenericBottomCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xFF1976D2),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun PointerOverlay(mode: OverlayService.PointerMode, text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        val arrowAlignment = when (mode) {
            OverlayService.PointerMode.TOP_RIGHT -> Alignment.TopEnd
            OverlayService.PointerMode.BOTTOM_LIST -> Alignment.BottomCenter
            OverlayService.PointerMode.SWITCH_RIGHT -> Alignment.CenterEnd
            OverlayService.PointerMode.LIST_ITEM_CENTER -> Alignment.CenterStart
            else -> Alignment.Center
        }

        val icon = when (mode) {
            OverlayService.PointerMode.TOP_RIGHT -> Icons.Filled.ArrowUpward
            OverlayService.PointerMode.BOTTOM_LIST -> Icons.Filled.ArrowDownward
            OverlayService.PointerMode.SWITCH_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
            OverlayService.PointerMode.LIST_ITEM_CENTER -> Icons.AutoMirrored.Filled.ArrowBack
            else -> Icons.Filled.ArrowUpward
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (mode == OverlayService.PointerMode.LIST_ITEM_CENTER) 24.dp else 0.dp,
                    end = if (mode == OverlayService.PointerMode.SWITCH_RIGHT ||
                        mode == OverlayService.PointerMode.TOP_RIGHT
                    ) 24.dp else 0.dp,
                    top = if (mode == OverlayService.PointerMode.TOP_RIGHT) 32.dp else 0.dp,
                    bottom = if (mode == OverlayService.PointerMode.BOTTOM_LIST) 180.dp else 0.dp
                ),
            contentAlignment = arrowAlignment
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFFFD600),
                modifier = Modifier.size(56.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (mode == OverlayService.PointerMode.LIST_ITEM_CENTER) 16.dp else 0.dp,
                    end = if (mode == OverlayService.PointerMode.SWITCH_RIGHT ||
                        mode == OverlayService.PointerMode.TOP_RIGHT
                    ) 16.dp else 0.dp,
                    top = if (mode == OverlayService.PointerMode.TOP_RIGHT) 96.dp else 0.dp,
                    bottom = if (mode == OverlayService.PointerMode.BOTTOM_LIST) 260.dp else 0.dp
                ),
            contentAlignment = when (mode) {
                OverlayService.PointerMode.BOTTOM_LIST -> Alignment.BottomCenter
                else -> arrowAlignment
            }
        ) {
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .background(
                        color = Color(0xFF1976D2),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFFFFD600),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}