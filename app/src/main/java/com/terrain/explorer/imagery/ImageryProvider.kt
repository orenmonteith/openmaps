package com.terrain.explorer.imagery

import com.terrain.explorer.terrain.cache.GeoLodDiskCache
import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.GeoRectangle
import com.terrain.explorer.terrain.model.TileKey
import com.terrain.explorer.terrain.provider.TerrariumDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.pow

interface ImageryProvider {
    val id: String
    val attribution: String
    val maxZoom: Int
    fun getCoverage(): Coverage
    /** Higher wins when both cover the tile and requested zoom is in range. */
    val priority: Int
    suspend fun getTile(z: Int, x: Int, y: Int): ByteArray?
}

/**
 * Esri World Imagery via URL template, cached on disk through [GeoLodDiskCache].
 */
class EsriWorldImageryProvider(
    private val cache: GeoLodDiskCache,
    private val http: OkHttpClient = defaultHttp(),
    private val urlTemplate: String = DEFAULT_TEMPLATE,
) : ImageryProvider {

    override val id: String = ID
    override val attribution: String =
        "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
    override val maxZoom: Int = 19
    override val priority: Int = 10
    override fun getCoverage(): Coverage = Coverage.global()

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (z > maxZoom) return@withContext null
        fetchCached(cache, http, id, urlTemplate, z, x, y, yxOrder = true)
    }

    companion object {
        const val ID = "esri-world-imagery"
        const val DEFAULT_TEMPLATE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    }
}

/**
 * USGS Imagery Only basemap — sharper CONUS aerial/satellite at high zooms.
 * Used as a regional override when the camera requests fine imagery tiles.
 */
class UsgsImageryProvider(
    private val cache: GeoLodDiskCache,
    private val http: OkHttpClient = defaultHttp(),
) : ImageryProvider {

    override val id: String = ID
    override val attribution: String =
        "USGS Imagery — data available from U.S. Geological Survey, National Geospatial Program."
    override val maxZoom: Int = 18
    override val priority: Int = 100
    override fun getCoverage(): Coverage = Coverage(
        rectangles = listOf(
            GeoRectangle(24.0, -125.0, 49.5, -66.0),
            GeoRectangle(51.0, -180.0, 72.0, -129.0),
            GeoRectangle(18.5, -161.0, 22.5, -154.0),
        ),
    )

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        // Prefer USGS only when close enough that Esri parents look soft.
        if (z < 14 || z > maxZoom) return@withContext null
        val (lat, lon) = tileCenter(z, x, y)
        if (!getCoverage().contains(lat, lon)) return@withContext null
        fetchCached(cache, http, id, TEMPLATE, z, x, y, yxOrder = true)
    }

    companion object {
        const val ID = "usgs-imagery"
        const val TEMPLATE =
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"
    }
}

class ImageryProviderSelector(
    private val providers: List<ImageryProvider>,
) : ImageryProvider {
    @Volatile
    var lastSourceId: String = providers.firstOrNull()?.id ?: "none"
        private set

    override val id: String = "imagery-selector"
    override val attribution: String =
        providers.joinToString(" · ") { it.attribution }.ifBlank { "Imagery" }
    override val maxZoom: Int = providers.maxOfOrNull { it.maxZoom } ?: 19
    override val priority: Int = 0
    override fun getCoverage(): Coverage = Coverage.global()

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        val (lat, lon) = tileCenter(z, x, y)
        val ranked = providers
            .filter { it.getCoverage().contains(lat, lon) && z <= it.maxZoom }
            .sortedByDescending { it.priority }
        for (provider in ranked) {
            val bytes = runCatching { provider.getTile(z, x, y) }.getOrNull()
            if (bytes != null) {
                lastSourceId = provider.id
                return bytes
            }
        }
        lastSourceId = "none"
        return null
    }
}

internal fun defaultHttp(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

internal suspend fun fetchCached(
    cache: GeoLodDiskCache,
    http: OkHttpClient,
    providerId: String,
    urlTemplate: String,
    z: Int,
    x: Int,
    y: Int,
    yxOrder: Boolean,
): ByteArray? {
    val key = TileKey("imagery", providerId, z, x, y)
    cache.getBytes(key)?.let { return it }
    val url = if (yxOrder) {
        urlTemplate
            .replace("{z}", z.toString())
            .replace("{y}", y.toString())
            .replace("{x}", x.toString())
    } else {
        urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }
    return try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", TerrariumDecoder.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            cache.putBytes(key, bytes)
            bytes
        }
    } catch (_: Exception) {
        null
    }
}

/** Web Mercator tile center in WGS84. */
fun tileCenter(z: Int, x: Int, y: Int): Pair<Double, Double> {
    val n = 2.0.pow(z)
    val lon = x / n * 360.0 - 180.0 + 180.0 / n
    val latRad = kotlin.math.atan(kotlin.math.sinh(Math.PI * (1.0 - 2.0 * (y + 0.5) / n)))
    val lat = Math.toDegrees(latRad)
    return lat to lon
}

fun latLonToTile(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
    val n = 2.0.pow(z)
    val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, (1 shl z) - 1)
    val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
    val y = floor(
        (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n,
    ).toInt().coerceIn(0, (1 shl z) - 1)
    return x to y
}
