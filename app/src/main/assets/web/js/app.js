/**
 * OpenMaps Cesium viewer — map modes, deferred DEM, Fatmap-style offline packs.
 *
 * Modes:
 *   flat-osm  — 2D OpenStreetMap, no DEM mesh
 *   sat-3d    — 3D satellite (DEM only when close enough for contours)
 *   topo-3d   — 3D OpenTopoMap draped on DEM
 */
(function (global) {
  "use strict";

  var viewer = null;
  var imageryLayer = null;
  var demTerrainProvider = null;
  var ellipsoidTerrain = null;
  var lowPower = false;
  var isAndroid = !!(global.OPENMAPS_ANDROID || global.AndroidBridge);
  var interactTimer = null;
  var settleTimer = null;
  var lodReportTimer = null;
  var userEntity = null;
  var lastLod = { imageryZ: 17, sse: 4, resolutionScale: 1 };
  var mapMode = "sat-3d";
  var terrainMeshActive = false;
  /** Last oblique 3D pose, restored when leaving the flat chart. */
  var saved3dView = null;
  // Contours only matter near the surface — keep the globe cheap until then.
  var TERRAIN_ENABLE_AGL = 42000;
  var TERRAIN_DISABLE_AGL = 75000;
  var MODE_ATTRIBUTION = {
    "flat-osm": "© OpenStreetMap contributors",
    "sat-3d": "Satellite · DEM terrain",
    "topo-3d": "© OpenStreetMap / OpenTopoMap · DEM terrain"
  };

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

  function captureCamera() {
    if (!viewer) return null;
    var c = viewer.camera.positionCartographic;
    return {
      lon: Cesium.Math.toDegrees(c.longitude),
      lat: Cesium.Math.toDegrees(c.latitude),
      height: c.height,
      heading: viewer.camera.heading,
      pitch: viewer.camera.pitch,
      roll: viewer.camera.roll
    };
  }

  function restoreCamera(snap, forceNadir) {
    if (!viewer || !snap) return;
    var pitch = forceNadir
      ? Cesium.Math.toRadians(-90)
      : typeof snap.pitch === "number"
        ? snap.pitch
        : Cesium.Math.toRadians(-42);
    var heading =
      typeof snap.heading === "number" ? snap.heading : Cesium.Math.toRadians(35);
    var height = Math.max(120, snap.height || 4200);
    viewer.camera.setView({
      destination: Cesium.Cartesian3.fromDegrees(snap.lon, snap.lat, height),
      orientation: {
        heading: heading,
        pitch: pitch,
        roll: typeof snap.roll === "number" ? snap.roll : 0
      }
    });
  }

  function imageryTuningForMode(mode) {
    if (!imageryLayer) return;
    if (mode === "sat-3d") {
      imageryLayer.brightness = 1.06;
      imageryLayer.contrast = 1.05;
      imageryLayer.saturation = 0.95;
      imageryLayer.gamma = 0.96;
    } else {
      // OSM / topo already have cartographic contrast — keep neutral.
      imageryLayer.brightness = 1.0;
      imageryLayer.contrast = 1.0;
      imageryLayer.saturation = 1.0;
      imageryLayer.gamma = 1.0;
    }
  }

  function providerForMode(mode) {
    if (mode === "flat-osm") {
      return TerrainBridge.createBasemapProvider("osm", 19);
    }
    if (mode === "topo-3d") {
      return TerrainBridge.createBasemapProvider("topo", 17);
    }
    return TerrainBridge.createLocalImageryProvider(19);
  }

  function setImageryForMode(mode) {
    if (!viewer) return;
    viewer.imageryLayers.removeAll();
    imageryLayer = viewer.imageryLayers.addImageryProvider(providerForMode(mode));
    imageryTuningForMode(mode);
    // Flat OSM reads as a chart — kill winter-sun / atmosphere shading.
    var lit = mode !== "flat-osm";
    viewer.scene.globe.enableLighting = lit;
    viewer.scene.skyAtmosphere.show = lit;
    viewer.scene.fog.enabled = lit;
  }

  function setTerrainMesh(enabled) {
    if (!viewer || !demTerrainProvider || !ellipsoidTerrain) return;
    if (enabled === terrainMeshActive) return;
    viewer.terrainProvider = enabled ? demTerrainProvider : ellipsoidTerrain;
    terrainMeshActive = enabled;
    viewer.scene.globe.depthTestAgainstTerrain = enabled;
    viewer.scene.globe.terrainExaggeration = enabled ? 1.45 : 1.0;
  }

  function syncDeferredTerrain(agl) {
    if (!viewer || mapMode === "flat-osm") {
      setTerrainMesh(false);
      return;
    }
    if (!terrainMeshActive && agl <= TERRAIN_ENABLE_AGL) {
      setTerrainMesh(true);
    } else if (terrainMeshActive && agl >= TERRAIN_DISABLE_AGL) {
      setTerrainMesh(false);
    }
  }

  function applyFlatChart(snap) {
    var controller = viewer.scene.screenSpaceCameraController;
    setTerrainMesh(false);
    controller.enableTilt = false;
    controller.enableLook = false;
    var flatH = Math.max(800, Math.min(snap.height || 4200, 120000));
    restoreCamera(
      {
        lon: snap.lon,
        lat: snap.lat,
        height: flatH,
        heading: 0,
        pitch: Cesium.Math.toRadians(-90),
        roll: 0
      },
      true,
    );
  }

  function applySaved3d(snap) {
    var controller = viewer.scene.screenSpaceCameraController;
    controller.enableTilt = true;
    controller.enableLook = true;
    restoreCamera(
      {
        lon: snap.lon,
        lat: snap.lat,
        height: Math.max(400, snap.height || 4200),
        heading: typeof snap.heading === "number" ? snap.heading : Cesium.Math.toRadians(35),
        pitch: typeof snap.pitch === "number" ? snap.pitch : Cesium.Math.toRadians(-42),
        roll: 0
      },
      false,
    );
    syncDeferredTerrain(cameraAgl());
  }

  function setMapMode(mode) {
    if (!viewer) return;
    var next = mode === "flat-osm" || mode === "topo-3d" || mode === "sat-3d" ? mode : "sat-3d";
    if (next === mapMode) return;
    var fromFlat = mapMode === "flat-osm";
    if (next === "flat-osm") {
      // Remember the scout pose before dropping to a north-up chart.
      saved3dView = captureCamera();
      mapMode = next;
      setImageryForMode(mapMode);
      applyFlatChart(saved3dView);
    } else if (fromFlat && saved3dView) {
      mapMode = next;
      setImageryForMode(mapMode);
      applySaved3d(saved3dView);
    } else {
      // Sat ↔ topo: same camera, only the draped layer changes.
      mapMode = next;
      setImageryForMode(mapMode);
      viewer.scene.screenSpaceCameraController.enableTilt = true;
      viewer.scene.screenSpaceCameraController.enableLook = true;
      syncDeferredTerrain(cameraAgl());
    }
    updateLod(true);
    notifyModeChanged();
    paintPreviewModeBar();
    viewer.scene.requestRender();
  }

  function notifyModeChanged() {
    if (global.AndroidBridge && global.AndroidBridge.onMapModeChanged) {
      global.AndroidBridge.onMapModeChanged(mapMode);
    }
  }

  function paintPreviewModeBar() {
    var bar = document.getElementById("modeBar");
    if (!bar || bar.hidden) return;
    bar.querySelectorAll("button[data-mode]").forEach(function (btn) {
      if (btn.getAttribute("data-mode") === mapMode) {
        btn.classList.add("active");
      } else {
        btn.classList.remove("active");
      }
    });
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

    demTerrainProvider = TerrainBridge.createLocalTerrainProvider();
    ellipsoidTerrain = TerrainBridge.createEllipsoidTerrainProvider();
    // Start on smooth globe — DEM mesh engages when the camera gets close.
    try {
      viewer = createViewer(ellipsoidTerrain);
      terrainMeshActive = false;
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
    scene.globe.depthTestAgainstTerrain = false;
    scene.globe.terrainExaggeration = 1.0;
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

    setImageryForMode(mapMode);

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
    // Engage DEM immediately at the default scout altitude.
    syncDeferredTerrain(cameraAgl());
    updateLod(true);
    viewer.scene.requestRender();

    bindPreviewModeBar();

    if (global.AndroidBridge && global.AndroidBridge.onEngineReady) {
      global.AndroidBridge.onEngineReady();
    }

    pollDebug();
  }

  /** Lightweight mode UI for browser preview — Android uses Compose chips instead. */
  function bindPreviewModeBar() {
    var bar = document.getElementById("modeBar");
    if (!bar || isAndroid) return;
    bar.hidden = false;
    bar.querySelectorAll("button[data-mode]").forEach(function (btn) {
      btn.addEventListener(
        "click",
        function (evt) {
          evt.preventDefault();
          evt.stopPropagation();
          setMapMode(btn.getAttribute("data-mode"));
        },
        true,
      );
    });
    var dl = document.getElementById("downloadAreaBtn");
    if (dl) {
      dl.addEventListener(
        "click",
        function (evt) {
          evt.preventDefault();
          evt.stopPropagation();
          downloadAreaAroundCamera(18).catch(function () {});
        },
        true,
      );
    }
    paintPreviewModeBar();
  }

  /** Meters above ground — ellipsoid height is wrong on tall peaks for LOD. */
  function cameraAgl() {
    var carto = viewer.camera.positionCartographic;
    if (!terrainMeshActive) {
      return Math.max(100, carto.height);
    }
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
    // Phone-safe DEM: Android max L12 (~76 m mesh / ~30 m Terrarium source).
    var maxTerrain = isAndroid ? 12 : 13;
    if (mapMode === "flat-osm") {
      // Flat OSM is cheap — push raster detail without DEM cost.
      if (agl > 2.0e5) imageryZ = 10;
      else if (agl > 5.0e4) imageryZ = 13;
      else if (agl > 1.2e4) imageryZ = 15;
      else if (agl > 3.0e3) imageryZ = 17;
      else imageryZ = 19;
      return {
        sse: 8,
        imageryZ: imageryZ,
        resolutionScale: scale,
        terrainLevel: 0,
        preloadSiblings: false,
        fogDensity: 0.00001,
        agl: agl
      };
    }
    if (agl > 2.0e5) {
      sse = isAndroid ? 8.0 : 7.0;
      imageryZ = 12;
      terrainLevel = 6;
      preloadSiblings = true;
      fogDensity = 0.00002;
    } else if (agl > 5.0e4) {
      sse = isAndroid ? 5.5 : 4.5;
      imageryZ = 14;
      terrainLevel = 8;
      preloadSiblings = true;
      fogDensity = 0.00003;
    } else if (agl > 1.2e4) {
      sse = isAndroid ? 3.2 : 2.6;
      imageryZ = mapMode === "topo-3d" ? 15 : 16;
      terrainLevel = 10;
      preloadSiblings = false;
      fogDensity = 0.00004;
    } else if (agl > 4.0e3) {
      sse = isAndroid ? 2.0 : 1.5;
      imageryZ = mapMode === "topo-3d" ? 16 : 17;
      terrainLevel = 11;
      preloadSiblings = false;
      fogDensity = 0.00005;
    } else if (agl > 1.2e3) {
      sse = isAndroid ? 1.2 : 0.85;
      imageryZ = mapMode === "topo-3d" ? 17 : 18;
      terrainLevel = maxTerrain;
      preloadSiblings = false;
      fogDensity = 0.00007;
      scale = lowPower ? 0.85 : 1.0;
    } else {
      // Close ski-scout — finest phone-safe DEM
      sse = isAndroid ? 0.85 : 0.45;
      imageryZ = mapMode === "topo-3d" ? 17 : isAndroid ? 18 : 19;
      terrainLevel = maxTerrain;
      preloadSiblings = false;
      fogDensity = 0.0001;
      scale = lowPower ? 0.9 : 1.0;
    }
    terrainLevel = Math.min(terrainLevel, maxTerrain);
    if (!terrainMeshActive) {
      terrainLevel = 0;
      sse = Math.max(sse, 3.5);
    }
    if (lowPower) {
      sse = Math.max(sse, 1.6);
      imageryZ = Math.min(imageryZ, 16);
      terrainLevel = Math.min(terrainLevel, 11);
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
    var agl = cameraAgl();
    syncDeferredTerrain(agl);
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
      if (mapMode !== "flat-osm" && terrainMeshActive && agl < 8000) {
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
      if (mapMode === "sat-3d") {
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
      }
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
        d.mapMode = mapMode;
        d.terrainMesh = terrainMeshActive;
        if (global.AndroidBridge && global.AndroidBridge.onTerrainDebug) {
          global.AndroidBridge.onTerrainDebug(JSON.stringify(d));
        }
      })
      .catch(function () {})
      .finally(function () {
        setTimeout(pollDebug, 2500);
      });
  }

  function downloadAreaAroundCamera(radiusKm) {
    if (!viewer) return Promise.resolve({ ok: false });
    var c = viewer.camera.positionCartographic;
    var lat = Cesium.Math.toDegrees(c.latitude);
    var lon = Cesium.Math.toDegrees(c.longitude);
    var r = typeof radiusKm === "number" ? radiusKm : 18;
    return fetch(
      TerrainBridge.tileServerBase() +
        "/offline/pack?lat=" +
        encodeURIComponent(lat) +
        "&lon=" +
        encodeURIComponent(lon) +
        "&radiusKm=" +
        encodeURIComponent(r)
    ).then(function (res) {
      return res.json();
    });
  }

  global.TerrainApp = {
    flyTo: function (lat, lon, height) {
      if (!viewer) return;
      // Default into a ski-scout band: close enough for lines, high enough for context.
      height = height || 3200;
      var pitch =
        mapMode === "flat-osm"
          ? Cesium.Math.toRadians(-90)
          : Cesium.Math.toRadians(-40);
      viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(lon, lat, height),
        orientation: {
          heading: Cesium.Math.toRadians(28),
          pitch: pitch,
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
      if (mapMode === "flat-osm") {
        setMapMode("sat-3d");
        return;
      }
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
          pitch:
            mapMode === "flat-osm"
              ? Cesium.Math.toRadians(-90)
              : Cesium.Math.toRadians(-40),
          roll: 0
        },
        duration: 0.5
      });
    },
    setTerrainExaggeration: function (amount) {
      if (!viewer || !terrainMeshActive) return;
      viewer.scene.globe.terrainExaggeration =
        typeof amount === "number" ? Math.max(1, Math.min(amount, 2.5)) : 1.45;
      viewer.scene.requestRender();
    },
    setMapMode: setMapMode,
    getMapMode: function () {
      return mapMode;
    },
    getCameraCenter: function () {
      return captureCamera();
    },
    downloadArea: function (radiusKm) {
      return downloadAreaAroundCamera(radiusKm);
    },
    attributionForMode: function () {
      return MODE_ATTRIBUTION[mapMode] || "";
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
