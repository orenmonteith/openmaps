/**
 * Dev preview: serves Cesium assets + Terrarium DEM heightmaps + Esri imagery.
 * Mirrors the Android LocalTileServer API for browser verification.
 */
import express from "express";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { PNG } from "pngjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "../..");
const WEB = path.join(ROOT, "app/src/main/assets/web");
const PORT = Number(process.env.PORT || 43123);
const HEIGHTMAP_SIZE = 65;
const TERRARIUM = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium";
const IMAGERY =
  "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile";

const app = express();
const pngCache = new Map();
const heightCache = new Map();
let lastDebug = {
  providerId: "global-terrarium",
  resolutionMeters: 30,
  level: -1,
  cacheHit: false,
  offline: false,
};

function xTiles(level) {
  return 1 << (level + 1);
}
function yTiles(level) {
  return 1 << level;
}
function rectangle(x, y, level) {
  const nx = xTiles(level);
  const ny = yTiles(level);
  return {
    west: -180 + (x / nx) * 360,
    east: -180 + ((x + 1) / nx) * 360,
    north: 90 - (y / ny) * 180,
    south: 90 - ((y + 1) / ny) * 180,
  };
}
function clampLat(lat) {
  return Math.max(-85.05112878, Math.min(85.05112878, lat));
}
function latLonToPixel(lat, lon, zoom) {
  const n = 2 ** zoom;
  const x = ((lon + 180) / 360) * n * 256;
  const latRad = (clampLat(lat) * Math.PI) / 180;
  const y =
    ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) *
    n *
    256;
  return [x, y];
}
function geoLevelToZoom(level) {
  return Math.min(15, Math.max(0, level + 1));
}
function approxRes(level) {
  const meters = (180 / yTiles(level)) * 111320;
  return Math.max(30, meters / (HEIGHTMAP_SIZE - 1));
}

