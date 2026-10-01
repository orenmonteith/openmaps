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
    // Mild vertical relief so couloirs / fall lines read when scouting.
    scene.globe.terrainExaggeration = 1.45;
    scene.fog.enabled = true;
    scene.fog.density = 0.000035;
    scene.fog.minimumBrightness = 0.45;
    scene.skyAtmosphere.show = true;
    // Winter-sun shading makes aspect and slope pop without burying snow detail.
    scene.globe.enableLighting = true;
    scene.globe.dynamicAtmosphereLighting = true;
    scene.globe.atmosphereLightIntensity = 8.0;
    viewer.clock.currentTime = Cesium.JulianDate.fromIso8601("2024-02-10T14:30:00Z");
    viewer.clock.shouldAnimate = false;
    // FXAA softens draped satellite — keep off for crisp imagery.
    scene.fxaa = false;
    scene.postProcessStages.fxaa.enabled = false;
    scene.highDynamicRange = false;
    scene.globe.tileCacheSize = 400;
    // Show ancestors ASAP so holes (blue sky) never open while children load.
    scene.globe.loadingDescendantLimit = 1;
    scene.globe.preloadAncestors = true;
    scene.globe.preloadSiblings = false;
    scene.globe.maximumScreenSpaceError = 2.0;

    // ONE stable worldwide imagery layer — never tear it down while flying.
    viewer.imageryLayers.removeAll();
    imageryLayer = viewer.imageryLayers.addImageryProvider(
      TerrainBridge.createLocalImageryProvider(19)
    );
    // Slight lift for snow / rock separation while scouting lines.
    imageryLayer.brightness = 1.06;
    imageryLayer.contrast = 1.05;
    imageryLayer.saturation = 0.95;
    imageryLayer.gamma = 0.96;

    scene.globe.imageryLayersUpdatedEvent.addEventListener(function () {
      viewer.scene.requestRender();
    });
    scene.globe.tileLoadProgressEvent.addEventListener(function () {
      viewer.scene.requestRender();
    });

    var controller = scene.screenSpaceCameraController;
    controller.zoomFactor = 12.0;
    controller.minimumZoomDistance = 35.0;
    controller.maximumZoomDistance = 4.0e7;
    controller.inertiaZoom = 0.7;
    controller.inertiaSpin = 0.85;
    controller.inertiaTranslate = 0.85;
    controller.enableCollisionDetection = true;

    // Oblique scout angle — fall lines read better than nadir.
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(-71.3036, 44.2706, 4200),
      orientation: {
        heading: Cesium.Math.toRadians(35),
        pitch: Cesium.Math.toRadians(-42),
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

  /** Meters above ground — ellipsoid height is wrong on tall peaks for LOD. */
  function cameraAgl() {
    var carto = viewer.camera.positionCartographic;
    var surface = viewer.scene.globe.getHeight(carto);
    if (typeof surface !== "number" || isNaN(surface)) {
      return Math.max(100, carto.height);
    }
    return Math.max(40, carto.height - surface);
  }

  /**
   * Google Earth-style LOD: razor-sharp only when close to the surface;
   * zoomed-out / far horizon stays cheap so it cannot starve the near field.
   */
  function lodForAgl(agl) {
    var sse;
    var scale = lowPower ? 0.7 : 1.0;
    var imageryZ;
    var terrainLevel;
    var preloadSiblings;
    var fogDensity;
    // terrainLevel targets mesh spacing ≈ 111320*180/(2^L)/64 meters
    // L15≈10 m, L16≈5 m, L17≈2.4 m, L18≈1.2 m (USGS 3DEP can feed ~1 m in US lidar).
    if (agl > 2.0e5) {
      sse = 8.0;
      imageryZ = 11;
      terrainLevel = 6;
      preloadSiblings = true;
      fogDensity = 0.00002;
    } else if (agl > 5.0e4) {
      sse = 5.0;
      imageryZ = 13;
      terrainLevel = 8;
      preloadSiblings = true;
      fogDensity = 0.00003;
    } else if (agl > 1.2e4) {
      sse = 2.8;
      imageryZ = 15;
      terrainLevel = 10;
      preloadSiblings = true;
      fogDensity = 0.00004;
    } else if (agl > 4.0e3) {
      sse = 1.5;
      imageryZ = 17;
      terrainLevel = 13;
      preloadSiblings = false;
      fogDensity = 0.00005;
    } else if (agl > 1.2e3) {
      sse = 0.7;
      imageryZ = 18;
      terrainLevel = 15;
      preloadSiblings = false;
      fogDensity = 0.00007;
      scale = lowPower ? 0.85 : 1.0;
    } else if (agl > 350) {
      sse = 0.35;
      imageryZ = 19;
      terrainLevel = 17;
      preloadSiblings = false;
      fogDensity = 0.0001;
      scale = lowPower ? 0.9 : 1.0;
    } else {
      // Super close — push toward ~1 m DEM mesh (L18)
      sse = 0.2;
      imageryZ = 19;
      terrainLevel = 18;
      preloadSiblings = false;
      fogDensity = 0.00014;
      scale = lowPower ? 0.95 : 1.0;
    }
    if (lowPower) {
      sse = Math.max(sse, 1.4);
      imageryZ = Math.min(imageryZ, 17);
      terrainLevel = Math.min(terrainLevel, 14);
    }
    return {
      sse: sse,
      imageryZ: imageryZ,
      resolutionScale: scale,
      terrainLevel: terrainLevel,
      preloadSiblings: preloadSiblings,
      fogDensity: fogDensity,
      agl: agl
    };
  }

  function applyLod(lod) {
    var globe = viewer.scene.globe;
    if (globe.maximumScreenSpaceError !== lod.sse) {
      globe.maximumScreenSpaceError = lod.sse;
    }
    if (globe.preloadSiblings !== lod.preloadSiblings) {
      globe.preloadSiblings = lod.preloadSiblings;
    }
    viewer.scene.fog.density = lod.fogDensity;
    if (Math.abs(viewer.resolutionScale - lod.resolutionScale) > 0.01) {
      viewer.resolutionScale = lod.resolutionScale;
    }
    if (TerrainBridge.setMaxTerrainLevel) {
      TerrainBridge.setMaxTerrainLevel(lod.terrainLevel);
    }
  }

  function updateLod(force) {
    if (!viewer) return;
    var lod = lodForAgl(cameraAgl());
    applyLod(lod);
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
      var agl = cameraAgl();
      var lod = lodForAgl(agl);
      // Settled + close → squeeze SSE harder so z18/z19 imagery fills the near face.
      if (agl < 8000) {
        var sharp = Math.max(0.18, lod.sse * 0.7);
        if (agl < 800) sharp = Math.min(sharp, 0.2);
        else if (agl < 2000) sharp = Math.min(sharp, 0.3);
        if (viewer.scene.globe.maximumScreenSpaceError > sharp) {
          viewer.scene.globe.maximumScreenSpaceError = sharp;
          lastLod.sse = sharp;
          viewer.scene.requestRender();
        }
      }
      // Only prefetch high-z when we're actually close — never for the far horizon.
      if (agl > 10000) return;
      var c = viewer.camera.positionCartographic;
      var lat = Cesium.Math.toDegrees(c.latitude);
      var lon = Cesium.Math.toDegrees(c.longitude);
      var z = agl < 1500 ? 19 : agl < 4000 ? 18 : 16;
      fetch(
        TerrainBridge.tileServerBase() +
          "/imagery/prefetch?lat=" +
          lat +
          "&lon=" +
          lon +
          "&z=" +
          z
      ).catch(function () {});
    }, 300);
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
      // Default into a ski-scout band: close enough for lines, high enough for context.
      height = height || 3200;
      viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, height),
        orientation: {
          heading: Cesium.Math.toRadians(28),
          pitch: Cesium.Math.toRadians(-40),
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
    /** Re-pitch current view for fall-line / ridge scouting. */
    scoutView: function () {
      if (!viewer) return;
      var cam = viewer.camera;
      var c = cam.positionCartographic;
      var h = Math.max(400, Math.min(c.height, 8000));
      cam.flyTo({
        destination: Cesium.Cartesian3.fromRadians(c.longitude, c.latitude, h),
        orientation: {
          heading: cam.heading + Cesium.Math.toRadians(25),
          pitch: Cesium.Math.toRadians(-38),
          roll: 0
        },
        duration: 0.7,
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
          pitch: Cesium.Math.toRadians(-40),
          roll: 0
        },
        duration: 0.5
      });
    },
    setTerrainExaggeration: function (amount) {
      if (!viewer) return;
      viewer.scene.globe.terrainExaggeration =
        typeof amount === "number" ? Math.max(1, Math.min(amount, 2.5)) : 1.45;
      viewer.scene.requestRender();
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
