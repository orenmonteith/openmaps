package com.terrain.explorer

import android.app.Application
import com.terrain.explorer.imagery.EsriWorldImageryProvider
import com.terrain.explorer.location.LocationFacade
import com.terrain.explorer.terrain.NetworkMonitor
import com.terrain.explorer.terrain.TerrainRepository
import com.terrain.explorer.terrain.cache.GeoLodDiskCache
import com.terrain.explorer.terrain.provider.ProviderRegistry
import com.terrain.explorer.terrain.provider.ProviderSelector
import com.terrain.explorer.terrain.server.LocalTileServer

class TerrainApp : Application() {
    lateinit var cache: GeoLodDiskCache
        private set
    lateinit var terrainRepository: TerrainRepository
        private set
    lateinit var imageryProvider: EsriWorldImageryProvider
        private set
    lateinit var tileServer: LocalTileServer
        private set
    lateinit var locationFacade: LocationFacade
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate() {
        super.onCreate()
        cache = GeoLodDiskCache(this)
        providerRegistry = ProviderRegistry.default()
        val selector = ProviderSelector(providerRegistry)
        terrainRepository = TerrainRepository(cache, selector)
        imageryProvider = EsriWorldImageryProvider(cache)
        locationFacade = LocationFacade(this)
        tileServer = LocalTileServer(terrainRepository, imageryProvider, assets)
        tileServer.ensureStarted()
        networkMonitor = NetworkMonitor(this) { online ->
            terrainRepository.setNetworkEnabled(online)
        }
        networkMonitor.start()
    }

    override fun onTerminate() {
        networkMonitor.stop()
        tileServer.shutdown()
        super.onTerminate()
    }
}
