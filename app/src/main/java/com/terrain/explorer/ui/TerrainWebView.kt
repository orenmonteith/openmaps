package com.terrain.explorer.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.terrain.explorer.terrain.model.TerrainDebugInfo

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerrainWebView(
    tileServerPort: Int,
    lowPower: Boolean,
    onDebug: (TerrainDebugInfo) -> Unit,
    onReady: () -> Unit,
    webViewRef: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val bridge = remember {
        TerrainJsBridge(onDebug = onDebug, onReady = onReady)
    }

    val webView = remember {
        mutableListOf<WebView>()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor("#1A2420"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Keep WebView at full device DPI so Cesium's canvas isn't pre-scaled soft.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val scale = if (lowPower) 0.7 else 1.0
                        view?.evaluateJavascript(
                            "window.TerrainApp && window.TerrainApp.setBatteryMode($lowPower, $scale);",
                            null,
                        )
                    }
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                // Flag before scripts run so DEM LOD stays phone-safe (L12 max).
                loadUrl("http://127.0.0.1:$tileServerPort/?port=$tileServerPort&android=1")
                webViewRef(this)
                webView.clear()
                webView.add(this)
            }
        },
        update = { view ->
            val scale = if (lowPower) 0.7 else 1.0
            view.evaluateJavascript(
                "window.TerrainApp && window.TerrainApp.setBatteryMode($lowPower, $scale);",
                null,
            )
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = webView.firstOrNull() ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    view.onPause()
                    view.evaluateJavascript("window.TerrainApp && window.TerrainApp.pause();", null)
                }
                Lifecycle.Event.ON_RESUME -> {
                    view.onResume()
                    view.evaluateJavascript("window.TerrainApp && window.TerrainApp.resume();", null)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.firstOrNull()?.apply {
                evaluateJavascript("window.TerrainApp && window.TerrainApp.pause();", null)
                onPause()
            }
        }
    }
}

fun WebView.flyTo(lat: Double, lon: Double, height: Double = 8000.0) {
    evaluateJavascript(
        "window.TerrainApp && window.TerrainApp.flyTo($lat, $lon, $height);",
        null,
    )
}

fun WebView.showUserLocation(lat: Double, lon: Double) {
    evaluateJavascript(
        "window.TerrainApp && window.TerrainApp.showUserLocation($lat, $lon);",
        null,
    )
}

fun WebView.resetNorth() {
    evaluateJavascript("window.TerrainApp && window.TerrainApp.resetNorth();", null)
}

fun WebView.scoutView() {
    evaluateJavascript("window.TerrainApp && window.TerrainApp.scoutView();", null)
}
