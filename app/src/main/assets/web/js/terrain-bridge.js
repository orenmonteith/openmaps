/**
 * Bridge between Cesium and the Android localhost tile server.
 */
(function (global) {
  "use strict";

  var isAndroid = !!(global.OPENMAPS_ANDROID || global.AndroidBridge);
  // Android: L12 ≈ 76 m mesh, Terrarium ~30 m source — proven reliable on Pixel.
  // Desktop preview can go one step deeper.
  var MAX_TERRAIN_LEVEL = isAndroid ? 12 : 13;
  var activeTerrainLevel = isAndroid ? 10 : 11;

  function tileServerBase() {
    return "http://127.0.0.1:" + (global.TERRAIN_PORT || "8765");
  }

  function flatHeights(size) {
    return new Float32Array(size * size);
  }

  /** Cap DEM refinement by camera distance — far views skip deep terrain levels. */
  function setMaxTerrainLevel(level) {
    var n = typeof level === "number" ? level : activeTerrainLevel;
    var next = Math.max(6, Math.min(MAX_TERRAIN_LEVEL, n | 0));
    // Allow raise freely; only lower by 1 step to avoid thrashing mid-gesture.
    if (next >= activeTerrainLevel) {
      activeTerrainLevel = next;
    } else if (activeTerrainLevel - next >= 2) {
      activeTerrainLevel = next;
    }
  }

  function createLocalTerrainProvider() {
    var size = 65;
    return new Cesium.CustomHeightmapTerrainProvider({
      width: size,
      height: size,
      callback: function (x, y, level) {
        if (level > activeTerrainLevel) {
          return undefined;
        }
        var url = tileServerBase() + "/terrain/" + level + "/" + x + "/" + y + ".heights";
        return fetch(url)
          .then(function (res) {
            if (!res.ok) {
              return level === 0 ? flatHeights(size) : undefined;
            }
            return res.arrayBuffer();
          })
          .then(function (buffer) {
            if (!buffer) {
              return level === 0 ? flatHeights(size) : undefined;
            }
            if (buffer instanceof Float32Array) return buffer;
            var heights = new Float32Array(buffer);
            if (heights.length !== size * size) {
              return level === 0 ? flatHeights(size) : undefined;
            }
            return heights;
          })
          .catch(function () {
            return level === 0 ? flatHeights(size) : undefined;
          });
      }
    });
  }

  function createEllipsoidTerrainProvider() {
    return new Cesium.EllipsoidTerrainProvider();
  }

  function createLocalImageryProvider(maximumLevel) {
    var maxZ = typeof maximumLevel === "number" ? maximumLevel : 19;
    return new Cesium.UrlTemplateImageryProvider({
      url: tileServerBase() + "/imagery/{z}/{x}/{y}.jpg",
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      minimumLevel: 0,
      maximumLevel: maxZ,
      tileWidth: 256,
      tileHeight: 256,
      hasAlphaChannel: false,
      enablePickFeatures: false,
      credit: "Satellite imagery"
    });
  }

  function createBasemapProvider(style, maximumLevel) {
    var maxZ =
      typeof maximumLevel === "number"
        ? maximumLevel
        : style === "topo"
          ? 17
          : 19;
    return new Cesium.UrlTemplateImageryProvider({
      url: tileServerBase() + "/basemap/" + style + "/{z}/{x}/{y}.png",
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      minimumLevel: 0,
      maximumLevel: maxZ,
      tileWidth: 256,
      tileHeight: 256,
      hasAlphaChannel: true,
      enablePickFeatures: false,
      credit: style === "topo" ? "OpenTopoMap" : "OpenStreetMap"
    });
  }

  global.TerrainBridge = {
    tileServerBase: tileServerBase,
    createLocalTerrainProvider: createLocalTerrainProvider,
    createEllipsoidTerrainProvider: createEllipsoidTerrainProvider,
    createLocalImageryProvider: createLocalImageryProvider,
    createBasemapProvider: createBasemapProvider,
    setMaxTerrainLevel: setMaxTerrainLevel,
    MAX_TERRAIN_LEVEL: MAX_TERRAIN_LEVEL
  };
})(window);
