package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.terrain.model.TerrainTile

/**
 * Chooses a DEM provider for a geographic tile.
 * Prefers fast Terrarium/global first so Android never stalls on slow APIs;
 * regional providers can still win when they return a tile quickly.
 */
class ProviderSelector(
    private val registry: ProviderRegistry,
) {
    @Volatile
    var lastDebug: TerrainDebugInfo = TerrainDebugInfo()
        private set

    suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? {
        val (lat, lon) = GeographicTiling.center(x, y, level)
        val candidates = registry.all()
            .filter { it.getCoverage().contains(lat, lon) && level <= it.getMetadata().maxLevel }
            .mapNotNull { provider ->
                val res = provider.getResolutionMeters(lat, lon, level) ?: return@mapNotNull null
                Ranked(provider, res)
            }
            // Fast global baseline first; then finer regional. Never block the phone
            // behind a single slow "best resolution" provider.
            .sortedWith(
                compareBy<Ranked> {
                    when (it.provider.id) {
                        GlobalDemProvider.ID -> 0
                        Usgs3depProvider.ID -> 1
                        else -> 2
                    }
                }.thenBy { it.resolutionMeters }
                    .thenByDescending { it.provider.priority },
            )

        for (ranked in candidates) {
            val tile = runCatching { ranked.provider.getTile(x, y, level) }.getOrNull()
            if (tile != null && tile.heights.isNotEmpty()) {
                lastDebug = TerrainDebugInfo(
                    providerId = tile.providerId,
                    resolutionMeters = tile.resolutionMeters,
                    level = level,
                    cacheHit = false,
                    offline = false,
                )
                return tile
            }
        }

        lastDebug = TerrainDebugInfo(
            providerId = "none",
            resolutionMeters = Double.NaN,
            level = level,
            cacheHit = false,
            offline = false,
        )
        return null
    }

    private data class Ranked(
        val provider: TerrainDataProvider,
        val resolutionMeters: Double,
    )
}
