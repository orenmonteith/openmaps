/**
 * OpenMaps Cesium viewer — stable worldwide imagery, performance-first LOD.
 */
(function (global) {
  "use strict";

  var viewer = null;
  var imageryLayer = null;
  var lowPower = false;
  var interactTimer = null;
  var settleTimer = null;
  var lodReportTimer = null;
  var userEntity = null;
  var lastLod = { imageryZ: 19, sse: 4, resolutionScale: 1 };

  function showWebGlError(detail) {
    var el = document.getElementById("cesiumContainer");
    if (!el) return;
    el.innerHTML =
      '<div class="webgl-error">' +
      "<h1>WebGL unavailable</h1>" +
      "<p>This browser/session cannot create a WebGL context for 3D terrain.</p>" +
      "<p>OpenMaps is meant for the <strong>Android app</strong> (Pixel WebView has WebGL). " +
      "For the web preview, open the URL in Chrome/Firefox on a machine with GPU, " +
      "or enable hardware acceleration.</p>" +
      (detail
        ? '<pre class="webgl-error-detail">' + String(detail).slice(0, 600) + "</pre>"
        : "") +
      "</div>";
  }

  function webGlWorks() {
    try {
      var c = document.createElement("canvas");
      var gl =
        c.getContext("webgl2", { failIfMajorPerformanceCaveat: false }) ||
        c.getContext("webgl", { failIfMajorPerformanceCaveat: false }) ||
        c.getContext("experimental-webgl", { failIfMajorPerformanceCaveat: false });
      return !!gl;
    } catch (e) {
      return false;
    }
  }

  function createViewer(terrainProvider) {
    var base = {
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
      msaaSamples: 4,
      // Critical: default true ignores devicePixelRatio → soft on Pixel/retina.
      useBrowserRecommendedResolution: false
    };
    // Quality first; soft options only if the device rejects the sharp path.
    var attempts = [
      {
        webgl: {
          alpha: false,
          antialias: true,
          powerPreference: "high-performance",
          failIfMajorPerformanceCaveat: false
        },
        allowTextureFilterAnisotropic: true
      },
      {
        webgl: {
          alpha: false,
          antialias: true,
          powerPreference: "default",
          failIfMajorPerformanceCaveat: false
        },
        allowTextureFilterAnisotropic: true
      },
      {
        webgl: {
          alpha: false,
          antialias: false,
          powerPreference: "default",
          failIfMajorPerformanceCaveat: false
        },
        allowTextureFilterAnisotropic: false
      }
    ];
    var lastErr = null;
    for (var i = 0; i < attempts.length; i++) {
      try {
        var opts = Object.assign({}, base, { contextOptions: attempts[i] });
        if (i === attempts.length - 1) opts.msaaSamples = 1;
        return new Cesium.Viewer("cesiumContainer", opts);
      } catch (err) {
        lastErr = err;
        var stale = document.getElementById("cesiumContainer");
        if (stale) stale.innerHTML = "";
      }
    }
    throw lastErr || new Error("Failed to construct Cesium.Viewer");
  }

  function init() {
    if (!global.Cesium || !global.TerrainBridge) {
      setTimeout(init, 50);
      return;
    }

    if (!webGlWorks()) {
      showWebGlError("No WebGL1/WebGL2 context from this browser.");
      return;
    }

    Cesium.Ion.defaultAccessToken = undefined;

    var terrainProvider = TerrainBridge.createLocalTerrainProvider();
    try {
      viewer = createViewer(terrainProvider);
    } catch (err) {
      showWebGlError(err && (err.message || err));
      return;
    }

    var scene = viewer.scene;
    // Match UI chrome — never flash Cesium's default blue clear/sky through gaps.
    var earth = Cesium.Color.fromCssColorString("#1a2420");
    scene.backgroundColor = earth;
    scene.globe.baseColor = earth;
    scene.globe.showGroundAtmosphere = false;
    scene.globe.depthTestAgainstTerrain = true;
    scene.globe.terrainExaggeration = 1.0;
    scene.fog.enabled = true;
    scene.fog.density = 0.00012;
    scene.skyAtmosphere.show = true;
    scene.globe.enableLighting = false;
    // FXAA softens draped satellite — keep off for crisp imagery.
    scene.fxaa = false;
    scene.postProcessStages.fxaa.enabled = false;
    scene.highDynamicRange = false;
    scene.globe.tileCacheSize = 400;
    // Show ancestors ASAP so holes (blue sky) never open while children load.
    scene.globe.loadingDescendantLimit = 1;
    scene.globe.preloadAncestors = true;
    scene.globe.preloadSiblings = true;
    scene.globe.maximumScreenSpaceError = 1.25;

    // ONE stable worldwide imagery layer — never tear it down while flying.
    viewer.imageryLayers.removeAll();
    imageryLayer = viewer.imageryLayers.addImageryProvider(
      TerrainBridge.createLocalImageryProvider(19)
    );
    // Keep color grading neutral — contrast/gamma exaggerate Esri tile seams.
    imageryLayer.brightness = 1.0;
    imageryLayer.contrast = 1.0;
    imageryLayer.saturation = 1.0;
    imageryLayer.gamma = 1.0;

    scene.globe.imageryLayersUpdatedEvent.addEventListener(function () {
      viewer.scene.requestRender();
    });
    scene.globe.tileLoadProgressEvent.addEventListener(function () {
      viewer.scene.requestRender();
    });

    var controller = scene.screenSpaceCameraController;
    controller.zoomFactor = 14.0;
    controller.minimumZoomDistance = 80.0;
    controller.maximumZoomDistance = 4.0e7;
    controller.inertiaZoom = 0.75;
    controller.enableCollisionDetection = true;

    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(-71.3036, 44.2706, 18000),
      orientation: {
        heading: Cesium.Math.toRadians(20),
        pitch: Cesium.Math.toRadians(-35),
        roll: 0
      }
    });

    userEntity = viewer.entities.add({
      id: "user-location",
      position: Cesium.Cartesian3.fromDegrees(-71.3036, 44.2706, 0),
      point: {
        pixelSize: 12,
        color: Cesium.Color.fromCssColorString("#2BB673"),
        outlineColor: Cesium.Color.WHITE,
        outlineWidth: 2,
        heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
        disableDepthTestDistance: Number.POSITIVE_INFINITY
      },
      show: false
    });

    bindInteractionThrottling();
    bindDynamicLod();
    updateLod(true);
    viewer.scene.requestRender();

    if (global.AndroidBridge && global.AndroidBridge.onEngineReady) {
      global.AndroidBridge.onEngineReady();
    }

    pollDebug();
  }

  function lodForHeight(height) {
    // Imagery max Z stays 19 on the provider; lower SSE → sharper child tiles.
    // resolutionScale is relative to devicePixelRatio (useBrowserRecommendedResolution=false).
    var sse;
    var scale = lowPower ? 0.7 : 1.0;
    var imageryZ;
    if (height > 2.0e6) {
      sse = 4.0;
      imageryZ = 13;
    } else if (height > 5.0e5) {
      sse = 2.4;
      imageryZ = 15;
    } else if (height > 1.0e5) {
      sse = 1.4;
      imageryZ = 17;
    } else if (height > 2.5e4) {
      sse = 0.95;
      imageryZ = 18;
    } else if (height > 5.0e3) {
      sse = 0.65;
      imageryZ = 19;
      scale = lowPower ? 0.8 : 1.0;
    } else {
      sse = 0.45;
      imageryZ = 19;
      scale = lowPower ? 0.85 : 1.0;
    }
    if (lowPower) {
      sse = Math.max(sse, 1.8);
      imageryZ = Math.min(imageryZ, 17);
    }
    return { sse: sse, imageryZ: imageryZ, resolutionScale: scale };
  }

  function updateLod(force) {
    if (!viewer) return;
    var height = viewer.camera.positionCartographic.height;
    var lod = lodForHeight(height);
    if (viewer.scene.globe.maximumScreenSpaceError !== lod.sse) {
      viewer.scene.globe.maximumScreenSpaceError = lod.sse;
    }
    if (Math.abs(viewer.resolutionScale - lod.resolutionScale) > 0.01) {
      viewer.resolutionScale = lod.resolutionScale;
    }
    lastLod = lod;
    scheduleLodReport();
    if (force) viewer.scene.requestRender();
  }

  function scheduleLodReport() {
    if (lodReportTimer) return;
    lodReportTimer = setTimeout(function () {
      lodReportTimer = null;
      fetch(
        TerrainBridge.tileServerBase() +
          "/client/lod?imageryZ=" +
          encodeURIComponent(lastLod.imageryZ) +
          "&sse=" +
          encodeURIComponent(lastLod.sse) +
          "&resolutionScale=" +
          encodeURIComponent(lastLod.resolutionScale)
      ).catch(function () {});
    }, 500);
  }

  function scheduleSettle() {
    if (settleTimer) clearTimeout(settleTimer);
    settleTimer = setTimeout(function () {
      if (!viewer || lowPower) return;
      var height = viewer.camera.positionCartographic.height;
      // After the camera settles, demand sharper tiles across the whole frustum.
      if (height < 150000) {
        var sharp = Math.max(0.4, lodForHeight(height).sse * 0.55);
        if (viewer.scene.globe.maximumScreenSpaceError > sharp) {
          viewer.scene.globe.maximumScreenSpaceError = sharp;
          lastLod.sse = sharp;
          viewer.scene.requestRender();
        }
      }
      if (height > 80000) return;
      var c = viewer.camera.positionCartographic;
      var lat = Cesium.Math.toDegrees(c.latitude);
      var lon = Cesium.Math.toDegrees(c.longitude);
      var z = Math.min(19, Math.max(lastLod.imageryZ, height < 12000 ? 19 : 18));
      fetch(
        TerrainBridge.tileServerBase() +
          "/imagery/prefetch?lat=" +
          lat +
          "&lon=" +
          lon +
          "&z=" +
          z
      ).catch(function () {});
    }, 350);
  }

  function bindDynamicLod() {
    viewer.camera.changed.addEventListener(function () {
      updateLod(false);
      scheduleSettle();
      viewer.scene.requestRender();
    });
    viewer.camera.moveEnd.addEventListener(function () {
      updateLod(true);
      scheduleSettle();
    });
  }

  function bindInteractionThrottling() {
    var canvas = viewer.canvas;
    function bump() {
      viewer.targetFrameRate = 30;
      viewer.scene.requestRender();
      if (interactTimer) clearTimeout(interactTimer);
      interactTimer = setTimeout(function () {
        viewer.targetFrameRate = 12;
      }, 900);
    }
    ["pointerdown", "pointermove", "wheel", "touchstart", "touchmove"].forEach(function (evt) {
      canvas.addEventListener(evt, bump, { passive: true });
    });
  }

  function pollDebug() {
    fetch(TerrainBridge.tileServerBase() + "/debug")
      .then(function (r) {
        return r.ok ? r.json() : null;
      })
      .then(function (d) {
        if (!d) return;
        d.imageryZ = lastLod.imageryZ;
        d.sse = String(lastLod.sse);
        d.resolutionScale = String(lastLod.resolutionScale);
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
      height = height || 12000;
      viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, height),
        orientation: {
          heading: 0,
          pitch: Cesium.Math.toRadians(-45),
          roll: 0
        },
        duration: 2.0,
        complete: function () {
          updateLod(true);
          scheduleSettle();
          viewer.scene.requestRender();
        }
      });
    },
    showUserLocation: function (lat, lon) {
      if (!viewer || !userEntity) return;
      userEntity.position = Cesium.Cartesian3.fromDegrees(lon, lat);
      userEntity.show = true;
      viewer.scene.requestRender();
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
        duration: 0.5
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
    setBatteryMode: function (isLow, resolutionScale) {
      if (!viewer) return;
      lowPower = !!isLow;
      if (typeof resolutionScale === "number") {
        viewer.resolutionScale = resolutionScale;
      }
      updateLod(true);
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
