package com.xiaohypercleaner.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
import com.xiaohypercleaner.ui.theme.XiaoHyperCleanerTheme
import com.xiaohypercleaner.util.AppLog
import androidx.core.net.toUri

private const val TAG = "WebView"

// Белый список хостов, которые разрешено открывать внутри WebView
// (платёжные страницы донатов). Всё остальное — во внешний браузер.
// Это закрывает XSS-вектор, о котором предупреждает Lint.
private val ALLOWED_HOSTS = listOf(
    "yoomoney.ru",
    "pay.cloudtips.ru"
)

private fun isAllowedUrl(url: String): Boolean {
    val uri = url.toUri()
    if (uri.scheme != "https") return false
    val host = uri.host ?: return false
    return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
}

class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // Не доверяем любым внешним URL — только белый список
        if (url == null || !isAllowedUrl(url)) {
            AppLog.w(TAG, "blocked untrusted url: $url")
            finish()
            return
        }

        setContent {
            XiaoHyperCleanerTheme(darkTheme = isSystemInDarkTheme()) {
                WebViewScreen(title = title, url = url, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
// JS необходим платёжным страницам (ЮMoney / CloudTips).
// Безопасность обеспечивается не отключением JS, а связкой:
// HTTPS + белый список хостов + внешние ссылки во внешний браузер.
@Composable
private fun WebViewScreen(title: String, url: String, onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
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
                                false
                            } else {
                                try {
                                    view?.context?.startActivity(
                                        Intent(Intent.ACTION_VIEW, target)
                                    )
                                } catch (_: Exception) {
                                }
                                true
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