package com.terrain.explorer.trails

import com.terrain.explorer.terrain.provider.TerrariumDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class TrailActivity { HIKE, BIKE, SKI, EXPLORE }

/**
 * Fetches OSM trail ways via Overpass and returns GeoJSON for Cesium.
 */
class TrailService(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchTrails(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        activity: TrailActivity,
    ): String = withContext(Dispatchers.IO) {
        val filters = when (activity) {
            TrailActivity.HIKE -> listOf(
                """way["highway"="path"]($south,$west,$north,$east);""",
                """way["highway"="footway"]($south,$west,$north,$east);""",
                """way["route"="hiking"]($south,$west,$north,$east);""",
            )
            TrailActivity.BIKE -> listOf(
                """way["highway"="cycleway"]($south,$west,$north,$east);""",
                """way["route"="mtb"]($south,$west,$north,$east);""",
            )
            TrailActivity.SKI -> listOf(
                """way["piste:type"]($south,$west,$north,$east);""",
                """way["route"="ski"]($south,$west,$north,$east);""",
            )
            TrailActivity.EXPLORE -> listOf(
                """way["highway"="path"]($south,$west,$north,$east);""",
                """way["highway"="footway"]($south,$west,$north,$east);""",
                """way["highway"="cycleway"]($south,$west,$north,$east);""",
            )
        }
        val query = """
            [out:json][timeout:25];
            (
              ${filters.joinToString("\n")}
            );
            out geom qt ${MAX_WAYS};
        """.trimIndent()

        try {
            val alt = Request.Builder()
                .url(OVERPASS)
                .header("User-Agent", TerrariumDecoder.USER_AGENT)
                .post(
                    ("data=" + java.net.URLEncoder.encode(query, "UTF-8"))
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                )
                .build()
            http.newCall(alt).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyFeatureCollection()
                val body = response.body?.string() ?: return@withContext emptyFeatureCollection()
                overpassToGeoJson(body, activity)
            }
        } catch (_: Exception) {
            emptyFeatureCollection()
        }
    }

    private fun overpassToGeoJson(body: String, activity: TrailActivity): String {
        return try {
            val root = JSONObject(body)
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val features = JSONArray()
            for (i in 0 until elements.length()) {
                val el = elements.getJSONObject(i)
                if (el.optString("type") != "way") continue
                val geometry = el.optJSONArray("geometry") ?: continue
                if (geometry.length() < 2) continue
                val coords = JSONArray()
                for (g in 0 until geometry.length()) {
                    val p = geometry.getJSONObject(g)
                    coords.put(JSONArray().put(p.getDouble("lon")).put(p.getDouble("lat")))
                }
                val tags = el.optJSONObject("tags") ?: JSONObject()
                val name = tags.optString("name", tags.optString("ref", "Trail"))
                val props = JSONObject()
                    .put("name", name)
                    .put("highway", tags.optString("highway", ""))
                    .put("surface", tags.optString("surface", ""))
                    .put("activity", activity.name)
                val feature = JSONObject()
                    .put("type", "Feature")
                    .put(
                        "geometry",
                        JSONObject().put("type", "LineString").put("coordinates", coords),
                    )
                    .put("properties", props)
                features.put(feature)
            }
            JSONObject()
                .put("type", "FeatureCollection")
                .put("features", features)
                .toString()
        } catch (_: Exception) {
            emptyFeatureCollection()
        }
    }

    private fun emptyFeatureCollection(): String =
        """{"type":"FeatureCollection","features":[]}"""

    companion object {
        private const val OVERPASS = "https://overpass-api.de/api/interpreter"
        private const val MAX_WAYS = 80
    }
}
