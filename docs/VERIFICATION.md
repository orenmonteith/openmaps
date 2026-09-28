# Worldwide verification checklist

Manual QA — these locations are **not** hardcoded product destinations.

For each site verify:

1. Terrain data exists
2. Elevations are plausible
3. Geometry is real 3D (tilt shows depth)
4. Satellite imagery is draped on the mesh
5. Camera orbit / tilt / zoom / pan work
6. LOD refines when zooming in
7. Tile seams connect
8. No geographic offset vs known landmarks
9. No dateline/projection crash
10. Renderer remains stable

## Sites

| Region | Suggested viewpoint |
| --- | --- |
| US – White Mountains | 44.2706, -71.3036 (Mount Washington) |
| Europe – Alps | 45.8326, 6.8652 (Mont Blanc) |
| Asia – Himalayas | 27.9881, 86.9250 (Everest region) |
| Asia – Japanese Alps | 36.2890, 137.6480 |
| Oceania – New Zealand | -43.5950, 170.1420 (Aoraki / Mt Cook) |
| Canada – Rockies | 51.4250, -116.2500 |
| South America – Andes | -32.6532, -70.0110 |
| Africa – Atlas | 31.0600, -7.9200 |
| Australia – Snowy Mtns | -36.4560, 148.2630 |
| Dateline | 0.0, 179.5 and 0.0, -179.5 |
| High latitude | 78.0, 15.0 (guard distortion / SSE) |

## Offline / battery checks

- Visit a region online, disable network, confirm cached parent/child tiles still render
- Background the app: WebView pauses; resume restores rendering
- Low-battery mode lowers `resolutionScale` / raises SSE
