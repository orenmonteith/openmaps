package com.terrain.explorer.terrain.model

/**
 * Normalized terrain tile consumed by the renderer.
 * Heights are meters above the WGS84 ellipsoid / EGM-relative as provided by the source;
 * values are Float32 row-major, north-row first (row 0 = north edge).
 */
data class TerrainTile(
    val x: Int,
    val y: Int,
    val level: Int,
    val width: Int,
    val height: Int,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val heights: FloatArray,
    val providerId: String,
    val resolutionMeters: Double,
    val licenseId: String,
) {
    init {
        require(heights.size == width * height) {
            "heights size ${heights.size} != width*height ${width * height}"
        }
    }
}

data class Coverage(
    val global: Boolean = false,
    val rectangles: List<GeoRectangle> = emptyList(),
) {
    fun contains(lat: Double, lon: Double): Boolean {
        if (global) return true
        val normalizedLon = normalizeLon(lon)
        return rectangles.any { it.contains(lat, normalizedLon) }
    }

    companion object {
        fun global() = Coverage(global = true)

        fun normalizeLon(lon: Double): Double {
            var x = lon % 360.0
            if (x >= 180.0) x -= 360.0
            if (x < -180.0) x += 360.0
            return x
        }
    }
}

data class GeoRectangle(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < south || lat > north) return false
        // Handle dateline-crossing rectangles (west > east).
        return if (west <= east) {
            lon >= west && lon <= east
        } else {
            lon >= west || lon <= east
        }
    }
}

data class DemMetadata(
    val datasetName: String,
    val coverageSummary: String,
    val nominalResolutionMeters: Double?,
    val sourceUrl: String,
    val license: String,
    val attribution: String,
    val commercialUse: String,
    val redistribution: String,
    val apiNotes: String,
    val maxLevel: Int,
)

data class TileKey(
    val kind: String,
    val providerId: String,
    val level: Int,
    val x: Int,
    val y: Int,
) {
    fun pathSegments(): String = "$kind/$providerId/$level/$x/$y"
}

data class TerrainDebugInfo(
    val providerId: String = "none",
    val resolutionMeters: Double = Double.NaN,
    val level: Int = -1,
    val cacheHit: Boolean = false,
    val offline: Boolean = false,
    val imagerySource: String = "—",
    val imageryZ: Int = -1,
    val sse: String = "—",
    val resolutionScale: String = "—",
)
