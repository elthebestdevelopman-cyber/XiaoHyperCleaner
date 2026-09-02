package com.xiaohypercleaner.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import com.xiaohypercleaner.util.AppLog

/**
 * Activity для отображения веб-страниц внутри приложения.
 *
 * ИСПОЛЬЗУЕТСЯ ДЛЯ:
 * - Платёжных страниц донатов (ЮMoney, CloudTips)
 * - Политики конфиденциальности (через openUrl, не WebView)
 *
 * ЗАПУСК:
 * - Через UiActions.openWebView(context, url, title)
 * - Напрямую через Intent с EXTRA_URL и EXTRA_TITLE
 *
 * БЕЗОПАСНОСТЬ:
 * - Белый список хостов (только yoomoney.ru, pay.cloudtips.ru)
 * - Только HTTPS (HTTP блокируется)
 * - Все внешние ссылки открываются во внешнем браузере
 * - JavaScript включён (необходим для платёжных страниц)
 *
 * АРХИТЕКТУРА:
 * - ComponentActivity + Compose (WebView через AndroidView)
 * - TopAppBar с кнопкой "Назад"
 * - BackHandler для навигации внутри WebView (история)
 *
 * УЛУЧШЕНИЯ:
 * 1. TAG публичный в companion object (для использования в WebViewScreen)
 * 2. Русские логи для соответствия правилу 1
 * 3. Явные типы для всех переменных
 * 4. Полная документация
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        // ИСПРАВЛЕНО: убран private — теперь TAG доступен как WebViewActivity.TAG
        // для использования в top-level composable функции WebViewScreen.
        // Внутри класса используется напрямую как TAG.
        const val TAG = "WebViewActivity"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url: String? = intent.getStringExtra(EXTRA_URL)
        val title: String = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // Не доверяем любым внешним URL — только белый список
        if (url == null || !isAllowedUrl(url)) {
            AppLog.w(TAG, "Заблокирован недоверенный URL: $url")
            finish()
            return
        }

        AppLog.i(TAG, "Открытие WebView: $url")

        setContent {
            XiaoHyperCleanerTheme(darkTheme = isSystemInDarkTheme()) {
                WebViewScreen(
                    title = title,
                    url = url,
                    onBack = { finish() }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Белый список и валидация URL
// ═══════════════════════════════════════════════════════════════

/**
 * Белый список хостов, которые разрешено открывать внутри WebView.
 * Это платёжные страницы донатов. Всё остальное — во внешний браузер.
 * Это закрывает XSS-вектор, о котором предупреждает Lint.
 */
private val ALLOWED_HOSTS: List<String> = listOf(
    "yoomoney.ru",
    "pay.cloudtips.ru"
)

/**
 * Проверяет, разрешён ли URL для открытия внутри WebView.
 *
 * Критерии:
 * - Схема должна быть HTTPS
 * - Хост должен быть в белом списке (точное совпадение или поддомен)
 *
 * @param url URL для проверки
 * @return true, если URL разрешён
 */
private fun isAllowedUrl(url: String): Boolean {
    val uri = url.toUri()
    if (uri.scheme != "https") return false
    val host: String = uri.host ?: return false
    return ALLOWED_HOSTS.any { allowedHost ->
        host == allowedHost || host.endsWith(".$allowedHost")
    }
}

// ═══════════════════════════════════════════════════════════════
// Composable экран
// ═══════════════════════════════════════════════════════════════

/**
 * Composable экран WebView с TopAppBar и навигацией.
 *
 * @param title Заголовок для TopAppBar
 * @param url URL для загрузки
 * @param onBack Callback при нажатии кнопки назад
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
// JS необходим платёжным страницам (ЮMoney / CloudTips).
// Безопасность обеспечивается не отключением JS, а связкой:
// HTTPS + белый список хостов + внешние ссылки во внешний браузер.
@Composable
private fun WebViewScreen(title: String, url: String, onBack: () -> Unit) {
    var webView: WebView? by remember { mutableStateOf(null) }

    BackHandler {
        val wv: WebView? = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url ?: return true

                            // Внутри WebView — только HTTPS и белый список,
                            // всё остальное открываем во внешнем браузере
                            return if (isAllowedUrl(target.toString())) {
                                false // Загружаем внутри WebView
                            } else {
                                try {
                                    view?.context?.startActivity(
                                        Intent(Intent.ACTION_VIEW, target)
                                    )
                                    // ИСПРАВЛЕНО: явная ссылка WebViewActivity.TAG
                                    // (TAG недоступен напрямую из top-level функции)
                                    AppLog.i(
                                        WebViewActivity.TAG,
                                        "Внешняя ссылка открыта в браузере: $target"
                                    )
                                } catch (e: Exception) {
                                    AppLog.w(
                                        WebViewActivity.TAG,
                                        "Не удалось открыть внешнюю ссылку: ${e.message}"
                                    )
                                }
                                true // Не загружаем внутри WebView
                            }
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            }
        )
    }
}