package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.DemMetadata
import com.terrain.explorer.terrain.model.GeoRectangle
import com.terrain.explorer.terrain.model.TerrainTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * United States USGS 3DEP elevation provider.
 *
 * Samples the multi-resolution 3DEP Elevation ImageServer (includes ~1 m lidar
 * where published, else 1/3" / 1" seamless). Dense getSamples grids at high LOD
 * aim for DEM m ≈ 1–3 on close ski-scout views inside coverage.
 */
class Usgs3depProvider(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val fallback: TerrariumDecoder = TerrariumDecoder(),
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
        // ImageServer mosaic includes 1 m products; mesh spacing is the other floor.
        return maxOf(tileRes, NOMINAL_RESOLUTION_M)
    }

    override suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? = withContext(Dispatchers.IO) {
        if (level > getMetadata().maxLevel) return@withContext null
        val (lat, lon) = GeographicTiling.center(x, y, level)
        if (!getCoverage().contains(lat, lon)) return@withContext null

        val rect = GeographicTiling.rectangle(x, y, level)
        val size = GeographicTiling.HEIGHTMAP_SIZE

        val heights = sampleGrid(rect, size, level)
            ?: return@withContext fallbackUsTile(x, y, level)

        val meshRes = GeographicTiling.approximateResolutionMeters(x, y, level)
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
            providerId = id,
            resolutionMeters = maxOf(meshRes, NOMINAL_RESOLUTION_M),
            licenseId = "usgs-3dep-pd",
        )
    }

    private suspend fun fallbackUsTile(x: Int, y: Int, level: Int): TerrainTile? {
        val tile = fallback.buildHeightmap(
            x = x,
            y = y,
            level = level,
            providerId = GlobalDemProvider.ID,
            licenseId = "aws-terrarium-open",
            nominalResolutionMeters = GlobalDemProvider.NOMINAL_RESOLUTION_M,
        ) ?: return null
        return tile.copy(
            providerId = id,
            resolutionMeters = maxOf(tile.resolutionMeters, GlobalDemProvider.NOMINAL_RESOLUTION_M),
            licenseId = "usgs-3dep-via-terrarium-ned",
        )
    }

    /**
     * Sample density scales with LOD: coarse tiles stay light; close-up tiles
     * pull near-full 65² grids so 1 m lidar is not destroyed by 17² upsampling.
     */
    private fun sampleSizeForLevel(level: Int): Int = when {
        level >= 16 -> GeographicTiling.HEIGHTMAP_SIZE // 65
        level >= 14 -> 33
        level >= 12 -> 25
        else -> 17
    }

    private fun sampleGrid(rect: GeoRectangle, size: Int, level: Int): FloatArray? {
        val sample = sampleSizeForLevel(level)
        val lons = DoubleArray(sample)
        val lats = DoubleArray(sample)
        for (i in 0 until sample) {
            val t = i.toDouble() / (sample - 1)
            lons[i] = rect.west + t * (rect.east - rect.west)
            lats[i] = rect.north - t * (rect.north - rect.south)
        }

        val points = StringBuilder(sample * sample * 28)
        points.append('[')
        var first = true
        for (row in 0 until sample) {
            for (col in 0 until sample) {
                if (!first) points.append(',')
                first = false
                points.append('[').append(lons[col]).append(',').append(lats[row]).append(']')
            }
        }
        points.append(']')

        val geometry = "{\"points\":$points}"
        val form = buildString {
            append("geometry=").append(urlEncode(geometry))
            append("&geometryType=esriGeometryMultipoint")
            append("&sr=4326")
            append("&returnFirstValueOnly=true")
            append("&returnGeometry=true")
            append("&f=json")
        }

        return try {
            val request = Request.Builder()
                .url("$IMAGE_SERVER/getSamples")
                .header("User-Agent", TerrariumDecoder.USER_AGENT)
                .header("Accept", "application/json")
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val coarse = parseSamplesToGrid(body, sample, lons, lats) ?: return null
                if (sample == size) coarse else upsampleBilinear(coarse, sample, size)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSamplesToGrid(
        body: String,
        sample: Int,
        lons: DoubleArray,
        lats: DoubleArray,
    ): FloatArray? {
        return try {
            val json = JSONObject(body)
            if (json.has("error")) return null
            val samples = json.optJSONArray("samples") ?: return null
            if (samples.length() == 0) return null

            val out = FloatArray(sample * sample) { Float.NaN }
            var valid = 0
            val dLon = abs(lons.last() - lons.first()).coerceAtLeast(1e-9)
            val dLat = abs(lats.first() - lats.last()).coerceAtLeast(1e-9)
            val cellLon = dLon / (sample - 1)
            val cellLat = dLat / (sample - 1)

            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val value = s.optDouble("value", Double.NaN)
                if (value.isNaN()) continue

                val loc = s.optJSONObject("location")
                val idx = if (loc != null) {
                    val lon = loc.optDouble("x", Double.NaN)
                    val lat = loc.optDouble("y", Double.NaN)
                    if (lon.isNaN() || lat.isNaN()) {
                        i
                    } else {
                        val col = ((lon - lons.first()) / cellLon).roundToInt().coerceIn(0, sample - 1)
                        val row = ((lats.first() - lat) / cellLat).roundToInt().coerceIn(0, sample - 1)
                        row * sample + col
                    }
                } else {
                    i
                }
                if (idx in out.indices && out[idx].isNaN()) {
                    out[idx] = value.toFloat()
                    valid++
                }
            }

            if (valid < sample * sample / 4) return null
            // Nearest fill for sparse holes before bilinear upsample.
            fillNaNsNearest(out, sample)
            val stillBad = out.count { it.isNaN() }
            if (stillBad > out.size / 10) return null
            fillNaNs(out)
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun fillNaNsNearest(data: FloatArray, size: Int) {
        val copy = data.copyOf()
        for (row in 0 until size) {
            for (col in 0 until size) {
                val i = row * size + col
                if (!copy[i].isNaN()) continue
                var best = Float.NaN
                var bestD = Double.POSITIVE_INFINITY
                val r0 = (row - 2).coerceAtLeast(0)
                val r1 = (row + 2).coerceAtMost(size - 1)
                val c0 = (col - 2).coerceAtLeast(0)
                val c1 = (col + 2).coerceAtMost(size - 1)
                for (rr in r0..r1) {
                    for (cc in c0..c1) {
                        val v = copy[rr * size + cc]
                        if (v.isNaN()) continue
                        val d = hypot((rr - row).toDouble(), (cc - col).toDouble())
                        if (d < bestD) {
                            bestD = d
                            best = v
                        }
                    }
                }
                if (!best.isNaN()) data[i] = best
            }
        }
    }

    private fun fillNaNs(data: FloatArray) {
        var fallback = 0f
        for (v in data) {
            if (!v.isNaN()) {
                fallback = v
                break
            }
        }
        for (i in data.indices) {
            if (data[i].isNaN()) data[i] = fallback
        }
    }

    private fun upsampleBilinear(src: FloatArray, srcSize: Int, dstSize: Int): FloatArray {
        val dst = FloatArray(dstSize * dstSize)
        val scale = (srcSize - 1).toFloat() / (dstSize - 1).toFloat()
        for (row in 0 until dstSize) {
            val sy = row * scale
            val y0 = sy.toInt().coerceIn(0, srcSize - 1)
            val y1 = (y0 + 1).coerceAtMost(srcSize - 1)
            val fy = sy - y0
            for (col in 0 until dstSize) {
                val sx = col * scale
                val x0 = sx.toInt().coerceIn(0, srcSize - 1)
                val x1 = (x0 + 1).coerceAtMost(srcSize - 1)
                val fx = sx - x0
                val v00 = src[y0 * srcSize + x0]
                val v10 = src[y0 * srcSize + x1]
                val v01 = src[y1 * srcSize + x0]
                val v11 = src[y1 * srcSize + x1]
                val v0 = v00 * (1 - fx) + v10 * fx
                val v1 = v01 * (1 - fx) + v11 * fx
                dst[row * dstSize + col] = v0 * (1 - fy) + v1 * fy
            }
        }
        return dst
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    override fun getMetadata(): DemMetadata = DemMetadata(
        datasetName = "USGS 3DEP (multi-res, 1 m lidar where published)",
        coverageSummary = "United States and territories (1 m lidar patches + seamless 10 m)",
        nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        sourceUrl = IMAGE_SERVER,
        license = "Public domain (USGS National Map / 3DEP)",
        attribution = "Data available from U.S. Geological Survey, National Geospatial Program.",
        commercialUse = "Allowed (public domain).",
        redistribution = "Public domain; attribution requested. Prefer runtime access over bundling large DEMs.",
        apiNotes = "ImageServer getSamples (dense grids at high LOD) + Terrarium fallback.",
        maxLevel = 18,
    )

    companion object {
        const val ID = "usgs-3dep"
        /** Best-case mosaic spacing (1 m lidar). Mesh LOD may be coarser. */
        const val NOMINAL_RESOLUTION_M = 1.0
        const val IMAGE_SERVER =
            "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer"
    }
}
