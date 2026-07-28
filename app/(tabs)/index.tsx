import Geolocation from "@react-native-community/geolocation";
import { fowRenderOverview, fowRenderTile } from "@/modules/recorder";
import {
  Camera,
  type CameraRef,
  CircleLayer,
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
import { readDay, todayKey, toLineString } from "@/services/trackStore";
import {
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
const TILE_PX = 1024;
const OVERVIEW_PX = 2048;
const OVERVIEW_MAX_ZOOM = 8;
// ARGB as numbers (passed to the native rasterizer)
const EXPLORED_DARK = 0xb4ffffff; // white @ ~70% on the dark map
const EXPLORED_LIGHT = 0x59000000; // black @ ~35% on the inverted map

export default function MapScreen() {
  const { invertColors } = useInvertColors();
  const cameraRef = useRef<CameraRef>(null);
  const mapRef = useRef<MapViewRef>(null);
  const [coords, setCoords] = useState<[number, number] | null>(null);
  const [trail, setTrail] = useState<ReturnType<typeof toLineString> | null>(null);
  const [importedTrails, setImportedTrails] = useState<GeoJSON.FeatureCollection | null>(null);
  const [fowTiles, setFowTiles] = useState<FowTile[]>([]);
  const fowTilesRef = useRef<FowTile[]>([]);
  const [overviewUri, setOverviewUri] = useState<string | null>(null);
  const overviewUriRef = useRef<string | null>(null);
  const [showOverview, setShowOverview] = useState(true);
  const [tileImages, setTileImages] = useState<
    { key: string; uri: string; corners: [number, number][] }[]
  >([]);
  const renderedRef = useRef<Map<string, string>>(new Map());
  const [fogDebug, setFogDebug] = useState("");
  const hasCentered = useRef(false);

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
        if (!cancelled) setTrail(points.length > 1 ? toLineString(points) : null);
      };
      loadTrail();
      pollId = setInterval(loadTrail, TRAIL_POLL_MS);

      const color = invertColors ? EXPLORED_LIGHT : EXPLORED_DARK;
      const pollFog = async () => {
        const map = mapRef.current;
        if (!map) return;
        if (fowTilesRef.current.length === 0) {
          if (!cancelled) setFogDebug("no FoW tiles imported");
          return;
        }
        try {
          const zoom = await map.getZoom();
          setShowOverview(zoom < OVERVIEW_MAX_ZOOM);
          if (zoom < OVERVIEW_MAX_ZOOM) {
            setTileImages([]);
            if (!cancelled)
              setFogDebug(
                `z${zoom.toFixed(1)} overview ${overviewUriRef.current ? "on" : "missing"} (${fowTilesRef.current.length} tiles)`
              );
            return;
          }
          const [ne, sw] = await map.getVisibleBounds();
          const needed = tilesInBounds(fowTilesRef.current, ne, sw);
          let failed = 0;
          const next: { key: string; uri: string; corners: [number, number][] }[] = [];
          for (const tile of needed) {
            const key = `fow-${tile.id}`;
            let uri = renderedRef.current.get(key);
            if (!uri) {
              const rendered = await fowRenderTile(tile.path, TILE_PX, color);
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
              `z${zoom.toFixed(1)} ${next.length}/${needed.length} fog imgs` +
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

      (async () => {
        try {
          const tiles = await scanFowTiles();
          if (cancelled) return;
          fowTilesRef.current = tiles;
          setFowTiles(tiles);
          if (tiles.length > 0) {
            const color = invertColors ? EXPLORED_LIGHT : EXPLORED_DARK;
            const uri = await fowRenderOverview(fowDirPath(), OVERVIEW_PX, color);
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
        if (watchId != null) Geolocation.clearWatch(watchId);
        if (pollId != null) clearInterval(pollId);
        clearInterval(fogPollId);
      };
    }, [invertColors])
  );

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
        {overviewUri && showOverview && fowTiles.length > 0 && (
          <ImageSource
            id="fow-overview"
            url={overviewUri}
            coordinates={WORLD_CORNERS}
          >
            <RasterLayer
              id="fow-overview-layer"
              style={{ rasterOpacity: 0.9, rasterResampling: "nearest" }}
            />
          </ImageSource>
        )}
        {!showOverview &&
          tileImages.map((img) => (
            <ImageSource
              id={img.key}
              key={img.key}
              url={img.uri}
              coordinates={img.corners}
            >
              <RasterLayer
                id={`${img.key}-layer`}
                style={{ rasterOpacity: 0.9, rasterResampling: "nearest" }}
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
        {trail && (
          <ShapeSource id="trail-source" shape={trail}>
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
