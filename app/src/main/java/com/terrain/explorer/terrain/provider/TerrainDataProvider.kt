package com.terrain.explorer.terrain.provider

import com.terrain.explorer.terrain.model.Coverage
import com.terrain.explorer.terrain.model.DemMetadata
import com.terrain.explorer.terrain.model.TerrainTile

interface TerrainDataProvider {
    val id: String
    /** Higher wins when resolutions are similar. */
    val priority: Int

    fun getCoverage(): Coverage

    fun getResolutionMeters(lat: Double, lon: Double, level: Int): Double?

    suspend fun getTile(x: Int, y: Int, level: Int): TerrainTile?

    fun getMetadata(): DemMetadata
}
