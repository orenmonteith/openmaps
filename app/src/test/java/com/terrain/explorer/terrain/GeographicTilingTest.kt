package com.terrain.explorer.terrain

import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.GeoRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeographicTilingTest {
    @Test
    fun levelZeroHasTwoXTiles() {
        assertEquals(2, GeographicTiling.xTiles(0))
        assertEquals(1, GeographicTiling.yTiles(0))
    }

    @Test
    fun rectanglesCoverEarthWithoutGapAtLevel0() {
        val left = GeographicTiling.rectangle(0, 0, 0)
        val right = GeographicTiling.rectangle(1, 0, 0)
        assertEquals(-180.0, left.west, 1e-9)
        assertEquals(0.0, left.east, 1e-9)
        assertEquals(0.0, right.west, 1e-9)
        assertEquals(180.0, right.east, 1e-9)
        assertEquals(90.0, left.north, 1e-9)
        assertEquals(-90.0, left.south, 1e-9)
    }

    @Test
    fun datelineCrossingRectangleContainsBothSides() {
        val rect = GeoRectangle(south = -10.0, west = 170.0, north = 10.0, east = -170.0)
        assertTrue(rect.contains(0.0, 175.0))
        assertTrue(rect.contains(0.0, -175.0))
        assertFalse(rect.contains(0.0, 0.0))
    }

    @Test
    fun coverageNormalizeLon() {
        assertEquals(-170.0, Coverage.normalizeLon(190.0), 1e-9)
        assertEquals(170.0, Coverage.normalizeLon(-190.0), 1e-9)
    }

    @Test
    fun parentHalvesIndices() {
        val parent = GeographicTiling.parent(5, 3, 2)
        assertEquals(Triple(2, 1, 1), parent)
    }
}
