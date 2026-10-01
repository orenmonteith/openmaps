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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.terrain.explorer.offline.OfflinePackState
import com.terrain.explorer.search.GeocodeResult
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.ui.TerrainWebView
import com.terrain.explorer.ui.downloadArea
import com.terrain.explorer.ui.flyTo
import com.terrain.explorer.ui.resetNorth
import com.terrain.explorer.ui.scoutView
import com.terrain.explorer.ui.setMapMode
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
private val ChipIdle = Color(0x66142630)
private val ChipActive = Color(0xFF1E5C42)

private enum class MapModeUi(val id: String, val label: String) {
    FlatOsm("flat-osm", "Flat"),
    Sat3d("sat-3d", "3D Sat"),
    Topo3d("topo-3d", "3D Topo"),
}

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
    var mapMode by remember { mutableStateOf(MapModeUi.Sat3d) }
    val packProgress by app.offlinePacks.progress.collectAsState()
    val scope = rememberCoroutineScope()

    val attribution = when (mapMode) {
        MapModeUi.FlatOsm -> app.osmProvider.attribution
        MapModeUi.Topo3d -> app.topoProvider.attribution
        MapModeUi.Sat3d -> app.imageryProvider.attribution
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
            onReady = { },
            onMapModeChanged = { id ->
                mapMode = MapModeUi.entries.firstOrNull { it.id == id } ?: MapModeUi.Sat3d
            },
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

            ModeSelector(
                selected = mapMode,
                onSelect = { mode ->
                    mapMode = mode
                    webView?.setMapMode(mode.id)
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

            if (packProgress.state == OfflinePackState.Running ||
                packProgress.state == OfflinePackState.Done ||
                packProgress.state == OfflinePackState.Error
            ) {
                OfflinePackBanner(
                    message = packProgress.message.ifBlank { packProgress.label },
                    fraction = packProgress.fraction,
                    running = packProgress.state == OfflinePackState.Running,
                    onCancel = { app.offlinePacks.cancel() },
                )
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
                onClick = {
                    if (packProgress.state != OfflinePackState.Running) {
                        webView?.downloadArea(18.0)
                    }
                },
                containerColor = if (packProgress.state == OfflinePackState.Running) ChipIdle else Panel,
                contentColor = Ink,
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Download area offline")
            }
            FloatingActionButton(
                onClick = { webView?.scoutView() },
                containerColor = Panel,
                contentColor = Ink,
            ) {
                Icon(Icons.Default.Terrain, contentDescription = "Scout view")
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
                                // Close scout altitude for reading nearby lines.
                                webView?.flyTo(pos.latitude, pos.longitude, 1800.0)
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
            text = attribution,
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
private fun ModeSelector(
    selected: MapModeUi,
    onSelect: (MapModeUi) -> Unit,
) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MapModeUi.entries.forEach { mode ->
                val active = mode == selected
                Surface(
                    color = if (active) ChipActive else ChipIdle,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(mode) },
                ) {
                    Text(
                        text = mode.label,
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflinePackBanner(
    message: String,
    fraction: Float,
    running: Boolean,
    onCancel: () -> Unit,
) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (running) "Downloading area…" else "Offline pack",
                    color = Accent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                if (running) {
                    Text(
                        text = "Cancel",
                        color = Ink.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable(onClick = onCancel)
                            .padding(4.dp),
                    )
                }
            }
            Text(text = message, color = Ink, fontSize = 12.sp)
            if (running) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = Accent,
                    trackColor = Ink.copy(alpha = 0.15f),
                )
            }
        }
    }
}

/** Search fly heights biased for ski / mountain scouting, not continent overview. */
private fun flyHeightForPlace(name: String): Double {
    val n = name.lowercase()
    return when {
        listOf(
            "ski", "resort", "glacier", "couloir", "bowl", "pass", "col ",
            "backcountry", "hut", "lodge", "piste",
        ).any { it in n } -> 2400.0
        listOf("peak", "mount ", "mountain", "berg", "horn", "spitze", "aiguille")
            .any { it in n } -> 3200.0
        listOf("alps", "himalaya", "andes", "rockies", "pyrenees", "cascade", "range")
            .any { it in n } -> 22000.0
        listOf("ocean", "sea", "desert", "continent", "country", "republic", "kingdom")
            .any { it in n } -> 120000.0
        listOf("national park", "park", "forest")
            .any { it in n } -> 9000.0
        else -> 3500.0
    }.let { max(it, 900.0) }
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
        placeholder = { Text("Search peaks, resorts, ranges", color = Ink.copy(alpha = 0.5f)) },
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
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("DEBUG", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Mode", debug.mapMode)
                DebugItem("Mesh", if (debug.terrainMesh) "on" else "off")
                DebugItem("DEM", debug.providerId)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem(
                    "DEM m",
                    if (debug.resolutionMeters.isNaN()) "—" else "${"%.0f".format(debug.resolutionMeters)}",
                )
                DebugItem("LOD", if (debug.level < 0) "—" else debug.level.toString())
                DebugItem("Imagery", debug.imagerySource)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DebugItem("Img Z", if (debug.imageryZ < 0) "—" else debug.imageryZ.toString())
                DebugItem("SSE", debug.sse)
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