async function loadTerrarium(z, x, y) {
  const key = `${z}/${x}/${y}`;
  if (pngCache.has(key)) return pngCache.get(key);
  const url = `${TERRARIUM}/${z}/${x}/${y}.png`;
  const res = await fetch(url, {
    headers: { "User-Agent": "TerrainExplorer-Preview/0.1" },
  });
  if (!res.ok) throw new Error(`terrarium ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  const png = PNG.sync.read(buf);
  if (pngCache.size > 120) {
    const first = pngCache.keys().next().value;
    pngCache.delete(first);
  }
  pngCache.set(key, png);
  return png;
}

function heightFromPng(png, lx, ly) {
  const idx = (png.width * ly + lx) << 2;
  const r = png.data[idx];
  const g = png.data[idx + 1];
  const b = png.data[idx + 2];
  return r * 256 + g + b / 256 - 32768;
}

async function buildHeights(x, y, level) {
  const cacheKey = `${level}/${x}/${y}`;
  if (heightCache.has(cacheKey)) {
    const cached = heightCache.get(cacheKey);
    lastDebug = { ...cached.debug, cacheHit: true };
    return cached;
  }

  const rect = rectangle(x, y, level);
  const zoom = geoLevelToZoom(level);
  const heights = new Float32Array(HEIGHTMAP_SIZE * HEIGHTMAP_SIZE);

  // Prefetch unique mercator tiles covering this geographic rectangle.
  const corners = [
    [rect.north, rect.west],
    [rect.north, rect.east],
    [rect.south, rect.west],
    [rect.south, rect.east],
  ];
  let minTX = Infinity,
    maxTX = -Infinity,
    minTY = Infinity,
    maxTY = -Infinity;
  for (const [lat, lon] of corners) {
    const [px, py] = latLonToPixel(lat, lon, zoom);
    minTX = Math.min(minTX, Math.floor(px / 256));
    maxTX = Math.max(maxTX, Math.floor(px / 256));
    minTY = Math.min(minTY, Math.floor(py / 256));
    maxTY = Math.max(maxTY, Math.floor(py / 256));
  }
  const fetches = [];
  for (let ty = minTY; ty <= maxTY; ty++) {
    for (let tx = minTX; tx <= maxTX; tx++) {
      fetches.push(loadTerrarium(zoom, tx, ty));
    }
  }
  await Promise.all(fetches);

  for (let row = 0; row < HEIGHTMAP_SIZE; row++) {
    const lat =
      rect.north - (row / (HEIGHTMAP_SIZE - 1)) * (rect.north - rect.south);
    for (let col = 0; col < HEIGHTMAP_SIZE; col++) {
      const lon =
        rect.west + (col / (HEIGHTMAP_SIZE - 1)) * (rect.east - rect.west);
      const [px, py] = latLonToPixel(lat, lon, zoom);
      const tileX = Math.floor(px / 256);
      const tileY = Math.floor(py / 256);
      const lx = Math.min(255, Math.max(0, Math.floor(px - tileX * 256)));
      const ly = Math.min(255, Math.max(0, Math.floor(py - tileY * 256)));
      const png = await loadTerrarium(zoom, tileX, tileY);
      heights[row * HEIGHTMAP_SIZE + col] = heightFromPng(png, lx, ly);
    }
  }

  const providerId =
    rect.west > -125 && rect.east < -66 && rect.south > 24 && rect.north < 50
      ? "usgs-3dep"
      : "global-terrarium";
  const resolutionMeters =
    providerId === "usgs-3dep"
      ? Math.max(10, approxRes(level))
      : Math.max(30, approxRes(level));
  const debug = {
    providerId,
    resolutionMeters,
    level,
    cacheHit: false,
    offline: false,
  };
  lastDebug = debug;
  const result = { heights, rect, providerId, resolutionMeters, debug };
  if (heightCache.size > 64) {
    heightCache.delete(heightCache.keys().next().value);
  }
  heightCache.set(cacheKey, result);
  return result;
}

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  next();
});

app.get("/health", (_req, res) => res.type("text").send("ok"));
app.get("/debug", (_req, res) => res.json(lastDebug));

app.get("/terrain/:level/:x/:y.json", async (req, res) => {
  try {
    const level = +req.params.level;
    const x = +req.params.x;
    const y = +req.params.y;
    const tile = await buildHeights(x, y, level);
    res.json({
      x,
      y,
      level,
      width: HEIGHTMAP_SIZE,
      height: HEIGHTMAP_SIZE,
      west: tile.rect.west,
      south: tile.rect.south,
      east: tile.rect.east,
      north: tile.rect.north,
      providerId: tile.providerId,
      resolutionMeters: tile.resolutionMeters,
      licenseId:
        tile.providerId === "usgs-3dep"
          ? "usgs-3dep-via-terrarium-ned"
          : "aws-terrarium-open",
    });
  } catch (e) {
    res.status(404).send(String(e.message || e));
  }
});

app.get("/terrain/:level/:x/:y.heights", async (req, res) => {
  try {
    const level = +req.params.level;
    const x = +req.params.x;
    const y = +req.params.y;
    const tile = await buildHeights(x, y, level);
    res.setHeader("Content-Type", "application/octet-stream");
    res.send(Buffer.from(tile.heights.buffer));
  } catch (e) {
    res.status(404).send(String(e.message || e));
  }
});

app.get("/imagery/:z/:x/:y.jpg", async (req, res) => {
  try {
    const { z, x, y } = req.params;
    const url = `${IMAGERY}/${z}/${y}/${x}`;
    const upstream = await fetch(url, {
      headers: { "User-Agent": "TerrainExplorer-Preview/0.1" },
    });
    if (!upstream.ok) return res.status(404).send("no imagery");
    const buf = Buffer.from(await upstream.arrayBuffer());
    res.setHeader("Content-Type", "image/jpeg");
    res.send(buf);
  } catch (e) {
    res.status(404).send(String(e.message || e));
  }
});

app.use(express.static(WEB, { fallthrough: true }));
app.get("/", (_req, res) => {
  res.sendFile(path.join(WEB, "index.html"));
});

if (!fs.existsSync(path.join(WEB, "cesium", "Cesium.js"))) {
  console.error("Missing vendored Cesium at", path.join(WEB, "cesium"));
  process.exit(1);
}

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Terrain Explorer preview http://127.0.0.1:${PORT}`);
});
