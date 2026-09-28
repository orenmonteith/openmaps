/**
 * Cesium viewer: real 3D terrain + draped satellite imagery, battery-aware rendering.
 */
(function (global) {
  "use strict";

  var viewer = null;
  var interactTimer = null;

  function init() {
    if (!global.Cesium || !global.TerrainBridge) {
      setTimeout(init, 50);
      return;
    }

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
    scene.globe.tileCacheSize = 120;
    scene.globe.loadingDescendantLimit = 2;
    scene.globe.preloadAncestors = true;
    scene.globe.preloadSiblings = false;
    // Start conservative for far views; updateSse() tightens when close.
    scene.globe.maximumScreenSpaceError = 6.0;

    var controller = scene.screenSpaceCameraController;
    // More sensitive zoom (especially noticeable when zooming out).
    controller.zoomFactor = 14.0;
    controller.minimumZoomDistance = 60.0;
    controller.maximumZoomDistance = 4.0e7;
    controller.inertiaZoom = 0.8;
    controller.enableCollisionDetection = true;

    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(-71.3036, 44.2706, 18000),
      orientation: {
        heading: Cesium.Math.toRadians(20),
        pitch: Cesium.Math.toRadians(-35),
        roll: 0
      }
    });

    bindInteractionThrottling();
    bindDynamicLod();
    updateSse();
    viewer.scene.requestRender();

    if (global.AndroidBridge && global.AndroidBridge.onEngineReady) {
      global.AndroidBridge.onEngineReady();
    }

    pollDebug();
  }

  function updateSse() {
    if (!viewer) return;
    var height = viewer.camera.positionCartographic.height;
    var sse;
    if (height > 2.0e6) {
      sse = 10.0; // continent / globe — fewer tiles, snappier
    } else if (height > 4.0e5) {
      sse = 7.0;
    } else if (height > 8.0e4) {
      sse = 4.5;
    } else if (height > 1.5e4) {
      sse = 2.5;
    } else if (height > 3.0e3) {
      sse = 1.75; // approach DEM resolution
    } else {
      sse = 1.25; // close — max detail within capped terrain LOD
    }
    if (viewer.scene.globe.maximumScreenSpaceError !== sse) {
      viewer.scene.globe.maximumScreenSpaceError = sse;
    }
  }

  function bindDynamicLod() {
    viewer.camera.changed.addEventListener(function () {
      updateSse();
      viewer.scene.requestRender();
    });
  }

  function bindInteractionThrottling() {
    var canvas = viewer.canvas;
    function bump() {
      viewer.targetFrameRate = 30;
      viewer.scene.requestRender();
      if (interactTimer) clearTimeout(interactTimer);
      interactTimer = setTimeout(function () {
        viewer.targetFrameRate = 8;
      }, 800);
    }
    ["pointerdown", "pointermove", "wheel", "touchstart", "touchmove"].forEach(function (evt) {
      canvas.addEventListener(evt, bump, { passive: true });
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
      if (lowPower) {
        viewer.scene.globe.maximumScreenSpaceError = Math.max(
          viewer.scene.globe.maximumScreenSpaceError,
          6.0
        );
        viewer.targetFrameRate = 20;
      } else {
        updateSse();
        viewer.targetFrameRate = 30;
      }
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
