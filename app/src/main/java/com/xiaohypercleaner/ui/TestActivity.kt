package com.xiaohypercleaner.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xiaohypercleaner.R
import com.xiaohypercleaner.service.SystemAutomationService
import com.xiaohypercleaner.ui.theme.Blue500
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import com.xiaohypercleaner.util.AppLog

/**
 * Тестовая Activity для проверки работоспособности Accessibility Service.
 *
 * Флоу:
 * 1. Activity запускается, показывает кнопку "Проверить автоматику"
 * 2. Устанавливает [SystemAutomationService.awaitingTestClick] = true
 * 3. Service находит кнопку и кликает
 * 4. Клик отправляет broadcast TEST_CLICK_SUCCESS
 * 5. Activity получает broadcast и закрывается с результатом RESULT_OK
 *
 * Если за [TIMEOUT_MS] миллисекунд клик не произошёл — RESULT_CANCELED.
 *
 * Почему Activity, а не диалог:
 * - Accessibility Service может кликать только по view-элементам в активном окне
 * - Диалог Compose — это часть MainActivity, что затрудняет поиск конкретной кнопки
 * - Отдельная Activity = изолированное окно с одной кнопкой, которую легко найти
 */
class TestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TestAct"
        private const val TIMEOUT_MS = 5000L

        // ═══════════════════════════════════════════════════════════════
        // НОВОЕ: статическое поле для передачи результата теста
        // из TestActivity в MainActivity.onResume().
        //
        // Почему статическое: ActivityResultLauncher не работает с
        // Intent.FLAG_ACTIVITY_NEW_TASK, а нам нужно запускать TestActivity
        // из SimpleModeController (который не имеет Activity-контекста).
        //
        // MainActivity.onResume() читает это поле, когда:
        // 1. permissionSubPhase == TEST_CLICK
        // 2. SystemAutomationService.awaitingTestClick == false (тест завершён)
        // ═══════════════════════════════════════════════════════════════
        @Volatile
        var lastTestResult: Boolean = false

        const val EXTRA_RESULT = "test_click_result"

        fun createIntent(context: Context): Intent {
            return Intent(context, TestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    // ═══════════════════════════════════════════════════════════════
    // ИСПРАВЛЕНИЕ: защита от множественных broadcast'ов.
    // SystemAutomationService может послать TEST_CLICK_SUCCESS несколько раз
    // (например, при повторном onAccessibilityEvent). Без проверки finished
    // receiver вызывал finishWithResult() повторно → IllegalStateException.
    // ═══════════════════════════════════════════════════════════════
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // КРИТИЧЕСКАЯ ЗАЩИТА: игнорируем broadcast после завершения Activity
            if (finished) {
                AppLog.d(TAG, "Ignoring broadcast after finish: ${intent?.action}")
                return
            }
            when (intent?.action) {
                SystemAutomationService.ACTION_TEST_CLICK_SUCCESS -> {
                    AppLog.i(TAG, "received TEST_CLICK_SUCCESS")
                    finishWithResult(true)
                }

                SystemAutomationService.ACTION_TEST_CLICK_TIMEOUT -> {
                    AppLog.w(TAG, "received TEST_CLICK_TIMEOUT")
                    finishWithResult(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i(TAG, "onCreate")

        val filter = IntentFilter().apply {
            addAction(SystemAutomationService.ACTION_TEST_CLICK_SUCCESS)
            addAction(SystemAutomationService.ACTION_TEST_CLICK_TIMEOUT)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        SystemAutomationService.awaitingTestClick.set(true)

        handler.postDelayed({
            if (!finished) {
                AppLog.w(TAG, "Test click timeout")
                finishWithResult(false)
            }
        }, TIMEOUT_MS)

        setContent {
            XiaoHyperCleanerTheme {
                TestScreen()
            }
        }
    }

    private fun onManualClick() {
        AppLog.i(TAG, "manual click (fallback)")
        finishWithResult(true)
    }

    private fun finishWithResult(success: Boolean) {
        if (finished) return
        finished = true
        // НОВОЕ: сохраняем результат для чтения из MainActivity.onResume()
        lastTestResult = success
        SystemAutomationService.awaitingTestClick.set(false)

        val resultIntent = Intent().putExtra(EXTRA_RESULT, success)
        setResult(if (success) RESULT_OK else RESULT_CANCELED, resultIntent)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        SystemAutomationService.awaitingTestClick.set(false)
        super.onDestroy()
        AppLog.i(TAG, "onDestroy")
    }

    @Composable
    private fun TestScreen() {
        DisposableEffect(Unit) {
            onDispose { }
        }

        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.test_click_title),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.test_click_description),
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { onManualClick() },
                    modifier = Modifier
                        .fillMaxSize()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue500)
                ) {
                    Text(
                        text = stringResource(R.string.test_click_button),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}