package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.TerrainDebugInfo
import com.terrain.explorer.terrain.model.TerrainTile

/**
 * Chooses the highest-quality legal DEM provider for a geographic tile,
 * then falls back through regional → global → parent LOD.
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
            .filter { it.getCoverage().contains(lat, lon) }
            .mapNotNull { provider ->
                val res = provider.getResolutionMeters(lat, lon, level) ?: return@mapNotNull null
                Ranked(provider, res)
            }
            .sortedWith(
                compareBy<Ranked> { it.resolutionMeters }
                    .thenByDescending { it.provider.priority },
            )

        for (ranked in candidates) {
            val tile = runCatching { ranked.provider.getTile(x, y, level) }.getOrNull()
            if (tile != null) {
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

        // Parent LOD fallback within the same request chain.
        val parent = GeographicTiling.parent(x, y, level) ?: return null
        val parentTile = getTile(parent.first, parent.second, parent.third) ?: return null
        // Do not upsample fake detail; return null so Cesium uses its own parent geometry.
        // We still record debug from the parent attempt.
        lastDebug = TerrainDebugInfo(
            providerId = parentTile.providerId,
            resolutionMeters = parentTile.resolutionMeters,
            level = parent.third,
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
