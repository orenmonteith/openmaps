package com.terrain.explorer

import android.Manifest
import android.os.BatteryManager
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.terrain.explorer.search.GeocodeResult
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.ui.TerrainWebView
import com.terrain.explorer.ui.flyTo
import com.terrain.explorer.ui.resetNorth
import com.terrain.explorer.ui.showUserLocation
import kotlinx.coroutines.launch
import kotlin.math.max

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
        app.tileServer.setPrefetchEnabled(!isLowPower())

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
    var showDebug by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Deep),
    ) {
        TerrainWebView(
            tileServerPort = app.tileServer.boundPort,
            lowPower = lowPower,
            onDebug = { debug = it },
            onReady = { },
            webViewRef = { webView = it },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                onSearch = {
                    scope.launch {
                        results = app.geocoder.search(query)
                    }
                },
            )

            if (results.isNotEmpty()) {
                Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        results.take(5).forEach { r ->
                            Text(
                                text = r.displayName,
                                color = Ink,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        webView?.flyTo(
                                            r.latitude,
                                            r.longitude,
                                            flyHeightForPlace(r.displayName),
                                        )
                                        results = emptyList()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

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
                            val pos = app.locationFacade.currentPosition(highAccuracy = true)
                            if (pos != null) {
                                webView?.showUserLocation(pos.latitude, pos.longitude)
                                webView?.flyTo(pos.latitude, pos.longitude, 2500.0)
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

/** Mountain ranges / countries need more altitude so draped imagery can fill the view. */
private fun flyHeightForPlace(name: String): Double {
    val n = name.lowercase()
    return when {
        listOf("alps", "himalaya", "andes", "rockies", "pyrenees", "cascade", "range")
            .any { it in n } -> 45000.0
        listOf("ocean", "sea", "desert", "continent", "country", "republic", "kingdom")
            .any { it in n } -> 120000.0
        listOf("national park", "park", "forest", "mountain", "peak", "mount ")
            .any { it in n } -> 18000.0
        else -> 8000.0
    }.let { max(it, 2500.0) }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search places", color = Ink.copy(alpha = 0.5f)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Accent) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Ink.copy(alpha = 0.35f),
            cursorColor = Accent,
            focusedContainerColor = Panel,
            unfocusedContainerColor = Panel,
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun DebugPanel(debug: TerrainDebugInfo, cacheMb: Double) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("DEBUG", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("DEM", debug.providerId)
                DebugItem(
                    "DEM m",
                    if (debug.resolutionMeters.isNaN()) "—" else "${"%.0f".format(debug.resolutionMeters)}",
                )
                DebugItem("LOD", if (debug.level < 0) "—" else debug.level.toString())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Imagery", debug.imagerySource)
                DebugItem("Img Z", if (debug.imageryZ < 0) "—" else debug.imageryZ.toString())
                DebugItem("SSE", debug.sse)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Scale", debug.resolutionScale)
                DebugItem("Cache", if (debug.cacheHit) "hit" else "miss")
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
