package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.DemMetadata
import com.terrain.explorer.terrain.model.GeoRectangle
import com.terrain.explorer.terrain.model.TerrainTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * US elevation via Terrarium/NED tiles (fast, phone-safe).
 *
 * The USGS ImageServer getSamples path was saturating the Pixel tile server
 * (multi-second / multi-thousand-point requests) so Cesium never refined past
 * multi-km parents. Terrarium over CONUS includes NED heritage and restores
 * real elevation quickly; dense 1 m ImageServer sampling can return later.
 */
class Usgs3depProvider(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val decoder: TerrariumDecoder = TerrariumDecoder(http),
) : TerrainDataProvider {

    override val id: String = ID
    override val priority: Int = 100

    override fun getCoverage(): Coverage = Coverage(
        rectangles = listOf(
            GeoRectangle(24.0, -125.0, 49.5, -66.0),
            GeoRectangle(51.0, -180.0, 72.0, -129.0),
            GeoRectangle(18.5, -161.0, 22.5, -154.0),
            GeoRectangle(17.5, -68.0, 18.6, -65.0),
        ),
    )

    override fun getResolutionMeters(lat: Double, lon: Double, level: Int): Double? {
        if (!getCoverage().contains(lat, lon)) return null
        val tileRes = GeographicTiling.approximateResolutionMeters(
            GeographicTiling.xTiles(level) / 2,
            GeographicTiling.yTiles(level) / 2,
            level,
        )
        // Honest US Terrarium/NED floor (~10 m), not fake 1 m.
        return maxOf(tileRes, NOMINAL_RESOLUTION_M)
    }

    override suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? = withContext(Dispatchers.IO) {
        if (level > getMetadata().maxLevel) return@withContext null
        val (lat, lon) = GeographicTiling.center(x, y, level)
        if (!getCoverage().contains(lat, lon)) return@withContext null

        val tile = decoder.buildHeightmap(
            x = x,
            y = y,
            level = level,
            providerId = id,
            licenseId = "usgs-3dep-via-terrarium-ned",
            nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        ) ?: return@withContext null

        tile.copy(
            resolutionMeters = maxOf(tile.resolutionMeters, NOMINAL_RESOLUTION_M),
        )
    }

    override fun getMetadata(): DemMetadata = DemMetadata(
        datasetName = "USGS/NED via Terrarium (phone-safe path)",
        coverageSummary = "United States and territories",
        nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        sourceUrl = TerrariumDecoder.DEFAULT_URL,
        license = "Public domain USGS NED/3DEP heritage via AWS Terrarium tiles",
        attribution = "Data available from U.S. Geological Survey, National Geospatial Program.",
        commercialUse = "Allowed (public domain components).",
        redistribution = "Runtime fetch + device cache only.",
        apiNotes = "Terrarium PNG decode only — ImageServer getSamples disabled for Android reliability.",
        maxLevel = 12,
    )

    companion object {
        const val ID = "usgs-3dep"
        const val NOMINAL_RESOLUTION_M = 10.0
    }
}
