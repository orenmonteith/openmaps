package com.terrain.explorer.terrain.server

import android.content.res.AssetManager
import com.terrain.explorer.imagery.ImageryProvider
import com.terrain.explorer.terrain.TerrainRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves DEM heightmaps, imagery, and Cesium web assets to the WebView over localhost.
 *
 * Routes:
 *  GET /health
 *  GET /terrain/{level}/{x}/{y}.json
 *  GET /terrain/{level}/{x}/{y}.heights
 *  GET /imagery/{z}/{x}/{y}.jpg
 *  GET /debug
 *  GET /cesium/...  /js/...  /css/...  (from assets/web)
 */
class LocalTileServer(
    private val terrainRepository: TerrainRepository,
    private val imageryProvider: ImageryProvider,
    private val assets: AssetManager,
    port: Int = 0,
) : NanoHTTPD("127.0.0.1", port) {

    private val started = AtomicBoolean(false)

    val boundPort: Int
        get() = listeningPort

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            start(SOCKET_READ_TIMEOUT, false)
        }
    }

    fun shutdown() {
        stop()
        started.set(false)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return text(Response.Status.OK, "").also { addCors(it) }
        }
        val uri = session.uri.substringBefore('?')
        return try {
            when {
                uri == "/health" -> text(Response.Status.OK, "ok")
                uri == "/debug" -> debug()
                uri.startsWith("/terrain/") && uri.endsWith(".heights") -> terrainHeights(uri)
                uri.startsWith("/terrain/") && uri.endsWith(".json") -> terrainMeta(uri)
                uri.startsWith("/imagery/") -> imagery(uri)
                uri == "/" || uri == "/index.html" -> asset("web/index.html", "text/html")
                else -> staticAsset(uri)
            }
        } catch (e: Exception) {
            text(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun staticAsset(uri: String): Response {
        val path = uri.trimStart('/')
        val assetPath = when {
            path.startsWith("cesium/") || path.startsWith("js/") || path.startsWith("css/") -> "web/$path"
            else -> return text(Response.Status.NOT_FOUND, "not found")
        }
        return asset(assetPath, mimeFor(assetPath))
    }

    private fun asset(assetPath: String, mime: String): Response {
        return try {
            val stream = assets.open(assetPath)
            // NanoHTTPD will close the stream.
            newChunkedResponse(Response.Status.OK, mime, stream).also { addCors(it) }
        } catch (_: Exception) {
            text(Response.Status.NOT_FOUND, "missing $assetPath")
        }
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff") || path.endsWith(".woff2") -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun debug(): Response {
        val d = terrainRepository.debug.value
        val json = JSONObject()
            .put("providerId", d.providerId)
            .put("resolutionMeters", if (d.resolutionMeters.isNaN()) JSONObject.NULL else d.resolutionMeters)
            .put("level", d.level)
            .put("cacheHit", d.cacheHit)
            .put("offline", d.offline)
            .put("imagery", imageryProvider.id)
            .put("attribution", imageryProvider.attribution)
        return json(json)
    }

    private fun terrainMeta(uri: String): Response {
        val (level, x, y) = parseTerrain(uri, ".json") ?: return text(Response.Status.BAD_REQUEST, "bad path")
        val tile = runBlocking { terrainRepository.getTile(x, y, level) }
            ?: return text(Response.Status.NOT_FOUND, "no terrain")
        val json = JSONObject()
            .put("x", tile.x)
            .put("y", tile.y)
            .put("level", tile.level)
            .put("width", tile.width)
            .put("height", tile.height)
            .put("west", tile.west)
            .put("south", tile.south)
            .put("east", tile.east)
            .put("north", tile.north)
            .put("providerId", tile.providerId)
            .put("resolutionMeters", tile.resolutionMeters)
            .put("licenseId", tile.licenseId)
        return json(json)
    }

    private fun terrainHeights(uri: String): Response {
        val (level, x, y) = parseTerrain(uri, ".heights") ?: return text(Response.Status.BAD_REQUEST, "bad path")
        val tile = runBlocking { terrainRepository.getTile(x, y, level) }
            ?: return text(Response.Status.NOT_FOUND, "no terrain")
        val buffer = ByteBuffer.allocate(tile.heights.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        tile.heights.forEach { buffer.putFloat(it) }
        val bytes = buffer.array()
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/octet-stream",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).also { addCors(it) }
    }

    private fun imagery(uri: String): Response {
        val parts = uri.removePrefix("/imagery/").removeSuffix(".jpg").removeSuffix(".png").split('/')
        if (parts.size != 3) return text(Response.Status.BAD_REQUEST, "bad imagery path")
        val z = parts[0].toIntOrNull() ?: return text(Response.Status.BAD_REQUEST, "bad z")
        val x = parts[1].toIntOrNull() ?: return text(Response.Status.BAD_REQUEST, "bad x")
        val y = parts[2].toIntOrNull() ?: return text(Response.Status.BAD_REQUEST, "bad y")
        val bytes = runBlocking { imageryProvider.getTile(z, x, y) }
            ?: return text(Response.Status.NOT_FOUND, "no imagery")
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).also { addCors(it) }
    }

    private fun parseTerrain(uri: String, suffix: String): Triple<Int, Int, Int>? {
        val parts = uri.removePrefix("/terrain/").removeSuffix(suffix).split('/')
        if (parts.size != 3) return null
        val level = parts[0].toIntOrNull() ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        return Triple(level, x, y)
    }

    private fun text(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "text/plain", body).also { addCors(it) }

    private fun json(obj: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString()).also { addCors(it) }

    private fun addCors(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "*")
        response.addHeader("Cache-Control", "public, max-age=3600")
    }
}
