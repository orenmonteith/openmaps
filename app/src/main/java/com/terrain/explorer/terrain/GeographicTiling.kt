package com.terrain.explorer.terrain

import com.terrain.explorer.terrain.model.GeoRectangle
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Cesium GeographicTilingScheme helpers (WGS84).
 * At level L: X tiles = 2^(L+1), Y tiles = 2^L.
 */
object GeographicTiling {
    const val HEIGHTMAP_SIZE = 65

    fun xTiles(level: Int): Int = 1 shl (level + 1)

    fun yTiles(level: Int): Int = 1 shl level

    fun rectangle(x: Int, y: Int, level: Int): GeoRectangle {
        val nx = xTiles(level).toDouble()
        val ny = yTiles(level).toDouble()
        // Keep west/east in sequential space so dateline-spanning tiles keep west < east
        // in unwrapped coordinates (e.g. 170..190); consumers normalize lon when sampling.
        val west = -180.0 + (x / nx) * 360.0
        val east = -180.0 + ((x + 1) / nx) * 360.0
        val north = 90.0 - (y / ny) * 180.0
        val south = 90.0 - ((y + 1) / ny) * 180.0
        return GeoRectangle(south = south, west = west, north = north, east = east)
    }

    fun center(x: Int, y: Int, level: Int): Pair<Double, Double> {
        val r = rectangle(x, y, level)
        return (r.south + r.north) / 2.0 to (r.west + r.east) / 2.0
    }

    /** Approximate ground resolution (meters/pixel) at tile center for a given sample size. */
    fun approximateResolutionMeters(x: Int, y: Int, level: Int, sampleSize: Int = HEIGHTMAP_SIZE): Double {
        val r = rectangle(x, y, level)
        val lat = (r.south + r.north) / 2.0
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val heightM = (r.north - r.south) * metersPerDegLat
        val widthM = (r.east - r.west) * metersPerDegLon
        return ((widthM + heightM) / 2.0) / (sampleSize - 1).coerceAtLeast(1)
    }

    fun parent(x: Int, y: Int, level: Int): Triple<Int, Int, Int>? {
        if (level <= 0) return null
        return Triple(x / 2, y / 2, level - 1)
    }
}

/** Web Mercator XYZ helpers for Terrarium / imagery tiles. */
object WebMercator {
    private const val MAX_LAT = 85.05112878

    fun clampLat(lat: Double): Double = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun latLonToPixel(lat: Double, lon: Double, zoom: Int, tileSize: Int = 256): Pair<Double, Double> {
        val n = 2.0.pow(zoom)
        val x = (lon + 180.0) / 360.0 * n * tileSize
        val latRad = Math.toRadians(clampLat(lat))
        val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n * tileSize
        return x to y
    }

    fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val (px, py) = latLonToPixel(lat, lon, zoom)
        val n = 1 shl zoom
        val x = floor(px / 256.0).toInt().coerceIn(0, n - 1)
        val y = floor(py / 256.0).toInt().coerceIn(0, n - 1)
        return x to y
    }

    fun tileBounds(x: Int, y: Int, zoom: Int): GeoRectangle {
        val n = 2.0.pow(zoom)
        val west = x / n * 360.0 - 180.0
        val east = (x + 1) / n * 360.0 - 180.0
        val north = mercatorYToLat(y / n)
        val south = mercatorYToLat((y + 1) / n)
        return GeoRectangle(south, west, north, east)
    }

    private fun mercatorYToLat(yNorm: Double): Double {
        val n = PI - 2.0 * PI * yNorm
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /**
     * Map Cesium geographic LOD to a Web Mercator zoom that roughly matches sample spacing.
     * Cap at 14 — Terrarium/SRTM-class detail does not improve meaningfully beyond that,
     * and higher zooms amplify sampling noise ("corruption") when zoomed in.
     */
    fun geographicLevelToMercatorZoom(level: Int): Int {
        // Geographic level 0 ≈ two 180° tiles; Mercator z0 is one world tile.
        return (level + 2).coerceIn(0, 14)
    }
}
