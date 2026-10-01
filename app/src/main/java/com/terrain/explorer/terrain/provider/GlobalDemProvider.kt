package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.DemMetadata
import com.terrain.explorer.terrain.model.TerrainTile

/**
 * Worldwide baseline DEM provider.
 *
 * Runtime tiles are decoded from openly published Terrarium-encoded elevation tiles
 * derived from SRTM / viewfinder / NED-class open datasets (Mapzen / AWS terrain tiles).
 * Nominal finest resolution is ~30 m — never advertised as higher.
 */
class GlobalDemProvider(
    private val decoder: TerrariumDecoder = TerrariumDecoder(),
) : TerrainDataProvider {

    override val id: String = ID
    override val priority: Int = 10

    override fun getCoverage(): Coverage = Coverage.global()

    override fun getResolutionMeters(lat: Double, lon: Double, level: Int): Double {
        val tileRes = GeographicTiling.approximateResolutionMeters(0, 0, level)
        return maxOf(tileRes, NOMINAL_RESOLUTION_M)
    }

    override suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? {
        if (level > getMetadata().maxLevel) return null
        return decoder.buildHeightmap(
            x = x,
            y = y,
            level = level,
            providerId = id,
            licenseId = "aws-terrarium-open",
            nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        )
    }

    override fun getMetadata(): DemMetadata = DemMetadata(
        datasetName = "Global Terrarium DEM (SRTM/NASADEM-class via AWS elevation-tiles)",
        coverageSummary = "Near-global land coverage (~60S–60N SRTM heritage; polar fill varies)",
        nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        sourceUrl = TerrariumDecoder.DEFAULT_URL,
        license = "Source datasets: mixed open licenses (SRTM public domain / NASADEM; viewfinderpanoramas per source). Tile service: AWS Open Data terrain tiles.",
        attribution = "Elevation data derived from open DEM sources via AWS Terrain Tiles (Terrarium encoding).",
        commercialUse = "Generally permitted for open DEM components; verify product-specific terms before redistribution of derived commercial products.",
        redistribution = "Do not bundle the global DEM in the APK. Runtime fetch + local device cache only.",
        apiNotes = "HTTPS PNG Terrarium tiles; no API key. Rate/bandwidth courtesy limits apply.",
        // Mesh may refine past source; resolutionMeters stays ≥ ~30 m (honest).
        maxLevel = 14,
    )

    companion object {
        const val ID = "global-terrarium"
        const val NOMINAL_RESOLUTION_M = 30.0
    }
}
