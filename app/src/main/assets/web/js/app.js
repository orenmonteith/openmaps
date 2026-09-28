/**
 * OpenMaps Cesium viewer: LOD imagery, GPS on mesh, trails, battery-aware rendering.
 */
(function (global) {
  "use strict";

  var viewer = null;
  var imageryLayer = null;
  var currentImageryMaxZ = -1;
  var lowPower = false;
  var interactTimer = null;
  var settleTimer = null;
  var userEntity = null;
  var trailsDataSource = null;
  var trailsEnabled = true;
  var trailActivity = "HIKE";
  var lastLod = {
    imageryZ: -1,
    sse: 6,
    resolutionScale: 1,
    imagerySource: "—"
  };

  function init() {
    if (!global.Cesium || !global.TerrainBridge) {
      setTimeout(init, 50);
      return;
    }

    Cesium.Ion.defaultAccessToken = undefined;

    var terrainProvider = TerrainBridge.createLocalTerrainProvider();
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
    setImageryMaxLevel(14);

    var scene = viewer.scene;
    scene.globe.depthTestAgainstTerrain = true;
    scene.globe.terrainExaggeration = 1.0;
    scene.fog.enabled = true;
    scene.skyAtmosphere.show = true;
    scene.globe.enableLighting = false;
    scene.fxaa = false;
    scene.postProcessStages.fxaa.enabled = false;
    scene.highDynamicRange = false;
    scene.globe.tileCacheSize = 140;
    scene.globe.loadingDescendantLimit = 2;
    scene.globe.preloadAncestors = true;
    scene.globe.preloadSiblings = false;
    scene.globe.maximumScreenSpaceError = 6.0;

    var controller = scene.screenSpaceCameraController;
    controller.zoomFactor = 14.0;
    controller.minimumZoomDistance = 60.0;
    controller.maximumZoomDistance = 4.0e7;
    controller.inertiaZoom = 0.8;
    controller.enableCollisionDetection = true;

    // Refresh render when imagery tiles arrive (swap blurry parents faster).
    viewer.imageryLayers.layerAdded.addEventListener(function () {
      viewer.scene.requestRender();
    });
    scene.globe.imageryLayersUpdatedEvent.addEventListener(function () {
      viewer.scene.requestRender();
    });

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

    trailsDataSource = new Cesium.GeoJsonDataSource("trails");
    viewer.dataSources.add(trailsDataSource);

    bindInteractionThrottling();
    bindDynamicLod();
    updateLod(true);
    viewer.scene.requestRender();

    if (global.AndroidBridge && global.AndroidBridge.onEngineReady) {
      global.AndroidBridge.onEngineReady();
    }

    pollDebug();
  }

  function setImageryMaxLevel(maxZ) {
    if (!viewer) return;
    if (currentImageryMaxZ === maxZ) return;
    currentImageryMaxZ = maxZ;
    if (imageryLayer) {
      viewer.imageryLayers.remove(imageryLayer, false);
    }
    var provider = TerrainBridge.createLocalImageryProvider(maxZ);
    imageryLayer = viewer.imageryLayers.addImageryProvider(provider);
    // Bias toward sharper children when available.
    imageryLayer.brightness = 1.02;
    imageryLayer.contrast = 1.05;
    viewer.scene.requestRender();
  }

  function lodForHeight(height) {
    var sse;
    var imageryZ;
    var scale = lowPower ? 0.75 : 1.0;
    if (height > 2.0e6) {
      sse = 10.0;
      imageryZ = lowPower ? 12 : 13;
    } else if (height > 4.0e5) {
      sse = 7.0;
      imageryZ = lowPower ? 13 : 14;
    } else if (height > 8.0e4) {
      sse = 4.5;
      imageryZ = lowPower ? 14 : 16;
    } else if (height > 1.5e4) {
      sse = 2.4;
      imageryZ = lowPower ? 15 : 17;
    } else if (height > 3.0e3) {
      sse = 1.6;
      imageryZ = lowPower ? 16 : 18;
      scale = lowPower ? 0.8 : 1.0;
    } else {
      sse = 1.15;
      imageryZ = lowPower ? 17 : 19;
      scale = lowPower ? 0.85 : 1.0;
    }
    return { sse: sse, imageryZ: imageryZ, scale: scale };
  }

  function updateLod(force) {
    if (!viewer) return;
    var height = viewer.camera.positionCartographic.height;
    var lod = lodForHeight(height);
    if (viewer.scene.globe.maximumScreenSpaceError !== lod.sse) {
      viewer.scene.globe.maximumScreenSpaceError = lod.sse;
    }
    if (viewer.resolutionScale !== lod.scale) {
      viewer.resolutionScale = lod.scale;
    }
    setImageryMaxLevel(lod.imageryZ);
    lastLod.imageryZ = lod.imageryZ;
    lastLod.sse = lod.sse;
    lastLod.resolutionScale = lod.scale;
    reportClientLod();
    if (force) viewer.scene.requestRender();
  }

  function reportClientLod() {
    var url =
      TerrainBridge.tileServerBase() +
      "/client/lod?imageryZ=" +
      encodeURIComponent(lastLod.imageryZ) +
      "&sse=" +
      encodeURIComponent(lastLod.sse) +
      "&resolutionScale=" +
      encodeURIComponent(lastLod.resolutionScale);
    fetch(url).catch(function () {});
  }

  function schedulePrefetch() {
    if (settleTimer) clearTimeout(settleTimer);
    settleTimer = setTimeout(function () {
      if (!viewer || lowPower) return;
      var c = viewer.camera.positionCartographic;
      var lat = Cesium.Math.toDegrees(c.latitude);
      var lon = Cesium.Math.toDegrees(c.longitude);
      var z = lastLod.imageryZ;
      if (z < 14) return;
      fetch(
        TerrainBridge.tileServerBase() +
          "/imagery/prefetch?lat=" +
          lat +
          "&lon=" +
          lon +
          "&z=" +
          z
      ).catch(function () {});
      if (trailsEnabled) {
        refreshTrails();
      }
    }, 700);
  }

  function bindDynamicLod() {
    viewer.camera.changed.addEventListener(function () {
      updateLod(false);
      schedulePrefetch();
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

  function styleTrailEntities(dataSource) {
    var entities = dataSource.entities.values;
    for (var i = 0; i < entities.length; i++) {
      var e = entities[i];
      if (e.polyline) {
        e.polyline.width = 3;
        e.polyline.clampToGround = true;
        e.polyline.material = Cesium.Color.fromCssColorString("#FFB020").withAlpha(0.9);
      }
    }
  }

  function refreshTrails() {
    if (!viewer || !trailsDataSource) return;
    var rect = viewer.camera.computeViewRectangle();
    if (!rect) return;
    var south = Cesium.Math.toDegrees(rect.south);
    var west = Cesium.Math.toDegrees(rect.west);
    var north = Cesium.Math.toDegrees(rect.north);
    var east = Cesium.Math.toDegrees(rect.east);
    var url =
      TerrainBridge.tileServerBase() +
      "/trails?south=" +
      south +
      "&west=" +
      west +
      "&north=" +
      north +
      "&east=" +
      east +
      "&activity=" +
      trailActivity;
    Cesium.GeoJsonDataSource.load(url, {
      clampToGround: true,
      stroke: Cesium.Color.fromCssColorString("#FFB020"),
      strokeWidth: 3
    })
      .then(function (ds) {
        trailsDataSource.entities.removeAll();
        var vals = ds.entities.values;
        for (var i = 0; i < vals.length; i++) {
          trailsDataSource.entities.add(vals[i]);
        }
        styleTrailEntities(trailsDataSource);
        viewer.scene.requestRender();
      })
      .catch(function () {});
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
        setTimeout(pollDebug, 2000);
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
    showUserLocation: function (lat, lon) {
      if (!viewer || !userEntity) return;
      userEntity.position = Cesium.Cartesian3.fromDegrees(lon, lat);
      userEntity.show = true;
      viewer.scene.requestRender();
    },
    setTrailsEnabled: function (enabled) {
      trailsEnabled = !!enabled;
      if (!trailsEnabled && trailsDataSource) {
        trailsDataSource.entities.removeAll();
        viewer.scene.requestRender();
      } else {
        refreshTrails();
      }
    },
    setTrailActivity: function (activity) {
      trailActivity = (activity || "HIKE").toUpperCase();
      if (trailsEnabled) refreshTrails();
    },
    refreshTrails: refreshTrails,
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
