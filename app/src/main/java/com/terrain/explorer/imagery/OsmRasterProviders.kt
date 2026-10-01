package com.terrain.explorer.imagery

import com.terrain.explorer.terrain.cache.GeoLodDiskCache
import com.terrain.explorer.terrain.model.Coverage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * OpenStreetMap standard raster tiles (flat map + offline packs).
 * Respects OSM tile usage policy via app User-Agent and polite concurrency.
 */
class OsmStandardProvider(
    private val cache: GeoLodDiskCache,
    private val http: OkHttpClient = defaultHttp(),
) : ImageryProvider {

    override val id: String = ID
    override val attribution: String =
        "© OpenStreetMap contributors — https://www.openstreetmap.org/copyright"
    override val maxZoom: Int = 19
    override val priority: Int = 5
    override fun getCoverage(): Coverage = Coverage.global()

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (z > maxZoom) return@withContext null
        fetchCached(cache, http, id, TEMPLATE, z, x, y, yxOrder = false)
    }

    companion object {
        const val ID = "osm-standard"
        const val TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    }
}

/**
 * OpenTopoMap — OSM-derived topographic raster with contours / relief shading.
 * Used as the draped layer for 3D Topo mode.
 */
class OpenTopoMapProvider(
    private val cache: GeoLodDiskCache,
    private val http: OkHttpClient = defaultHttp(),
) : ImageryProvider {

    override val id: String = ID
    override val attribution: String =
        "© OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)"
    override val maxZoom: Int = 17
    override val priority: Int = 6
    override fun getCoverage(): Coverage = Coverage.global()

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (z > maxZoom) return@withContext null
        fetchCached(cache, http, id, TEMPLATE, z, x, y, yxOrder = false)
    }

    companion object {
        const val ID = "opentopomap"
        const val TEMPLATE = "https://tile.opentopomap.org/{z}/{x}/{y}.png"
    }
}
