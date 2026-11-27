package app.touchai.core.openui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Local lightweight WebView fallbacks matching ChatGPT Android's hybrid strategy:
 * only Math (LaTeX) and HtmlView (interactive canvas/games) use a scoped WebView;
 * all prose, cards, lists, SVG diagrams, charts, and inputs remain 100% native Compose.
 */

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OpenUiMath(element: OpenUiElement, modifier: Modifier = Modifier) {
    val tex = element.props.stringProp("tex").orEmpty()
    if (tex.isBlank()) return
    val inline = element.props.boolProp("inline") == true
    val scheme = MaterialTheme.colorScheme
    val fgHex = String.format("#%06X", 0xFFFFFF and scheme.onSurface.toArgb())
    var measuredHeightDp by remember(tex) { mutableIntStateOf(if (inline) 28 else 48) }

    val escapedTex = remember(tex) {
        tex.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$").replace("</script>", "<\\/script>")
    }
    val escapedFallback = remember(tex) {
        tex.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
    val html = remember(escapedTex, fgHex, inline) {
        """
        <!doctype html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
        <style>
          html, body { margin:0; padding:${if (inline) "2px 4px" else "6px 4px"}; background:transparent; color:$fgHex; font-family:serif; overflow-x:auto; }
          #math { font-size:${if (inline) "15px" else "17px"}; text-align:${if (inline) "left" else "center"}; }
          .fallback { font-family:monospace; font-size:14px; }
        </style>
        </head>
        <body>
          <div id="math" class="fallback">$escapedFallback</div>
          <script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js" onload="renderMath()" onerror="reportHeight()"></script>
          <script>
            function reportHeight() {
              if (window.TouchAiBridge) {
                window.TouchAiBridge.onHeight(Math.ceil(document.body.scrollHeight));
              }
            }
            function renderMath() {
              try {
                var el = document.getElementById('math');
                el.className = '';
                katex.render(`$escapedTex`, el, { displayMode: ${!inline}, throwOnError: false });
              } catch (e) {}
              reportHeight();
            }
            window.addEventListener('load', reportHeight);
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(measuredHeightDp.coerceIn(24, 320).dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeight(px: Int) {
                            post { measuredHeightDp = px.coerceIn(24, 320) }
                        }
                    },
                    "TouchAiBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL("https://cdn.jsdelivr.net/", html, "text/html", "utf-8", null)
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OpenUiHtmlView(element: OpenUiElement, modifier: Modifier = Modifier) {
    val rawHtml = element.props.stringProp("html").orEmpty()
    if (rawHtml.isBlank()) return
    val explicitHeight = element.props.intProp("height")
    val title = element.props.stringProp("title")
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.surface.luminance() < 0.5f
    var autoHeightDp by remember(rawHtml) { mutableIntStateOf(explicitHeight ?: 260) }

    val heightObserverScript = """
        <script>
          (function(){
            function notify(){
              if (window.TouchAiBridge) {
                var h = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
                window.TouchAiBridge.onHeight(Math.ceil(h));
              }
            }
            window.addEventListener('load', function(){ notify(); setTimeout(notify, 250); });
            if (typeof ResizeObserver !== 'undefined' && document.body) {
              new ResizeObserver(notify).observe(document.body);
            }
          })();
        </script>
    """.trimIndent()

    val wrappedHtml = remember(rawHtml, isDark) {
        val baseCss = "<style>html,body{margin:0;padding:0;color-scheme:${if (isDark) "dark" else "light"};}</style>"
        if (rawHtml.contains("</body>", ignoreCase = true)) {
            rawHtml.replace("</body>", "$heightObserverScript</body>", ignoreCase = true)
        } else {
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">$baseCss</head><body>$rawHtml$heightObserverScript</body></html>"
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((explicitHeight ?: autoHeightDp).coerceIn(120, 640).dp)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = false
                            setSupportMultipleWindows(false)
                        }
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onHeight(px: Int) {
                                    if (explicitHeight == null) {
                                        post { autoHeightDp = px.coerceIn(120, 640) }
                                    }
                                }
                            },
                            "TouchAiBridge",
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                        }
                    }
                },
                update = { webView ->
                    if (webView.tag != wrappedHtml) {
                        webView.tag = wrappedHtml
                        webView.loadDataWithBaseURL("https://openui.local/", wrappedHtml, "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}
