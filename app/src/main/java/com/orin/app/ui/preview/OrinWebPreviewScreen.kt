@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.preview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

data class PreviewTarget(
    val title: String,
    val url: String? = null,
    val html: String? = null,
)

/**
 * In-app WebView sandbox for localhost dev servers or rendered HTML/Mermaid artifacts.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OrinWebPreviewScreen(
    target: PreviewTarget,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(target.title) },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                    }
                    if (!target.url.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                runCatching {
                                    CustomTabsIntent.Builder().build()
                                        .launchUrl(context, Uri.parse(target.url))
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Open in browser")
                        }
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url ?: return false
                            val host = u.host.orEmpty()
                            return !(host == "localhost" || host == "127.0.0.1" || u.scheme == "data")
                        }
                    }
                    when {
                        !target.url.isNullOrBlank() -> loadUrl(target.url)
                        !target.html.isNullOrBlank() -> loadDataWithBaseURL(null, target.html, "text/html", "UTF-8", null)
                    }
                    webViewRef = this
                }
            },
        )
    }
}
