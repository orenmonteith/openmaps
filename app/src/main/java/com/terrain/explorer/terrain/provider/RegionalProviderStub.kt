package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.DemMetadata
import com.terrain.explorer.terrain.model.TerrainTile

/**
 * Placeholder for future national/regional high-resolution DEMs
 * (LINZ, NRCan, Geoscience Australia, GSI Japan, European national agencies, etc.).
 *
 * Coverage polygons drive selection — no hardcoded country switch in the renderer.
 * Register concrete implementations with [ProviderRegistry] when licenses and endpoints are ready.
 */
class RegionalProviderStub(
    override val id: String = "regional-stub",
    override val priority: Int = 200,
    private val coverage: Coverage = Coverage(),
) : TerrainDataProvider {

    override fun getCoverage(): Coverage = coverage

    override fun getResolutionMeters(lat: Double, lon: Double, level: Int): Double? = null

    override suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? = null

    override fun getMetadata(): DemMetadata = DemMetadata(
        datasetName = "Regional DEM stub",
        coverageSummary = "None registered",
        nominalResolutionMeters = null,
        sourceUrl = "",
        license = "N/A",
        attribution = "N/A",
        commercialUse = "N/A",
        redistribution = "N/A",
        apiNotes = "Add a provider implementing TerrainDataProvider and register it.",
        maxLevel = 0,
    )
}
