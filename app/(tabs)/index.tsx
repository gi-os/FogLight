import AsyncStorage from "@react-native-async-storage/async-storage";
import Geolocation from "@react-native-community/geolocation";
import {
  type FogStyle,
  fowRenderOverview,
  fowRenderTile,
  setGrayscale,
} from "@/modules/recorder";
import {
  Camera,
  type CameraRef,
  CircleLayer,
  FillLayer,
  ImageSource,
  LineLayer,
  MapView,
  type MapViewRef,
  RasterLayer,
  ShapeSource,
} from "@maplibre/maplibre-react-native";
import { useFocusEffect } from "expo-router";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { PermissionsAndroid, StyleSheet, View } from "react-native";
import { HapticPressable } from "@/components/HapticPressable";
import { StyledText } from "@/components/StyledText";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import { fowDirPath, listImported, readImported } from "@/services/cloud/dropbox";
import { healRecording } from "@/services/recordingState";
import { buildTrail, readDay, todayKey, type Trail } from "@/services/trackStore";
import {
  type Corners,
  type FowTile,
  scanFowTiles,
  tilesInBounds,
  WORLD_CORNERS,
} from "@/utils/fog/fowSource";
import { parseGpx } from "@/utils/parseGpx";
import { buildMapStyle, trailColor } from "@/utils/mapStyle";
import { n } from "@/utils/scaling";

const TRAIL_POLL_MS = 15_000;
const FOG_POLL_MS = 2_500;
const OVERVIEW_PX = 2048;
const TILE_MIN_ZOOM = 5.5;
const HIRES_ZOOM = 12.5;
// Crossfade: overview fades out z6->8 while detail tiles fade in z6->7.5
const OVERVIEW_OPACITY = ["interpolate", ["linear"], ["zoom"], 6, 1, 8, 0] as const;
const TILE_OPACITY = ["interpolate", ["linear"], ["zoom"], 6, 0, 7.5, 1] as const;

// Fog RGB choices — alpha comes from the persisted Fog Density setting.
const FOG_RGBS: Record<string, number> = {
  black: 0x0a0a0a,
  darkgrey: 0x3a3a3a,
  lightgrey: 0xbbbbbb,
  white: 0xf5f5f5,
};
const FOG_CSS: Record<string, string> = {
  black: "rgb(10,10,10)",
  darkgrey: "rgb(58,58,58)",
  lightgrey: "rgb(187,187,187)",
  white: "rgb(245,245,245)",
};
const DEFAULT_DENSITY = 78;

