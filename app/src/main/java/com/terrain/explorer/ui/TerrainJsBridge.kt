package com.terrain.explorer.ui

import android.webkit.JavascriptInterface
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import org.json.JSONObject

class TerrainJsBridge(
    private val onDebug: (TerrainDebugInfo) -> Unit,
    private val onReady: () -> Unit,
) {
    @JavascriptInterface
    fun onTerrainDebug(json: String) {
        try {
            val obj = JSONObject(json)
            onDebug(
                TerrainDebugInfo(
                    providerId = obj.optString("providerId", "none"),
                    resolutionMeters = obj.optDouble("resolutionMeters", Double.NaN),
                    level = obj.optInt("level", -1),
                    cacheHit = obj.optBoolean("cacheHit", false),
                    offline = obj.optBoolean("offline", false),
                    imagerySource = obj.optString("imagery", "—"),
                    imageryZ = obj.optInt("imageryZ", -1),
                    sse = obj.optString("sse", "—"),
                    resolutionScale = obj.optString("resolutionScale", "—"),
                ),
            )
        } catch (_: Exception) {
            // ignore malformed debug payloads
        }
    }

    @JavascriptInterface
    fun onEngineReady() {
        onReady()
    }
}
