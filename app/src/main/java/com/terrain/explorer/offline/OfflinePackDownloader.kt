package com.terrain.explorer.offline

import com.terrain.explorer.imagery.ImageryProvider
import com.terrain.explorer.imagery.latLonToTile
import com.terrain.explorer.terrain.GeographicTiling
import com.terrain.explorer.terrain.TerrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class OfflinePackState {
    Idle,
    Running,
    Done,
    Cancelled,
    Error,
}

data class OfflinePackProgress(
    val state: OfflinePackState = OfflinePackState.Idle,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
) {
    val fraction: Float
        get() = if (total <= 0) 0f else done.toFloat() / total.toFloat()
}

/**
 * Downloads DEM + satellite + OSM + topo tiles for a bounding box into the disk cache
 * so a scouted area stays usable offline (Fatmap-style region packs).
 */
class OfflinePackDownloader(
    private val terrainRepository: TerrainRepository,
    private val satellite: ImageryProvider,
    private val osm: ImageryProvider,
    private val topo: ImageryProvider,
) {
    private val enabled = AtomicBoolean(true)
    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val fetchSemaphore = Semaphore(4)
    private var job: Job? = null

    private val _progress = MutableStateFlow(OfflinePackProgress())
    val progress: StateFlow<OfflinePackProgress> = _progress.asStateFlow()

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(
            state = OfflinePackState.Cancelled,
            message = "Cancelled",
        )
    }

    /**
     * Pack a circle of [radiusKm] around [lat]/[lon].
     * Defaults are phone-safe: DEM ≤ L11, sat ≤ Z16, OSM/topo ≤ Z15.
     */
    fun downloadAround(
        lat: Double,
        lon: Double,
        radiusKm: Double = 18.0,
        maxDemLevel: Int = 11,
        maxSatZ: Int = 16,
        maxOsmZ: Int = 15,
        maxTopoZ: Int = 15,
    ) {
        val dLat = radiusKm / 111.32
        val dLon = radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        downloadRegion(
            south = (lat - dLat).coerceIn(-85.0, 85.0),
            west = lon - dLon,
            north = (lat + dLat).coerceIn(-85.0, 85.0),
            east = lon + dLon,
            maxDemLevel = maxDemLevel,
            maxSatZ = maxSatZ,
            maxOsmZ = maxOsmZ,
            maxTopoZ = maxTopoZ,
        )
    }

    fun downloadRegion(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        maxDemLevel: Int = 11,
        maxSatZ: Int = 16,
        maxOsmZ: Int = 15,
        maxTopoZ: Int = 15,
    ) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                val demTiles = planDemTiles(south, west, north, east, maxDemLevel.coerceIn(6, 12))
                val satTiles = planMercatorTiles(south, west, north, east, maxSatZ.coerceIn(8, 17))
                val osmTiles = planMercatorTiles(south, west, north, east, maxOsmZ.coerceIn(8, 16))
                val topoTiles = planMercatorTiles(south, west, north, east, maxTopoZ.coerceIn(8, 16))
                val total = demTiles.size + satTiles.size + osmTiles.size + topoTiles.size
                var done = 0

                fun bump(label: String) {
                    done++
                    _progress.value = OfflinePackProgress(
                        state = OfflinePackState.Running,
                        label = label,
                        done = done,
                        total = total,
                        message = "$done / $total tiles",
                    )
                }

                _progress.value = OfflinePackProgress(
                    state = OfflinePackState.Running,
                    label = "Starting",
                    done = 0,
                    total = total,
                    message = "0 / $total tiles",
                )

                for ((level, x, y) in demTiles) {
                    if (!enabled.get()) break
                    fetchSemaphore.withPermit {
                        runCatching { terrainRepository.getTile(x, y, level) }
                    }
                    bump("DEM L$level")
                }
                for ((z, x, y) in satTiles) {
                    if (!enabled.get()) break
                    fetchSemaphore.withPermit {
                        runCatching { satellite.getTile(z, x, y) }
                    }
                    bump("Satellite Z$z")
                }
                for ((z, x, y) in osmTiles) {
                    if (!enabled.get()) break
                    fetchSemaphore.withPermit {
                        runCatching { osm.getTile(z, x, y) }
                    }
                    bump("OSM Z$z")
                }
                for ((z, x, y) in topoTiles) {
                    if (!enabled.get()) break
                    fetchSemaphore.withPermit {
                        runCatching { topo.getTile(z, x, y) }
                    }
                    bump("Topo Z$z")
                }

                _progress.value = OfflinePackProgress(
                    state = OfflinePackState.Done,
                    label = "Done",
                    done = done,
                    total = total,
                    message = "Cached $done tiles for offline use",
                )
            } catch (e: Exception) {
                _progress.value = OfflinePackProgress(
                    state = OfflinePackState.Error,
                    label = "Error",
                    done = _progress.value.done,
                    total = _progress.value.total,
                    message = e.message ?: "Download failed",
                )
            }
        }
    }

    fun shutdown() {
        enabled.set(false)
        job?.cancel()
        dispatcher.close()
    }

    private fun planDemTiles(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        maxLevel: Int,
    ): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        // Sparse far LODs + denser near max — Fatmap packs prioritize usable scout detail.
        val levels = buildList {
            add(max(0, maxLevel - 6))
            add(max(0, maxLevel - 4))
            add(max(0, maxLevel - 2))
            add(maxLevel)
        }.distinct().sorted()
        for (level in levels) {
            val xTiles = GeographicTiling.xTiles(level)
            val yTiles = GeographicTiling.yTiles(level)
            val minX = ((west + 180.0) / 360.0 * xTiles).toInt().coerceIn(0, xTiles - 1)
            val maxX = ((east + 180.0) / 360.0 * xTiles).toInt().coerceIn(0, xTiles - 1)
            val minY = ((90.0 - north) / 180.0 * yTiles).toInt().coerceIn(0, yTiles - 1)
            val maxY = ((90.0 - south) / 180.0 * yTiles).toInt().coerceIn(0, yTiles - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    out += Triple(level, x, y)
                }
            }
        }
        // Cap DEM fan-out so a pack stays phone-safe (~minutes, not hours).
        return out.take(900)
    }

    private fun planMercatorTiles(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        maxZ: Int,
    ): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val minZ = max(8, maxZ - 4)
        for (z in minZ..maxZ) {
            val (x0, y0) = latLonToTile(north, west, z)
            val (x1, y1) = latLonToTile(south, east, z)
            val minX = min(x0, x1)
            val maxX = max(x0, x1)
            val minY = min(y0, y1)
            val maxY = max(y0, y1)
            // Skip absurd rectangles at high z.
            val count = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
            if (count > 2_500) continue
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    out += Triple(z, x, y)
                }
            }
        }
        return out.take(4_500)
    }
}