export default function MapScreen() {
  const { invertColors } = useInvertColors();
  const cameraRef = useRef<CameraRef>(null);
  const mapRef = useRef<MapViewRef>(null);
  const [coords, setCoords] = useState<[number, number] | null>(null);
  const [trail, setTrail] = useState<Trail | null>(null);
  const [importedTrails, setImportedTrails] = useState<GeoJSON.FeatureCollection | null>(null);
  const [fowTiles, setFowTiles] = useState<FowTile[]>([]);
  const fowTilesRef = useRef<FowTile[]>([]);
  const [overviewUri, setOverviewUri] = useState<string | null>(null);
  const overviewUriRef = useRef<string | null>(null);
  const [tileImages, setTileImages] = useState<
    { key: string; uri: string; corners: Corners }[]
  >([]);
  const renderedRef = useRef<Map<string, string>>(new Map());
  const prevCenterRef = useRef<[number, number] | null>(null);
  const staggerRef = useRef<ReturnType<typeof setTimeout>[]>([]);
  const [fogDebug, setFogDebug] = useState("");
  const [fogDensity, setFogDensity] = useState(DEFAULT_DENSITY);
  const [debugEnabled, setDebugEnabled] = useState(false);
  const [fogStyle, setFogStyle] = useState<FogStyle>(0);
  const [lightMap, setLightMap] = useState(false);
  const [fogColorName, setFogColorName] = useState("black");
  const [blurRadius, setBlurRadius] = useState(1);
  const [initialCam, setInitialCam] = useState<
    { center: [number, number]; zoom: number } | null | undefined
  >(undefined); // undefined = still loading, null = none saved
  const hasCentered = useRef(false);

  const lightBase = lightMap || invertColors;

  const fogColor = useMemo(() => {
    const alpha = Math.round((fogDensity / 100) * 255);
    const rgb = FOG_RGBS[fogColorName] ?? FOG_RGBS.black;
    return alpha * 0x1000000 + rgb;
  }, [fogDensity, fogColorName]);

  useEffect(() => {
    AsyncStorage.getItem("lastCamera")
      .then((raw) => {
        if (raw != null) {
          const v = JSON.parse(raw);
          if (Array.isArray(v.center) && Number.isFinite(v.zoom)) {
            setInitialCam({ center: v.center, zoom: v.zoom });
            return;
          }
        }
        setInitialCam(null);
      })
      .catch(() => setInitialCam(null));
  }, []);

  const fillOpacity = useMemo(
    () =>
      ["interpolate", ["linear"], ["zoom"], 6, 0, 7.5, fogDensity / 100] as const,
    [fogDensity]
  );

  const mapStyle = useMemo(() => buildMapStyle(lightBase), [lightBase]);

  useFocusEffect(
    useCallback(() => {
      let watchId: number | null = null;
      let pollId: ReturnType<typeof setInterval> | null = null;
      let cancelled = false;

      (async () => {
        // Check before asking. `request` on an already-granted permission is usually a no-op, but
        // this screen is focused constantly and each ask that outlives its caller leaves a
        // permission-dialog task behind — several hundred of them, once, which is enough to take
        // the system UI down with it. Ask only when there is something to ask for.
        const already = await PermissionsAndroid.check(
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
        );
        if (cancelled) return;
        if (!already) {
          const granted = await PermissionsAndroid.request(
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
          );
          if (granted !== PermissionsAndroid.RESULTS.GRANTED || cancelled) return;
        }
        // Recording can only be healed once location is actually ours to read.
        healRecording();
        watchId = Geolocation.watchPosition(
          (pos) => {
            const c: [number, number] = [pos.coords.longitude, pos.coords.latitude];
            setCoords(c);
            if (!hasCentered.current) {
              hasCentered.current = true;
              cameraRef.current?.setCamera({
                centerCoordinate: c,
                zoomLevel: 14,
                animationDuration: 400,
              });
            }
          },
          () => undefined,
          { enableHighAccuracy: true, distanceFilter: 5 }
        );
      })();

      const loadTrail = async () => {
        const points = await readDay(todayKey());
        if (!cancelled) setTrail(points.length > 1 ? buildTrail(points) : null);
      };
      loadTrail();
      pollId = setInterval(loadTrail, TRAIL_POLL_MS);

      const pollFog = async () => {
        const map = mapRef.current;
        if (!map) return;
        if (fowTilesRef.current.length === 0) {
          if (!cancelled) setFogDebug("no FoW tiles imported");
          return;
        }
        try {
          const zoom = await map.getZoom();
          try {
            const center = await map.getCenter();
            AsyncStorage.setItem(
              "lastCamera",
              JSON.stringify({ center, zoom })
            ).catch(() => undefined);
          } catch {
            // ignore
          }
          if (zoom < TILE_MIN_ZOOM) {
            setTileImages([]);
            if (!cancelled)
              setFogDebug(
                `z${zoom.toFixed(1)} overview ${overviewUriRef.current ? "on" : "missing"} (${fowTilesRef.current.length} tiles)`
              );
            return;
          }
          const sizePx =
            fogStyle === 2
              ? 1024
              : zoom >= HIRES_ZOOM
                ? 4096
                : 2048;
          const [ne, sw] = (await map.getVisibleBounds()) as [
            [number, number],
            [number, number],
          ];
          const needed = tilesInBounds(
            fowTilesRef.current,
            ne,
            sw,
            sizePx === 4096 || fogStyle === 2 ? 4 : 16
          );
          let failed = 0;
          const next: { key: string; uri: string; corners: Corners }[] = [];
          for (const tile of needed) {
            const key = `fow-${tile.id}-${sizePx}-${fogColor}-${fogStyle}-${blurRadius}`;
            let uri = renderedRef.current.get(key);
            if (!uri) {
              let rendered = await fowRenderTile(
                tile.path,
                sizePx,
                fogColor,
                fogStyle,
                blurRadius
              );
              if (!rendered && sizePx > 2048) {
                rendered = await fowRenderTile(
                  tile.path,
                  2048,
                  fogColor,
                  fogStyle,
                  blurRadius
                );
              }
              if (!rendered) {
                failed++;
                continue;
              }
              uri = `file://${rendered}`;
              renderedRef.current.set(key, uri);
            }
            next.push({ key, uri, corners: tile.corners });
          }
          if (!cancelled) {
            const origin = prevCenterRef.current ?? [
              (ne[0] + sw[0]) / 2,
              (ne[1] + sw[1]) / 2,
            ];
            prevCenterRef.current = [(ne[0] + sw[0]) / 2, (ne[1] + sw[1]) / 2];
            setTileImages((current) => {
              const currentKeys = new Set(current.map((t) => t.key));
              const kept = next.filter((t) => currentKeys.has(t.key));
              const fresh = next
                .filter((t) => !currentKeys.has(t.key))
                .sort((a, b) => {
                  const da =
                    (a.corners[0][0] - origin[0]) ** 2 +
                    (a.corners[0][1] - origin[1]) ** 2;
                  const db =
                    (b.corners[0][0] - origin[0]) ** 2 +
                    (b.corners[0][1] - origin[1]) ** 2;
                  return da - db;
                });
              // Directional reveal: closest-to-previous-view first, then sweep.
              for (const t of staggerRef.current) clearTimeout(t);
              staggerRef.current = fresh.slice(1).map((tile, i) =>
                setTimeout(() => {
                  if (!cancelled) {
                    setTileImages((cur) =>
                      cur.some((c) => c.key === tile.key) ? cur : [...cur, tile]
                    );
                  }
                }, 140 * (i + 1))
              );
              return fresh.length > 0 ? [...kept, fresh[0]] : kept;
            });
            setFogDebug(
              `z${zoom.toFixed(1)} ${next.length}/${needed.length} fog imgs @${sizePx}` +
                (failed ? ` (${failed} failed)` : "")
            );
          }
        } catch (e) {
          if (!cancelled)
            setFogDebug(`fog: ${e instanceof Error ? e.message : String(e)}`);
        }
      };
      const fogPollId = setInterval(pollFog, FOG_POLL_MS);
      pollFog();

      setGrayscale(false); // color while the map is open (no-op if not granted)

      AsyncStorage.getItem("fogDensity")
        .then((raw) => {
          if (raw != null && !cancelled) {
            const v = Number(JSON.parse(raw));
            if (Number.isFinite(v)) setFogDensity(v);
          }
        })
        .catch(() => undefined);
      AsyncStorage.getItem("fogColorName")
        .then((raw) => {
          if (!cancelled && raw != null) setFogColorName(JSON.parse(raw));
        })
        .catch(() => undefined);
      AsyncStorage.getItem("lightMap")
        .then((raw) => {
          if (!cancelled) setLightMap(raw != null && JSON.parse(raw) === true);
        })
        .catch(() => undefined);
      AsyncStorage.getItem("fogDebugEnabled")
        .then((raw) => {
          if (!cancelled) setDebugEnabled(raw != null && JSON.parse(raw) === true);
        })
        .catch(() => undefined);
      Promise.all([
        AsyncStorage.getItem("fogStyle"),
        AsyncStorage.getItem("fogScale2x"),
        AsyncStorage.getItem("fogBlurRadius"),
      ])
        .then(([styleRaw, s2xRaw, radiusRaw]) => {
          if (cancelled) return;
          const style = styleRaw != null ? JSON.parse(styleRaw) : "smooth";
          const s2x = s2xRaw != null ? JSON.parse(s2xRaw) === true : true;
          const radius = radiusRaw != null ? Number(JSON.parse(radiusRaw)) : 1;
          setFogStyle(style === "pixel" ? (s2x ? 2 : 1) : 0);
          setBlurRadius(Number.isFinite(radius) ? radius : 1);
        })
        .catch(() => undefined);

      (async () => {
        try {
          const tiles = await scanFowTiles();
          if (cancelled) return;
          fowTilesRef.current = tiles;
          setFowTiles(tiles);
          if (tiles.length > 0) {
            let uri = await fowRenderOverview(fowDirPath(), OVERVIEW_PX, fogColor);
            if (!uri) {
              uri = await fowRenderOverview(fowDirPath(), OVERVIEW_PX / 2, fogColor);
            }
            if (cancelled) return;
            if (uri) {
              overviewUriRef.current = `file://${uri}`;
              setOverviewUri(`file://${uri}`);
            } else {
              setFogDebug("overview render returned null");
            }
          }
        } catch (e) {
          if (!cancelled)
            setFogDebug(`overview: ${e instanceof Error ? e.message : String(e)}`);
        }
      })();

      (async () => {
        const names = await listImported();
        const features: GeoJSON.Feature[] = [];
        for (const name of names) {
          try {
            const route = parseGpx(await readImported(name));
            if (route && route.geojson.geometry.coordinates.length > 1) {
              features.push(route.geojson);
            }
          } catch {
            // skip unreadable file
          }
        }
        if (!cancelled) {
          setImportedTrails(
            features.length > 0
              ? { type: "FeatureCollection", features }
              : null
          );
        }
      })();

      return () => {
        cancelled = true;
        setGrayscale(true); // restore LightOS grayscale when leaving the map
        if (watchId != null) Geolocation.clearWatch(watchId);
        if (pollId != null) clearInterval(pollId);
        clearInterval(fogPollId);
        for (const t of staggerRef.current) clearTimeout(t);
      };
    }, [lightBase, fogColor, fogStyle, blurRadius])
  );

  // World polygon with holes where hi-res fog tiles are rendered — fogs
  // everything without tile data once the overview has faded out.
  const fogFill = useMemo(() => {
    if (fowTiles.length === 0) return null;
    const world: GeoJSON.Position[] = [
      [-180, -85.0511],
      [180, -85.0511],
      [180, 85.0511],
      [-180, 85.0511],
      [-180, -85.0511],
    ];
    const holes = tileImages.map((img) => {
      const [tl, tr, br, bl] = img.corners;
      return [tl, bl, br, tr, tl] as GeoJSON.Position[];
    });
    return {
      type: "Feature" as const,
      properties: {},
      geometry: { type: "Polygon" as const, coordinates: [world, ...holes] },
    };
  }, [tileImages, fowTiles.length]);

  const locate = () => {
    if (coords) {
      cameraRef.current?.setCamera({
        centerCoordinate: coords,
        zoomLevel: 14,
        animationDuration: 300,
      });
    }
  };

  if (initialCam === undefined) {
    return <View style={styles.container} />;
  }

  return (
    <View style={styles.container}>
      <MapView
        ref={mapRef}
        attributionEnabled={false}
        compassEnabled={false}
        logoEnabled={false}
        pitchEnabled={false}
        rotateEnabled={false}
        mapStyle={mapStyle}
        style={styles.map}
      >
        <Camera
          ref={cameraRef}
          defaultSettings={
            initialCam
              ? { centerCoordinate: initialCam.center, zoomLevel: initialCam.zoom }
              : undefined
          }
        />
        {overviewUri && fowTiles.length > 0 && (
          <ImageSource
            id="fow-overview"
            url={overviewUri}
            coordinates={WORLD_CORNERS}
          >
            <RasterLayer
              id="fow-overview-layer"
              style={{
                rasterOpacity: OVERVIEW_OPACITY as unknown as number,
                rasterFadeDuration: 600,
              }}
            />
          </ImageSource>
        )}
        {fogFill && (
          <ShapeSource id="fog-fill-source" shape={fogFill}>
            <FillLayer
              id="fog-fill-layer"
              style={{
                fillColor: FOG_CSS[fogColorName] ?? FOG_CSS.black,
                fillOpacity: fillOpacity as unknown as number,
                fillAntialias: false,
              }}
            />
          </ShapeSource>
        )}
        {tileImages.map((img) => (
          <ImageSource
            id={img.key}
            key={img.key}
            url={img.uri}
            coordinates={img.corners}
          >
            <RasterLayer
              id={`${img.key}-layer`}
              style={{
                rasterOpacity: TILE_OPACITY as unknown as number,
                rasterFadeDuration: 600,
                ...(fogStyle === 1 || fogStyle === 2
                  ? { rasterResampling: "nearest" as const }
                  : {}),
              }}
            />
          </ImageSource>
        ))}
        {importedTrails && (
          <ShapeSource id="imported-source" shape={importedTrails}>
            <LineLayer
              id="imported-lines"
              style={{
                lineColor: lightBase ? "#888888" : "#777777",
                lineWidth: 2,
                lineCap: "round",
                lineJoin: "round",
              }}
            />
          </ShapeSource>
        )}
        {trail?.lines && (
          <ShapeSource id="trail-source" shape={trail.lines}>
            <LineLayer
              id="trail-line"
              style={{
                lineColor: trailColor(lightBase),
                lineWidth: 3,
                lineCap: "round",
                lineJoin: "round",
              }}
            />
          </ShapeSource>
        )}
        {trail?.gapDots && (
          <ShapeSource id="trail-gaps-source" shape={trail.gapDots}>
            <CircleLayer
              id="trail-gap-rings"
              style={{
                circleRadius: [
                  "interpolate",
                  ["exponential", 2],
                  ["zoom"],
                  10,
                  2,
                  16,
                  12,
                ] as unknown as number,
                circleColor: "rgba(0,0,0,0)",
                circleOpacity: 0,
                circleStrokeOpacity: [
                  "interpolate",
                  ["linear"],
                  ["zoom"],
                  10.5,
                  0,
                  11.5,
                  1,
                ] as unknown as number,
                circleStrokeColor: trailColor(lightBase),
                circleStrokeWidth: [
                  "interpolate",
                  ["linear"],
                  ["zoom"],
                  10,
                  1.5,
                  16,
                  3,
                ] as unknown as number,
              }}
            />
          </ShapeSource>
        )}
        {coords && (
          <ShapeSource
            id="me-source"
            shape={{
              type: "Feature",
              properties: {},
              geometry: { type: "Point", coordinates: coords },
            }}
          >
            <CircleLayer
              id="me-dot"
              style={{
                circleRadius: 6,
                circleColor: lightBase ? "black" : "white",
                circleStrokeColor: lightBase ? "white" : "black",
                circleStrokeWidth: 2,
              }}
            />
          </ShapeSource>
        )}
      </MapView>
      <HapticPressable onPress={locate} style={styles.locateButton}>
        <StyledText style={styles.locateLabel}>LOCATE</StyledText>
      </HapticPressable>
      {debugEnabled && fogDebug !== "" && (
        <View style={styles.debugBox}>
          <StyledText style={styles.debugText}>{fogDebug}</StyledText>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  map: { flex: 1 },
  locateButton: {
    position: "absolute",
    bottom: n(16),
    right: n(16),
    backgroundColor: "black",
    borderColor: "white",
    borderWidth: 1,
    paddingHorizontal: n(12),
    paddingVertical: n(8),
  },
  locateLabel: { fontSize: n(14) },
  debugBox: {
    position: "absolute",
    top: n(8),
    left: n(8),
    backgroundColor: "rgba(0,0,0,0.7)",
    paddingHorizontal: n(6),
    paddingVertical: n(3),
  },
  debugText: { fontSize: n(10), color: "white" },
});
