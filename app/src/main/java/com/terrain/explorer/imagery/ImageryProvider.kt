package com.terrain.explorer.imagery

import com.terrain.explorer.terrain.cache.GeoLodDiskCache
import com.terrain.explorer.terrain.model.TileKey
import com.terrain.explorer.terrain.provider.TerrariumDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

interface ImageryProvider {
    val id: String
    val attribution: String
    suspend fun getTile(z: Int, x: Int, y: Int): ByteArray?
}

/**
 * Esri World Imagery via URL template, cached on disk through [GeoLodDiskCache].
 */
class EsriWorldImageryProvider(
    private val cache: GeoLodDiskCache,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val urlTemplate: String = DEFAULT_TEMPLATE,
) : ImageryProvider {

    override val id: String = ID
    override val attribution: String =
        "Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community"

    override suspend fun getTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        val key = TileKey("imagery", id, z, x, y)
        cache.getBytes(key)?.let { return@withContext it }

        // Esri MapServer tile URL uses z/y/x
        val url = urlTemplate
            .replace("{z}", z.toString())
            .replace("{y}", y.toString())
            .replace("{x}", x.toString())

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", TerrariumDecoder.USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                cache.putBytes(key, bytes)
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ID = "esri-world-imagery"
        const val DEFAULT_TEMPLATE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    }
}
