# OpenMaps

Worldwide, offline-first **3D terrain** for Android: real DEM elevation meshes with satellite or topographic imagery draped on the surface — not hillshade.

## Features

- **Map modes** (shared camera position when switching):
  - **Flat** — 2D OpenStreetMap
  - **3D Sat** — satellite draped on DEM (Fatmap-style scout)
  - **3D Topo** — OpenTopoMap (OSM contours) draped on DEM
- **Deferred DEM mesh** — smooth ellipsoid globe until you get close enough for contours (~42 km AGL); then terrain engages
- **Download area** — cache DEM + satellite + OSM + topo tiles for ~18 km around the current view for offline use
- Pluggable DEM providers — USGS 3DEP (US) + global Terrarium/SRTM-class baseline (phone-safe max L12)
- Stable worldwide **Esri satellite** layer (max Z 19) — SSE/prefetch sharpen when close
- CesiumJS WebView renderer with orbit / tilt / zoom / pan
- GPS on-mesh marker + locate / fly-to + place search (Nominatim)
- Cache-first disk store; battery-aware rendering

## Stack

- Kotlin, Jetpack Compose, Android WebView
- CesiumJS (vendored under `app/src/main/assets/web/cesium`)
- Native tile pipeline: providers → `GeoLodDiskCache` → `LocalTileServer` → Cesium

## Build & run (Android)

Requirements: JDK 17+, Android SDK 35, device or emulator (API 26+).

```bash
# local.properties must point at your SDK, e.g.
# sdk.dir=/path/to/Android/sdk

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant location permission to use **Locate me**. Use the mode chips (Flat / 3D Sat / 3D Topo) and the download FAB to pack the current area offline.

## Browser preview (terrain engine)

Same Cesium assets + Terrarium DEM + imagery/basemap APIs as the Android local server.
Requires a browser with **WebGL**.

```bash
cd tools/preview-server
npm install
npm start
# open http://127.0.0.1:43123 in a local Chrome/Firefox tab
```

## Architecture

See `docs/DEM_LICENSES.md` for dataset licensing.

```
Android app
  ├─ GPS (FusedLocationProvider)
  ├─ OfflinePackDownloader (region cache)
  └─ WebView ← LocalTileServer (127.0.0.1)
                 ├─ TerrainRepository (cache-first DEM)
                 ├─ ImageryProvider (Esri / USGS)
                 ├─ OSM + OpenTopoMap basemaps
                 └─ /offline/pack status API
```

## What this is not

No trails, routing, peaks, weather, accounts, or AI. Terrain engine first.

## License notes

Open DEM/imagery/OSM terms are summarized in `docs/DEM_LICENSES.md`. Do not redistribute bulk tile corpora inside the APK.
