/**
 * Cesium viewer: real 3D terrain + draped satellite imagery, battery-aware rendering.
 */
(function (global) {
  "use strict";

  var viewer = null;
  var interacting = false;
  var interactTimer = null;

  function init() {
    if (!global.Cesium || !global.TerrainBridge) {
      setTimeout(init, 50);
      return;
    }

    // Fully offline-capable boot: no Cesium ion token required.
    Cesium.Ion.defaultAccessToken = undefined;

    var terrainProvider = TerrainBridge.createLocalTerrainProvider();
    var imageryProvider = TerrainBridge.createLocalImageryProvider();

    viewer = new Cesium.Viewer("cesiumContainer", {
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
      vrButton: false,
      infoBox: false,
      selectionIndicator: false,
      imageryProvider: false,
      baseLayer: false,
      terrainProvider: terrainProvider,
      requestRenderMode: true,
      maximumRenderTimeChange: Infinity,
      targetFrameRate: 30,
      msaaSamples: 1
    });

    viewer.imageryLayers.removeAll();
    viewer.imageryLayers.addImageryProvider(imageryProvider);

    var scene = viewer.scene;
    scene.globe.depthTestAgainstTerrain = true;
    scene.globe.terrainExaggeration = 1.0;
    scene.fog.enabled = true;
    scene.skyAtmosphere.show = true;
    scene.globe.enableLighting = false;
    scene.fxaa = false;
    scene.postProcessStages.fxaa.enabled = false;
    scene.highDynamicRange = false;
    scene.globe.maximumScreenSpaceError = 2.0;

    // Start over the White Mountains (Mount Washington area) — not hardcoded as the only region.
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(-71.3036, 44.2706, 18000),
      orientation: {
        heading: Cesium.Math.toRadians(20),
        pitch: Cesium.Math.toRadians(-35),
        roll: 0
      }
    });

    bindInteractionThrottling();
    viewer.scene.requestRender();

    if (global.AndroidBridge && global.AndroidBridge.onEngineReady) {
      global.AndroidBridge.onEngineReady();
    }

    pollDebug();
  }

  function bindInteractionThrottling() {
    var canvas = viewer.canvas;
    function bump() {
      interacting = true;
      viewer.targetFrameRate = 30;
      viewer.scene.requestRender();
      if (interactTimer) clearTimeout(interactTimer);
      interactTimer = setTimeout(function () {
        interacting = false;
        viewer.targetFrameRate = 8;
      }, 800);
    }
    ["pointerdown", "pointermove", "wheel", "touchstart", "touchmove"].forEach(function (evt) {
      canvas.addEventListener(evt, bump, { passive: true });
    });
    viewer.camera.changed.addEventListener(function () {
      viewer.scene.requestRender();
    });
  }

  function pollDebug() {
    fetch(TerrainBridge.tileServerBase() + "/debug")
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return;
        if (global.AndroidBridge && global.AndroidBridge.onTerrainDebug) {
          global.AndroidBridge.onTerrainDebug(JSON.stringify(d));
        }
      })
      .catch(function () {})
      .finally(function () {
        setTimeout(pollDebug, 2500);
      });
  }

  global.TerrainApp = {
    flyTo: function (lat, lon, height) {
      if (!viewer) return;
      height = height || 8000;
      viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, height),
        orientation: {
          heading: 0,
          pitch: Cesium.Math.toRadians(-40),
          roll: 0
        },
        duration: 1.6
      });
    },
    resetNorth: function () {
      if (!viewer) return;
      var cam = viewer.camera;
      cam.flyTo({
        destination: cam.positionWC,
        orientation: {
          heading: 0,
          pitch: cam.pitch,
          roll: 0
        },
        duration: 0.6
      });
    },
    pause: function () {
      if (!viewer) return;
      viewer.useDefaultRenderLoop = false;
    },
    resume: function () {
      if (!viewer) return;
      viewer.useDefaultRenderLoop = true;
      viewer.scene.requestRender();
    },
    setBatteryMode: function (lowPower, resolutionScale) {
      if (!viewer) return;
      viewer.resolutionScale = resolutionScale || (lowPower ? 0.7 : 1.0);
      viewer.scene.globe.maximumScreenSpaceError = lowPower ? 4.0 : 2.0;
      viewer.targetFrameRate = lowPower ? 20 : 30;
      viewer.scene.requestRender();
    },
    _onDebug: null
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})(window);
