/**
 * Bridge between Cesium and the Android localhost tile server.
 * Fetches normalized Float32 heightmaps and builds a CustomHeightmapTerrainProvider.
 */
(function (global) {
  "use strict";

  function tileServerBase() {
    return "http://127.0.0.1:" + (global.TERRAIN_PORT || "8765");
  }

  function createLocalTerrainProvider() {
    var size = 65;
    return new Cesium.CustomHeightmapTerrainProvider({
      width: size,
      height: size,
      callback: function (x, y, level) {
        var url = tileServerBase() + "/terrain/" + level + "/" + x + "/" + y + ".heights";
        return fetch(url)
          .then(function (res) {
            if (!res.ok) {
              // Signal Cesium to use parent tile geometry.
              return undefined;
            }
            return res.arrayBuffer();
          })
          .then(function (buffer) {
            if (!buffer) return undefined;
            var heights = new Float32Array(buffer);
            if (heights.length !== size * size) {
              return undefined;
            }
            // Push debug metadata (fire-and-forget).
            fetch(tileServerBase() + "/terrain/" + level + "/" + x + "/" + y + ".json")
              .then(function (r) { return r.ok ? r.json() : null; })
              .then(function (meta) {
                if (!meta) return;
                var payload = {
                  providerId: meta.providerId,
                  resolutionMeters: meta.resolutionMeters,
                  level: meta.level,
                  cacheHit: false,
                  offline: false
                };
                if (global.AndroidBridge && global.AndroidBridge.onTerrainDebug) {
                  global.AndroidBridge.onTerrainDebug(JSON.stringify(payload));
                }
                if (global.TerrainApp && global.TerrainApp._onDebug) {
                  global.TerrainApp._onDebug(payload);
                }
              })
              .catch(function () {});
            return heights;
          })
          .catch(function () {
            return undefined;
          });
      }
    });
  }

  function createLocalImageryProvider() {
    return new Cesium.UrlTemplateImageryProvider({
      url: tileServerBase() + "/imagery/{z}/{x}/{y}.jpg",
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      maximumLevel: 18,
      credit: "Esri World Imagery"
    });
  }

  global.TerrainBridge = {
    tileServerBase: tileServerBase,
    createLocalTerrainProvider: createLocalTerrainProvider,
    createLocalImageryProvider: createLocalImageryProvider
  };
})(window);
