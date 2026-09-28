package com.terrain.explorer.imagery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Prefetches higher-z imagery around a camera center into the disk cache.
 * Battery/network aware: callers disable via [setEnabled].
 */
class ImageryPrefetcher(
    private val imagery: ImageryProvider,
) {
    private val enabled = AtomicBoolean(true)
    private val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val semaphore = Semaphore(3)
    private var job: Job? = null

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) job?.cancel()
    }

    /**
     * Prefetch a ring of tiles around [lat]/[lon] at [targetZ] (and one parent level).
     */
    fun prefetchAround(lat: Double, lon: Double, targetZ: Int, radius: Int = 1) {
        if (!enabled.get()) return
        val z = targetZ.coerceIn(8, imagery.maxZoom)
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val tiles = linkedSetOf<Triple<Int, Int, Int>>()
            // Warm parents first so Cesium always has something to drape worldwide.
            for (level in max(z - 2, 6)..z) {
                val (cx, cy) = latLonToTile(lat, lon, level)
                val n = (1 shl level) - 1
                val r = if (level >= z - 1) radius else 1
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        val x = (cx + dx).coerceIn(0, n)
                        val y = (cy + dy).coerceIn(0, n)
                        tiles += Triple(level, x, y)
                    }
                }
            }
            tiles.take(24).forEach { (level, x, y) ->
                if (!enabled.get()) return@launch
                semaphore.withPermit {
                    runCatching { imagery.getTile(level, x, y) }
                }
            }
        }
    }

    fun shutdown() {
        enabled.set(false)
        job?.cancel()
        dispatcher.close()
    }
}
