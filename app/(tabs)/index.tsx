import AsyncStorage from "@react-native-async-storage/async-storage";
import Geolocation from "@react-native-community/geolocation";
import { fowRenderOverview, fowRenderTile, setGrayscale } from "@/modules/recorder";
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
import { useCallback, useMemo, useRef, useState } from "react";
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

// Fog RGB bases — alpha comes from the persisted Fog Density setting.
const FOG_RGB_DARK = 0x0a0a0a;
const FOG_RGB_LIGHT = 0xf0f0f0;
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
  const [fogDebug, setFogDebug] = useState("");
  const [fogDensity, setFogDensity] = useState(DEFAULT_DENSITY);
  const hasCentered = useRef(false);

  const fogColor = useMemo(() => {
    const alpha = Math.round((fogDensity / 100) * 255);
    const rgb = invertColors ? FOG_RGB_LIGHT : FOG_RGB_DARK;
    return alpha * 0x1000000 + rgb;
  }, [fogDensity, invertColors]);

  const fillOpacity = useMemo(
    () =>
      ["interpolate", ["linear"], ["zoom"], 6, 0, 7.5, fogDensity / 100] as const,
    [fogDensity]
  );

  const mapStyle = useMemo(() => buildMapStyle(invertColors), [invertColors]);

  useFocusEffect(
    useCallback(() => {
      let watchId: number | null = null;
      let pollId: ReturnType<typeof setInterval> | null = null;
      let cancelled = false;

      (async () => {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
        );
        if (granted !== PermissionsAndroid.RESULTS.GRANTED || cancelled) return;
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
          if (zoom < TILE_MIN_ZOOM) {
            setTileImages([]);
            if (!cancelled)
              setFogDebug(
                `z${zoom.toFixed(1)} overview ${overviewUriRef.current ? "on" : "missing"} (${fowTilesRef.current.length} tiles)`
              );
            return;
          }
          const sizePx = zoom >= HIRES_ZOOM ? 2048 : 1024;
          const [ne, sw] = (await map.getVisibleBounds()) as [
            [number, number],
            [number, number],
          ];
          const needed = tilesInBounds(fowTilesRef.current, ne, sw, 16);
          let failed = 0;
          const next: { key: string; uri: string; corners: Corners }[] = [];
          for (const tile of needed) {
            const key = `fow-${tile.id}-${sizePx}-${fogColor}`;
            let uri = renderedRef.current.get(key);
            if (!uri) {
              const rendered = await fowRenderTile(tile.path, sizePx, fogColor);
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
            setTileImages(next);
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

      healRecording();
      setGrayscale(false); // color while the map is open (no-op if not granted)

      AsyncStorage.getItem("fogDensity")
        .then((raw) => {
          if (raw != null && !cancelled) {
            const v = Number(JSON.parse(raw));
            if (Number.isFinite(v)) setFogDensity(v);
          }
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
      };
    }, [invertColors, fogColor])
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
        <Camera ref={cameraRef} />
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
                rasterFadeDuration: 300,
              }}
            />
          </ImageSource>
        )}
        {fogFill && (
          <ShapeSource id="fog-fill-source" shape={fogFill}>
            <FillLayer
              id="fog-fill-layer"
              style={{
                fillColor: invertColors ? "rgb(240,240,240)" : "rgb(10,10,10)",
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
                rasterFadeDuration: 300,
              }}
            />
          </ImageSource>
        ))}
        {importedTrails && (
          <ShapeSource id="imported-source" shape={importedTrails}>
            <LineLayer
              id="imported-lines"
              style={{
                lineColor: invertColors ? "#888888" : "#777777",
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
                lineColor: trailColor(invertColors),
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
                circleStrokeColor: trailColor(invertColors),
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
                circleColor: invertColors ? "black" : "white",
                circleStrokeColor: invertColors ? "white" : "black",
                circleStrokeWidth: 2,
              }}
            />
          </ShapeSource>
        )}
      </MapView>
      <HapticPressable onPress={locate} style={styles.locateButton}>
        <StyledText style={styles.locateLabel}>LOCATE</StyledText>
      </HapticPressable>
      {fogDebug !== "" && (
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
