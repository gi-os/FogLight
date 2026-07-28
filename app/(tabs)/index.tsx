import Geolocation from "@react-native-community/geolocation";
import {
  Camera,
  type CameraRef,
  CircleLayer,
  LineLayer,
  MapView,
  ShapeSource,
} from "@maplibre/maplibre-react-native";
import { useFocusEffect } from "expo-router";
import { useCallback, useMemo, useRef, useState } from "react";
import { PermissionsAndroid, StyleSheet, View } from "react-native";
import { HapticPressable } from "@/components/HapticPressable";
import { StyledText } from "@/components/StyledText";
import { useInvertColors } from "@/contexts/InvertColorsContext";
import { listImported, readImported } from "@/services/cloud/dropbox";
import { readDay, todayKey, toLineString } from "@/services/trackStore";
import { parseGpx } from "@/utils/parseGpx";
import { buildMapStyle, trailColor } from "@/utils/mapStyle";
import { n } from "@/utils/scaling";

const TRAIL_POLL_MS = 15_000;

export default function MapScreen() {
  const { invertColors } = useInvertColors();
  const cameraRef = useRef<CameraRef>(null);
  const [coords, setCoords] = useState<[number, number] | null>(null);
  const [trail, setTrail] = useState<ReturnType<typeof toLineString> | null>(null);
  const [importedTrails, setImportedTrails] = useState<GeoJSON.FeatureCollection | null>(null);
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
      };
    }, [])
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
        attributionEnabled={false}
        compassEnabled={false}
        logoEnabled={false}
        pitchEnabled={false}
        rotateEnabled={false}
        mapStyle={mapStyle}
        style={styles.map}
      >
        <Camera ref={cameraRef} />
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
});
