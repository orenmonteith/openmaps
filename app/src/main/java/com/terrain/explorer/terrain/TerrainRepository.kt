package com.terrain.explorer.terrain

import com.terrain.explorer.terrain.cache.GeoLodDiskCache
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.terrain.model.TerrainTile
import com.terrain.explorer.terrain.model.TileKey
import com.terrain.explorer.terrain.provider.ProviderSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cache-first terrain access used by the local tile server.
 */
class TerrainRepository(
    private val cache: GeoLodDiskCache,
    private val selector: ProviderSelector,
) {
    private val networkEnabled = AtomicBoolean(true)
    private val _debug = MutableStateFlow(TerrainDebugInfo())
    val debug: StateFlow<TerrainDebugInfo> = _debug.asStateFlow()

    fun setNetworkEnabled(enabled: Boolean) {
        networkEnabled.set(enabled)
    }

    suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? {
        // Probe any cached provider variant for this XYZ.
        val cached = findCached(x, y, level)
        if (cached != null) {
            val info = TerrainDebugInfo(
                providerId = cached.providerId,
                resolutionMeters = cached.resolutionMeters,
                level = level,
                cacheHit = true,
                offline = !networkEnabled.get(),
            )
            _debug.value = info
            return cached
        }

        if (!networkEnabled.get()) {
            // Offline: try parent LODs from cache only.
            return parentFromCache(x, y, level)?.also {
                _debug.value = TerrainDebugInfo(
                    providerId = it.providerId,
                    resolutionMeters = it.resolutionMeters,
                    level = it.level,
                    cacheHit = true,
                    offline = true,
                )
            }
        }

        val tile = selector.getTile(x, y, level)
        if (tile != null) {
            cache.putTerrain(tile)
            _debug.value = TerrainDebugInfo(
                providerId = tile.providerId,
                resolutionMeters = tile.resolutionMeters,
                level = level,
                cacheHit = false,
                offline = false,
            )
            return tile
        }

        // Network failure / missing: parent from cache.
        return parentFromCache(x, y, level)?.also {
            _debug.value = TerrainDebugInfo(
                providerId = it.providerId,
                resolutionMeters = it.resolutionMeters,
                level = it.level,
                cacheHit = true,
                offline = false,
            )
        }
    }

    private suspend fun findCached(x: Int, y: Int, level: Int): TerrainTile? {
        val providerIds = listOf("usgs-3dep", "global-terrarium")
        for (id in providerIds) {
            val hit = cache.getTerrain(TileKey("dem", id, level, x, y))
            if (hit != null) return hit
        }
        return null
    }

    private suspend fun parentFromCache(x: Int, y: Int, level: Int): TerrainTile? {
        var cx = x
        var cy = y
        var cl = level
        while (cl > 0) {
            val parent = GeographicTiling.parent(cx, cy, cl) ?: return null
            cx = parent.first
            cy = parent.second
            cl = parent.third
            val hit = findCached(cx, cy, cl)
            if (hit != null) return hit
        }
        return null
    }
}
