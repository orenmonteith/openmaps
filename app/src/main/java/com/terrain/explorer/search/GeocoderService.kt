package com.terrain.explorer.search

import com.terrain.explorer.terrain.provider.TerrariumDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class GeocodeResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * OpenStreetMap Nominatim geocoder (usage policy: identify app, cache, no bulk).
 */
class GeocoderService(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun search(query: String, limit: Int = 5): List<GeocodeResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val url =
            "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=$limit"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", TerrariumDecoder.USER_AGENT)
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            GeocodeResult(
                                displayName = o.optString("display_name"),
                                latitude = o.optString("lat").toDoubleOrNull() ?: continue,
                                longitude = o.optString("lon").toDoubleOrNull() ?: continue,
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
