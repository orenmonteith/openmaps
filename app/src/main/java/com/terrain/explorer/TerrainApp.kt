package com.terrain.explorer

import android.app.Application
import com.terrain.explorer.imagery.EsriWorldImageryProvider
import com.terrain.explorer.imagery.ImageryPrefetcher
import com.terrain.explorer.imagery.ImageryProvider
import com.terrain.explorer.imagery.ImageryProviderSelector
import com.terrain.explorer.imagery.UsgsImageryProvider
import com.terrain.explorer.location.LocationFacade
import com.terrain.explorer.search.GeocoderService
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
    lateinit var imageryProvider: ImageryProvider
        private set
    lateinit var imagerySelector: ImageryProviderSelector
        private set
    lateinit var tileServer: LocalTileServer
        private set
    lateinit var locationFacade: LocationFacade
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var geocoder: GeocoderService
        private set
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var imageryPrefetcher: ImageryPrefetcher

    override fun onCreate() {
        super.onCreate()
        cache = GeoLodDiskCache(this)
        providerRegistry = ProviderRegistry.default()
        val selector = ProviderSelector(providerRegistry)
        terrainRepository = TerrainRepository(cache, selector)
        imagerySelector = ImageryProviderSelector(
            listOf(
                EsriWorldImageryProvider(cache), // worldwide baseline first
                UsgsImageryProvider(cache), // US high-z upgrade only
            ),
        )
        imageryProvider = imagerySelector
        imageryPrefetcher = ImageryPrefetcher(imagerySelector)
        geocoder = GeocoderService()
        locationFacade = LocationFacade(this)
        tileServer = LocalTileServer(
            terrainRepository = terrainRepository,
            imageryProvider = imageryProvider,
            assets = assets,
            imageryPrefetcher = imageryPrefetcher,
            geocoder = geocoder,
        )
        tileServer.ensureStarted()
        networkMonitor = NetworkMonitor(this) { online ->
            terrainRepository.setNetworkEnabled(online)
            tileServer.setPrefetchEnabled(online)
        }
        networkMonitor.start()
    }

    override fun onTerminate() {
        networkMonitor.stop()
        tileServer.shutdown()
        super.onTerminate()
    }
}
