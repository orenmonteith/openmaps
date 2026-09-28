/**
 * Bridge between Cesium and the Android localhost tile server.
 */
(function (global) {
  "use strict";

  var MAX_TERRAIN_LEVEL = 11;

  function tileServerBase() {
    return "http://127.0.0.1:" + (global.TERRAIN_PORT || "8765");
  }

  function flatHeights(size) {
    return new Float32Array(size * size);
  }

  function createLocalTerrainProvider() {
    var size = 65;
    return new Cesium.CustomHeightmapTerrainProvider({
      width: size,
      height: size,
      callback: function (x, y, level) {
        if (level > MAX_TERRAIN_LEVEL) {
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

  function createLocalImageryProvider(maximumLevel) {
    var maxZ = typeof maximumLevel === "number" ? maximumLevel : 19;
    return new Cesium.UrlTemplateImageryProvider({
      url: tileServerBase() + "/imagery/{z}/{x}/{y}.jpg",
      tilingScheme: new Cesium.WebMercatorTilingScheme(),
      maximumLevel: maxZ,
      credit: "Satellite imagery"
    });
  }

  global.TerrainBridge = {
    tileServerBase: tileServerBase,
    createLocalTerrainProvider: createLocalTerrainProvider,
    createLocalImageryProvider: createLocalImageryProvider,
    MAX_TERRAIN_LEVEL: MAX_TERRAIN_LEVEL
  };
})(window);
