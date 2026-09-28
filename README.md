# OpenMaps

Worldwide, offline-first **3D terrain** for Android: real DEM elevation meshes with satellite imagery draped on the surface — not hillshade.

## Features (MVP)

- Pluggable DEM providers (`TerrainDataProvider`) — USGS 3DEP (US) + global Terrarium/SRTM-class baseline
- Distance-based **imagery LOD** — high-z satellite near the camera, cheaper tiles when far
- Imagery selector: Esri worldwide + USGS Imagery override in the US at high zoom
- Near-frustum imagery prefetch (paused on low battery / offline)
- CesiumJS WebView renderer with orbit / tilt / zoom / pan
- GPS on-mesh marker + locate / fly-to
- Place search (Nominatim) and OSM trail overlays (hike / bike / ski / explore)
- Cache-first disk store for DEM + imagery (visited areas work offline)
- Battery-aware rendering (`requestRenderMode`, FPS caps, WebView pause, low-power resolution scale)
- Debug HUD: DEM, imagery source/Z, SSE, cache

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

Grant location permission to use **Locate me**.

## Browser preview (terrain engine)

Same Cesium assets + Terrarium DEM + Esri imagery API as the Android local server:

```bash
cd tools/preview-server
npm install
npm start
# open http://127.0.0.1:43123
```

## Architecture

See `docs/DEM_LICENSES.md` for dataset licensing and `docs/VERIFICATION.md` for multi-continent QA.

```
Android app
  ├─ GPS (FusedLocationProvider)
  └─ WebView ← LocalTileServer (127.0.0.1)
                 ├─ TerrainRepository (cache-first)
                 │    └─ ProviderSelector → USGS / Global / Regional stub
                 └─ ImageryProvider (Esri World Imagery)
```

## What this is not

No trails, routing, peaks, weather, accounts, or AI. Terrain engine first.

## License notes

Open DEM/imagery terms are summarized in `docs/DEM_LICENSES.md`. Do not redistribute bulk DEM corpora inside the APK.
