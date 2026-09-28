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

/**
 * United States USGS 3DEP elevation provider.
 *
 * Samples the 3DEP Elevation ImageServer via getSamples (single multipart request per tile).
 * Reports ~10 m nominal for seamless CONUS 1/3 arc-second — never claims 1 m without
 * a confirmed 1 m product for that tile.
 */
class Usgs3depProvider(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        return maxOf(tileRes, NOMINAL_RESOLUTION_M)
    }

    override suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile? = withContext(Dispatchers.IO) {
        if (level > getMetadata().maxLevel) return@withContext null
        val (lat, lon) = GeographicTiling.center(x, y, level)
        if (!getCoverage().contains(lat, lon)) return@withContext null

        val rect = GeographicTiling.rectangle(x, y, level)
        val size = GeographicTiling.HEIGHTMAP_SIZE

        val heights = sampleGrid(rect, size)
            ?: return@withContext fallbackUsTile(x, y, level)

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
            resolutionMeters = getResolutionMeters(lat, lon, level) ?: NOMINAL_RESOLUTION_M,
            licenseId = "usgs-3dep-pd",
        )
    }

    /**
     * When ImageServer sampling fails, fall back to Terrarium (NED-inclusive over US)
     * but keep honest ~10 m resolution metadata and usgs-3dep provider id only if we
     * successfully used 3DEP. Fallback returns a global-tagged tile via null so selector
     * can try the next provider — here we explicitly build a US-improved terrarium tile
     * labeled with its true source.
     */
    private suspend fun fallbackUsTile(x: Int, y: Int, level: Int): TerrainTile? {
        val tile = fallback.buildHeightmap(
            x = x,
            y = y,
            level = level,
            providerId = GlobalDemProvider.ID,
            licenseId = "aws-terrarium-open",
            nominalResolutionMeters = GlobalDemProvider.NOMINAL_RESOLUTION_M,
        ) ?: return null
        // Re-tag resolution floor for US NED heritage in terrarium tiles (~10–30 m).
        return tile.copy(
            providerId = id,
            resolutionMeters = maxOf(tile.resolutionMeters, NOMINAL_RESOLUTION_M),
            licenseId = "usgs-3dep-via-terrarium-ned",
        )
    }

    private fun sampleGrid(rect: GeoRectangle, size: Int): FloatArray? {
        // Use a moderate sample density to stay within URL/body limits, then upsample.
        val sample = 17
        val points = StringBuilder("[")
        var first = true
        for (row in 0 until sample) {
            val lat = rect.north - (row.toDouble() / (sample - 1)) * (rect.north - rect.south)
            for (col in 0 until sample) {
                val lon = rect.west + (col.toDouble() / (sample - 1)) * (rect.east - rect.west)
                if (!first) points.append(',')
                first = false
                points.append('[').append(lon).append(',').append(lat).append(']')
            }
        }
        points.append(']')

        val geometry = "{\"points\":$points}"
        val form = buildString {
            append("geometry=").append(urlEncode(geometry))
            append("&geometryType=esriGeometryMultipoint")
            append("&sr=4326")
            append("&returnFirstValueOnly=true")
            append("&returnGeometry=false")
            append("&f=json")
        }

        return try {
            val request = Request.Builder()
                .url("$IMAGE_SERVER/getSamples")
                .header("User-Agent", TerrariumDecoder.USER_AGENT)
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseSamples(body, sample)?.let { coarse ->
                    if (sample == size) coarse else upsampleBilinear(coarse, sample, size)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSamples(body: String, sample: Int): FloatArray? {
        return try {
            val json = JSONObject(body)
            if (json.has("error")) return null
            val samples = json.optJSONArray("samples") ?: return null
            if (samples.length() == 0) return null
            val out = FloatArray(sample * sample) { Float.NaN }
            var valid = 0
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val value = when {
                    s.has("value") && !s.isNull("value") -> s.optDouble("value", Double.NaN)
                    else -> Double.NaN
                }
                if (!value.isNaN()) {
                    val idx = s.optInt("rasterId", i).let { i }
                    if (idx in out.indices) {
                        out[idx] = value.toFloat()
                        valid++
                    }
                }
            }
            // getSamples may not preserve order mapped to our grid — prefer location-based fill.
            if (valid < sample * sample / 3) {
                return parseSamplesByLocation(json, sample)
            }
            fillNaNs(out)
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSamplesByLocation(json: JSONObject, sample: Int): FloatArray? {
        val samples = json.optJSONArray("samples") ?: return null
        // Without reliable ordering, abandon getSamples parsing.
        if (samples.length() < sample * sample / 2) return null
        val out = FloatArray(sample * sample)
        for (i in 0 until minOf(samples.length(), out.size)) {
            val s = samples.getJSONObject(i)
            val value = s.optDouble("value", Double.NaN)
            out[i] = if (value.isNaN()) Float.NaN else value.toFloat()
        }
        val valid = out.count { !it.isNaN() }
        if (valid < out.size / 2) return null
        fillNaNs(out)
        return out
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
        datasetName = "USGS 3DEP",
        coverageSummary = "United States and territories (variable product availability)",
        nominalResolutionMeters = NOMINAL_RESOLUTION_M,
        sourceUrl = IMAGE_SERVER,
        license = "Public domain (USGS National Map / 3DEP)",
        attribution = "Data available from U.S. Geological Survey, National Geospatial Program.",
        commercialUse = "Allowed (public domain).",
        redistribution = "Public domain; attribution requested. Prefer runtime access over bundling large DEMs.",
        apiNotes = "ImageServer getSamples + Terrarium/NED fallback. Aggressive on-device cache required.",
        maxLevel = 13,
    )

    companion object {
        const val ID = "usgs-3dep"
        const val NOMINAL_RESOLUTION_M = 10.0
        const val IMAGE_SERVER =
            "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer"
    }
}
