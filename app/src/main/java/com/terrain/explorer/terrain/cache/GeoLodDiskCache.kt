package com.terrain.explorer.terrain.cache

import android.content.Context
import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.model.TerrainTile
import com.terrain.explorer.terrain.model.TileKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Geographic + LOD aware disk cache with LRU eviction.
 * Compatible with future offline packs (same on-disk layout).
 */
class GeoLodDiskCache(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val root = File(context.filesDir, "tile-cache").apply { mkdirs() }
    private val mutex = Mutex()
    private val approximateBytes = AtomicLong(measureInitialSize())

    suspend fun getTerrain(key: TileKey): TerrainTile? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(key)
            if (!file.exists()) return@withLock null
            file.setLastModified(System.currentTimeMillis())
            readTerrain(file)
        }
    }

    suspend fun putTerrain(tile: TerrainTile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = TileKey("dem", tile.providerId, tile.level, tile.x, tile.y)
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            writeTerrain(file, tile)
            approximateBytes.addAndGet(file.length())
            evictIfNeeded()
        }
    }

    suspend fun getBytes(key: TileKey): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(key)
            if (!file.exists()) return@withLock null
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }
    }

    suspend fun putBytes(key: TileKey, bytes: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            approximateBytes.addAndGet(bytes.size.toLong())
            evictIfNeeded()
        }
    }

    fun usageBytes(): Long = approximateBytes.get()

    fun cacheRoot(): File = root

    /**
     * Future offline packs: prefetch a geographic region up to [maxLevel].
     * MVP records the intent; callers may fill tiles through the normal fetch path.
     */
    fun prefetchPlan(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        maxLevel: Int,
    ): List<TileKey> {
        val keys = mutableListOf<TileKey>()
        for (level in 0..maxLevel.coerceAtMost(10)) {
            val xTiles = GeographicTiling.xTiles(level)
            val yTiles = GeographicTiling.yTiles(level)
            val minX = ((west + 180.0) / 360.0 * xTiles).toInt().coerceIn(0, xTiles - 1)
            val maxX = ((east + 180.0) / 360.0 * xTiles).toInt().coerceIn(0, xTiles - 1)
            val minY = ((90.0 - north) / 180.0 * yTiles).toInt().coerceIn(0, yTiles - 1)
            val maxY = ((90.0 - south) / 180.0 * yTiles).toInt().coerceIn(0, yTiles - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    keys += TileKey("dem", "any", level, x, y)
                }
            }
        }
        return keys
    }

    private fun fileFor(key: TileKey): File =
        File(root, "${key.pathSegments()}.bin")

    private fun writeTerrain(file: File, tile: TerrainTile) {
        DataOutputStream(FileOutputStream(file)).use { out ->
            val meta = JSONObject()
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
                .toString()
            val metaBytes = meta.toByteArray(Charsets.UTF_8)
            out.writeInt(metaBytes.size)
            out.write(metaBytes)
            out.writeInt(tile.heights.size)
            for (h in tile.heights) out.writeFloat(h)
        }
    }

    private fun readTerrain(file: File): TerrainTile? {
        return try {
            DataInputStream(FileInputStream(file)).use { input ->
                val metaLen = input.readInt()
                val metaBytes = ByteArray(metaLen)
                input.readFully(metaBytes)
                val meta = JSONObject(String(metaBytes, Charsets.UTF_8))
                val count = input.readInt()
                val heights = FloatArray(count)
                for (i in 0 until count) heights[i] = input.readFloat()
                TerrainTile(
                    x = meta.getInt("x"),
                    y = meta.getInt("y"),
                    level = meta.getInt("level"),
                    width = meta.getInt("width"),
                    height = meta.getInt("height"),
                    west = meta.getDouble("west"),
                    south = meta.getDouble("south"),
                    east = meta.getDouble("east"),
                    north = meta.getDouble("north"),
                    heights = heights,
                    providerId = meta.getString("providerId"),
                    resolutionMeters = meta.getDouble("resolutionMeters"),
                    licenseId = meta.getString("licenseId"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun evictIfNeeded() {
        if (approximateBytes.get() <= maxBytes) return
        val files = root.walkTopDown().filter { it.isFile }.toList()
            .sortedBy { it.lastModified() }
        for (file in files) {
            if (approximateBytes.get() <= maxBytes * 0.85) break
            val len = file.length()
            if (file.delete()) {
                approximateBytes.addAndGet(-len)
            }
        }
    }

    private fun measureInitialSize(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 1_500L * 1024L * 1024L // 1.5 GB
    }
}
