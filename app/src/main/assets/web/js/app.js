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
  var trailsDataSource = null;
  var trailsEnabled = false;
  var trailActivity = "HIKE";
  var lastLod = { imageryZ: 19, sse: 4, resolutionScale: 1 };

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
      msaaSamples: 1,
      contextOptions: {
        webgl: {
          alpha: false,
          antialias: true,
          powerPreference: "high-performance"
        }
      }
    });

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
    scene.fxaa = true;
    scene.postProcessStages.fxaa.enabled = true;
    scene.highDynamicRange = false;
    scene.globe.tileCacheSize = 300;
    // Show ancestors ASAP so holes (blue sky) never open while children load.
    scene.globe.loadingDescendantLimit = 1;
    scene.globe.preloadAncestors = true;
    scene.globe.preloadSiblings = true;
    scene.globe.maximumScreenSpaceError = 3.0;

    // ONE stable worldwide imagery layer — never tear it down while flying.
    viewer.imageryLayers.removeAll();
    imageryLayer = viewer.imageryLayers.addImageryProvider(
      TerrainBridge.createLocalImageryProvider(19)
    );
    imageryLayer.brightness = 1.02;
    imageryLayer.contrast = 1.04;
    imageryLayer.saturation = 1.02;
    imageryLayer.gamma = 0.95;

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

  function lodForHeight(height) {
    // Imagery max Z stays 19 on the provider; SSE decides how hard Cesium works.
    var sse;
    var scale = lowPower ? 0.85 : 1.0;
    var imageryZ;
    if (height > 2.0e6) {
      sse = 7.0;
      imageryZ = 12;
    } else if (height > 5.0e5) {
      sse = 4.5;
      imageryZ = 14;
    } else if (height > 1.0e5) {
      sse = 2.8;
      imageryZ = 16;
    } else if (height > 2.0e4) {
      sse = 1.8;
      imageryZ = 17;
    } else if (height > 4.0e3) {
      sse = 1.2;
      imageryZ = 18;
      scale = lowPower ? 0.9 : 1.0;
    } else {
      sse = 0.9;
      imageryZ = 19;
      scale = lowPower ? 0.95 : 1.0;
    }
    if (lowPower) {
      sse = Math.max(sse, 2.2);
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
      // After the camera settles, pull a sharper frame without thrashing mid-gesture.
      if (height < 80000) {
        var sharp = Math.max(0.75, lodForHeight(height).sse * 0.75);
        if (viewer.scene.globe.maximumScreenSpaceError > sharp) {
          viewer.scene.globe.maximumScreenSpaceError = sharp;
          lastLod.sse = sharp;
          viewer.scene.requestRender();
        }
      }
      if (height > 30000) return;
      var c = viewer.camera.positionCartographic;
      var lat = Cesium.Math.toDegrees(c.latitude);
      var lon = Cesium.Math.toDegrees(c.longitude);
      var z = Math.min(19, lastLod.imageryZ);
      fetch(
        TerrainBridge.tileServerBase() +
          "/imagery/prefetch?lat=" +
          lat +
          "&lon=" +
          lon +
          "&z=" +
          z
      ).catch(function () {});
    }, 700);
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

  function styleTrailEntities(dataSource) {
    var entities = dataSource.entities.values;
    for (var i = 0; i < entities.length; i++) {
      var e = entities[i];
      if (e.polyline) {
        e.polyline.width = 2.5;
        e.polyline.clampToGround = true;
        e.polyline.material = Cesium.Color.fromCssColorString("#FFB020").withAlpha(0.85);
      }
    }
  }

  function refreshTrails() {
    if (!viewer || !trailsDataSource || !trailsEnabled) return;
    var rect = viewer.camera.computeViewRectangle();
    if (!rect) return;
    var south = Cesium.Math.toDegrees(rect.south);
    var west = Cesium.Math.toDegrees(rect.west);
    var north = Cesium.Math.toDegrees(rect.north);
    var east = Cesium.Math.toDegrees(rect.east);
    if (north - south > 0.45 || east - west > 0.45) return;
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
      strokeWidth: 2.5
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
    setTrailsEnabled: function (enabled) {
      trailsEnabled = !!enabled;
      if (!trailsEnabled && trailsDataSource) {
        trailsDataSource.entities.removeAll();
        viewer.scene.requestRender();
      } else if (trailsEnabled) {
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
