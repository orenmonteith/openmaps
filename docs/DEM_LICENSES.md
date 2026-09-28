# DEM & Imagery License Notes

This document records datasets used by OpenMaps. Runtime fetch + on-device cache only — datasets are **not** bundled in the APK.

## USGS 3DEP

| Field | Value |
| --- | --- |
| Dataset | USGS 3D Elevation Program (3DEP) |
| Coverage | United States and territories (product availability varies) |
| Resolution | Seamless ~1/3 arc-second (~10 m) widely; 1 m lidar-derived products in selected areas (MVP reports ≥10 m unless a confirmed 1 m product is queried) |
| Source | https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer |
| License | Public domain |
| Attribution | Data available from U.S. Geological Survey, National Geospatial Program. |
| Commercial use | Allowed |
| Redistribution | Allowed (public domain); prefer runtime access for large rasters |
| API | ImageServer `getSamples` / EPQS; courtesy rate limits apply |

Provider id: `usgs-3dep`

## Global Terrarium DEM (AWS Terrain Tiles)

| Field | Value |
| --- | --- |
| Dataset | Mapzen / AWS elevation-tiles Terrarium PNGs (SRTM, NED, viewfinderpanoramas heritage) |
| Coverage | Near-global land (~60°S–60°N SRTM core; fills vary at extremes) |
| Resolution | Nominal ~30 m at finest useful LODs — **not** 1 m worldwide |
| Source | `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` |
| License | Mixed open sources (SRTM/NASADEM public domain components; other contributors per Mapzen/AWS notices) |
| Attribution | Elevation data derived from open DEM sources via AWS Terrain Tiles (Terrarium encoding). |
| Commercial use | Generally permitted for open components; verify before redistributing derived commercial products |
| Redistribution | Do not ship the global tile corpus in the app; cache on device after fetch |
| API | Anonymous HTTPS; no key; apply polite concurrency |

Provider id: `global-terrarium`

Encoding: `height = R*256 + G + B/256 - 32768` (meters).

## Copernicus DEM (architecture note)

| Field | Value |
| --- | --- |
| Dataset | Copernicus DEM GLO-30 / GLO-90 |
| Coverage | Global |
| Resolution | 30 m / 90 m |
| Source | Copernicus programme / AWS Open Data mirrors |
| License | Copernicus license — free access with attribution; check current terms for redistribution |
| Status in MVP | Documented as preferred global baseline; Terrarium path used for practical tile streaming. A dedicated `CopernicusDemProvider` can replace/extend `GlobalDemProvider` when a stable COG/tile endpoint is configured. |

## Regional providers (stub)

| Field | Value |
| --- | --- |
| Dataset | Future national/regional DEMs (LINZ, NRCan, GA, GSI, European agencies, …) |
| Coverage | Provider-specific polygons |
| Status | `RegionalProviderStub` registered; no country hardcoding in the renderer |

## Esri World Imagery

| Field | Value |
| --- | --- |
| Dataset | Esri World Imagery |
| Coverage | Global |
| Source | ArcGIS Online World Imagery MapServer tiles |
| License / ToS | Esri terms of use; attribution required |
| Attribution | Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community |
| Redistribution | Do not bulk-mirror; runtime tiles + device cache only |

Imagery provider id: `esri-world-imagery`

## USGS Imagery Only (regional high-res)

| Field | Value |
| --- | --- |
| Dataset | USGS Imagery Only basemap |
| Coverage | United States (strongest in CONUS) |
| Source | https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer |
| License | Public domain USGS; attribution requested |
| Attribution | USGS Imagery — data available from U.S. Geological Survey, National Geospatial Program. |
| Use in OpenMaps | Selected at high zoom (z≥14) when the tile center is in coverage; falls back to Esri worldwide |
| Redistribution | Runtime tiles + device cache only |

Imagery provider id: `usgs-imagery`

## Geocoding (runtime)

| Service | Use | Notes |
| --- | --- | --- |
| Nominatim (OSM) | Place search | Identify with app User-Agent; no bulk geocoding; respect usage policy |

## Honesty policy

The app must never claim a finer DEM resolution than the active provider reports. The debug HUD shows `DEM source`, `resolution (m)`, `tile LOD`, imagery source, imagery Z, and SSE.

