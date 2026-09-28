package com.terrain.explorer

import android.Manifest
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.webkit.WebView
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.ui.TerrainWebView
import com.terrain.explorer.ui.flyTo
import com.terrain.explorer.ui.resetNorth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingLocate: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            pendingLocate?.invoke()
        }
        pendingLocate = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as TerrainApp
        app.tileServer.ensureStarted()

        setContent {
            TerrainTheme {
                TerrainScreen(
                    app = app,
                    lowPower = isLowPower(),
                    onRequestLocate = { locate ->
                        val facade = app.locationFacade
                        if (facade.hasPermission()) {
                            locate()
                        } else {
                            pendingLocate = locate
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun isLowPower(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct in 1..20
    }
}

private val Deep = Color(0xFF0B1C24)
private val Accent = Color(0xFF2BB673)
private val Panel = Color(0xCC0E2430)
private val Ink = Color(0xFFE6F2EC)

@Composable
private fun TerrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Deep,
            surface = Panel,
            onPrimary = Deep,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

@Composable
private fun TerrainScreen(
    app: TerrainApp,
    lowPower: Boolean,
    onRequestLocate: (locate: () -> Unit) -> Unit,
) {
    var debug by remember { mutableStateOf(TerrainDebugInfo()) }
    var showDebug by remember { mutableStateOf(true) }
    var engineReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Starting terrain engine…") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(engineReady) {
        if (engineReady) status = "Explore worldwide 3D terrain"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Deep),
    ) {
        TerrainWebView(
            tileServerPort = app.tileServer.boundPort,
            lowPower = lowPower,
            onDebug = { debug = it },
            onReady = { engineReady = true },
            webViewRef = { webView = it },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "TERRAIN EXPLORER",
                color = Ink,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                letterSpacing = 1.sp,
            )
            Text(
                text = status,
                color = Ink.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )

            if (showDebug) {
                DebugPanel(debug = debug, cacheMb = app.cache.usageBytes() / (1024.0 * 1024.0))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = { showDebug = !showDebug },
                containerColor = Panel,
                contentColor = Ink,
            ) {
                Icon(Icons.Default.Info, contentDescription = "Toggle debug")
            }
            FloatingActionButton(
                onClick = { webView?.resetNorth() },
                containerColor = Panel,
                contentColor = Ink,
            ) {
                Icon(Icons.Default.Explore, contentDescription = "Reset north")
            }
            FloatingActionButton(
                onClick = {
                    onRequestLocate {
                        scope.launch {
                            status = "Locating…"
                            val pos = app.locationFacade.currentPosition(highAccuracy = true)
                            if (pos != null) {
                                webView?.flyTo(pos.latitude, pos.longitude)
                                status = "Located ${"%.4f".format(pos.latitude)}, ${"%.4f".format(pos.longitude)}"
                            } else {
                                status = "Location unavailable"
                            }
                        }
                    }
                },
                containerColor = Accent,
                contentColor = Deep,
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Locate me")
            }
        }

        Text(
            text = app.imageryProvider.attribution,
            color = Ink.copy(alpha = 0.65f),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .fillMaxWidth(0.7f),
        )
    }
}

@Composable
private fun DebugPanel(debug: TerrainDebugInfo, cacheMb: Double) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("DEM DEBUG", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Source", debug.providerId)
                DebugItem(
                    "Resolution",
                    if (debug.resolutionMeters.isNaN()) "—" else "${"%.0f".format(debug.resolutionMeters)} m",
                )
                DebugItem("LOD", if (debug.level < 0) "—" else debug.level.toString())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Cache", if (debug.cacheHit) "hit" else "miss")
                DebugItem("Mode", if (debug.offline) "offline" else "online")
                DebugItem("Disk", "${"%.0f".format(cacheMb)} MB")
            }
        }
    }
}

@Composable
private fun DebugItem(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(label, color = Ink.copy(alpha = 0.6f), fontSize = 10.sp)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
