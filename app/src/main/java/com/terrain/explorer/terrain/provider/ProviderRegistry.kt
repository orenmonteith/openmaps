package com.terrain.explorer.terrain.provider

class ProviderRegistry(
    private val providers: List<TerrainDataProvider>,
) {
    fun all(): List<TerrainDataProvider> = providers

    fun byId(id: String): TerrainDataProvider? = providers.find { it.id == id }

    companion object {
        fun default(): ProviderRegistry = ProviderRegistry(
            listOf(
                Usgs3depProvider(),
                GlobalDemProvider(),
                RegionalProviderStub(),
            ),
        )
    }
}
