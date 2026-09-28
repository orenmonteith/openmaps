package com.terrain.explorer.terrain.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.WebMercator
import com.terrain.explorer.terrain.model.TerrainTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Decodes Mapzen Terrarium PNG tiles (AWS elevation-tiles-prod) into Float32 heightmaps
 * on the Cesium geographic tiling grid.
 *
 * Height (m) = R * 256 + G + B / 256 - 32768
 */
class TerrariumDecoder(
    private val http: OkHttpClient = defaultClient(),
    private val tileUrlTemplate: String = DEFAULT_URL,
) {
    private val pngCache = ConcurrentHashMap<String, Bitmap>()

    suspend fun buildHeightmap(
        x: Int,
        y: Int,
        level: Int,
        providerId: String,
        licenseId: String,
        nominalResolutionMeters: Double,
    ): TerrainTile? = withContext(Dispatchers.IO) {
        val rect = GeographicTiling.rectangle(x, y, level)
        val size = GeographicTiling.HEIGHTMAP_SIZE
        val heights = FloatArray(size * size)
        val zoom = WebMercator.geographicLevelToMercatorZoom(level)

        // Prefetch unique mercator tiles covering this geographic rectangle.
        val corners = listOf(
            rect.north to rect.west,
            rect.north to rect.east,
            rect.south to rect.west,
            rect.south to rect.east,
        )
        var minTX = Int.MAX_VALUE
        var maxTX = Int.MIN_VALUE
        var minTY = Int.MAX_VALUE
        var maxTY = Int.MIN_VALUE
        for ((lat, lon) in corners) {
            val (tx, ty) = WebMercator.tileXY(lat, lon, zoom)
            minTX = minOf(minTX, tx)
            maxTX = maxOf(maxTX, tx)
            minTY = minOf(minTY, ty)
            maxTY = maxOf(maxTY, ty)
        }
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Bitmap?>>()
            for (ty in minTY..maxTY) {
                for (tx in minTX..maxTX) {
                    jobs += async { loadTile(zoom, tx, ty) }
                }
            }
            jobs.awaitAll()
        }

        for (row in 0 until size) {
            val lat = rect.north - (row.toDouble() / (size - 1)) * (rect.north - rect.south)
            for (col in 0 until size) {
                val lon = rect.west + (col.toDouble() / (size - 1)) * (rect.east - rect.west)
                val h = sampleHeight(lat, lon, zoom) ?: return@withContext null
                heights[row * size + col] = h
            }
        }

        val resolution = GeographicTiling.approximateResolutionMeters(x, y, level)
            .coerceAtLeast(nominalResolutionMeters)

        TerrainTile(
            x = x,
            y = y,
            level = level,
            width = size,
            height = size,
            west = rect.west,
            south = rect.south,
            east = rect.east,
            north = rect.north,
            heights = heights,
            providerId = providerId,
            resolutionMeters = resolution,
            licenseId = licenseId,
        )
    }

    private fun sampleHeight(lat: Double, lon: Double, zoom: Int): Float? {
        val (px, py) = WebMercator.latLonToPixel(lat, lon, zoom)
        val tileX = floor(px / 256.0).toInt()
        val tileY = floor(py / 256.0).toInt()
        val localX = (px - tileX * 256.0).toInt().coerceIn(0, 255)
        val localY = (py - tileY * 256.0).toInt().coerceIn(0, 255)
        val bmp = loadTile(zoom, tileX, tileY) ?: return null
        if (localX >= bmp.width || localY >= bmp.height) return null
        val pixel = bmp.getPixel(localX, localY)
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (r * 256.0 + g + b / 256.0 - 32768.0).toFloat()
    }

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        pngCache[key]?.let { return it }
        val url = tileUrlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (pngCache.size > 96) {
                    val first = pngCache.keys.firstOrNull()
                    if (first != null) {
                        pngCache.remove(first)?.recycle()
                    }
                }
                pngCache[key] = bmp
                bmp
            }
        } catch (_: IOException) {
            null
        }
    }

    fun clearMemoryCache() {
        pngCache.values.forEach { it.recycle() }
        pngCache.clear()
    }

    companion object {
        const val DEFAULT_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        const val USER_AGENT = "TerrainExplorer/0.1 (offline-first Android terrain; open-data)"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
