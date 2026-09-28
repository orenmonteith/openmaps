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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Decodes Mapzen Terrarium PNG tiles (AWS elevation-tiles-prod) into Float32 heightmaps
 * on the Cesium geographic tiling grid.
 *
 * Height (m) = R * 256 + G + B / 256 - 32768
 *
 * Pixel data is cached as IntArrays (not live Bitmaps) so concurrent tile builds cannot
 * hit a recycled Bitmap via getPixel — that previously aborted on device.
 */
class TerrariumDecoder(
    private val http: OkHttpClient = defaultClient(),
    private val tileUrlTemplate: String = DEFAULT_URL,
) {
    private val cacheMutex = Mutex()
    private val tileCache = object : LinkedHashMap<String, TilePixels>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TilePixels>?): Boolean =
            size > MAX_CACHED_TILES
    }

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
        val nMerc = 1 shl zoom
        for ((lat, lon) in corners) {
            var L = lon
            if (L >= 180.0) L = 179.999999
            if (L < -180.0) L = -180.0
            val (tx, ty) = WebMercator.tileXY(lat, L, zoom)
            minTX = minOf(minTX, tx)
            maxTX = maxOf(maxTX, tx)
            minTY = minOf(minTY, ty)
            maxTY = maxOf(maxTY, ty)
        }
        if (rect.east > 180.0 || rect.west < -180.0 || rect.east - rect.west > 180.0) {
            minTX = 0
            maxTX = nMerc - 1
        }
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<TilePixels?>>()
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

    private suspend fun sampleHeight(lat: Double, lon: Double, zoom: Int): Float? {
        var normalizedLon = lon
        if (normalizedLon > 180.0) normalizedLon -= 360.0
        if (normalizedLon < -180.0) normalizedLon += 360.0
        val (px, py) = WebMercator.latLonToPixel(lat, normalizedLon, zoom)
        // Bilinear sample in pixel space for smoother close-up meshes.
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val fx = (px - x0).toFloat().coerceIn(0f, 1f)
        val fy = (py - y0).toFloat().coerceIn(0f, 1f)
        val h00 = heightAtWorldPixel(x0, y0, zoom) ?: return null
        val h10 = heightAtWorldPixel(x0 + 1, y0, zoom) ?: h00
        val h01 = heightAtWorldPixel(x0, y0 + 1, zoom) ?: h00
        val h11 = heightAtWorldPixel(x0 + 1, y0 + 1, zoom) ?: h10
        val h0 = h00 * (1f - fx) + h10 * fx
        val h1 = h01 * (1f - fx) + h11 * fx
        return h0 * (1f - fy) + h1 * fy
    }

    private suspend fun heightAtWorldPixel(worldX: Int, worldY: Int, zoom: Int): Float? {
        val n = 1 shl zoom
        val tileX = ((worldX.floorDiv(256) % n) + n) % n
        val tileY = worldY.floorDiv(256).coerceIn(0, n - 1)
        val localX = ((worldX % 256) + 256) % 256
        val localY = worldY.coerceIn(0, n * 256 - 1) % 256
        val tile = loadTile(zoom, tileX, tileY) ?: return null
        if (localX >= tile.width || localY >= tile.height) return null
        return decodeTerrarium(tile.pixels[localY * tile.width + localX])
    }

    private fun decodeTerrarium(pixel: Int): Float {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (r * 256.0 + g + b / 256.0 - 32768.0).toFloat()
    }

    private suspend fun loadTile(z: Int, x: Int, y: Int): TilePixels? {
        val n = 1 shl z
        val wx = ((x % n) + n) % n
        val wy = y.coerceIn(0, n - 1)
        val key = "$z/$wx/$wy"

        cacheMutex.withLock {
            tileCache[key]?.let { return it }
        }

        val url = tileUrlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", wx.toString())
            .replace("{y}", wy.toString())
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val w = bmp.width
                val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                // Safe: no other thread holds this Bitmap.
                if (!bmp.isRecycled) bmp.recycle()
                val tile = TilePixels(w, h, pixels)
                cacheMutex.withLock {
                    tileCache[key] = tile
                }
                tile
            }
        } catch (_: IOException) {
            null
        }
    }

    suspend fun clearMemoryCache() {
        cacheMutex.withLock { tileCache.clear() }
    }

    private data class TilePixels(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    companion object {
        const val DEFAULT_URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
        const val USER_AGENT = "OpenMaps/0.1 (offline-first Android terrain; open-data)"
        private const val MAX_CACHED_TILES = 96

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
