import * as FileSystem from "expo-file-system/legacy";
import { tracksDir } from "@/modules/recorder";

export type TrackPoint = {
  ts: number;
  lat: number;
  lng: number;
  acc: number;
};

function dirUri(): string | null {
  const dir = tracksDir();
  return dir ? `file://${dir}` : null;
}

export function todayKey(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export async function listDays(): Promise<string[]> {
  const dir = dirUri();
  if (!dir) return [];
  try {
    const files = await FileSystem.readDirectoryAsync(dir);
    return files
      .filter((f) => f.endsWith(".csv"))
      .map((f) => f.replace(".csv", ""))
      .sort()
      .reverse();
  } catch {
    return [];
  }
}

export async function readDay(day: string): Promise<TrackPoint[]> {
  const dir = dirUri();
  if (!dir) return [];
  try {
    const content = await FileSystem.readAsStringAsync(`${dir}/${day}.csv`);
    const points: TrackPoint[] = [];
    for (const line of content.split("\n")) {
      const parts = line.split(",");
      if (parts.length < 4) continue;
      const ts = Number(parts[0]);
      const lat = Number(parts[1]);
      const lng = Number(parts[2]);
      const acc = Number(parts[3]);
      if (Number.isFinite(lat) && Number.isFinite(lng)) {
        points.push({ ts, lat, lng, acc });
      }
    }
    return points;
  } catch {
    return [];
  }
}

export function toLineString(points: TrackPoint[]) {
  return {
    type: "Feature" as const,
    properties: {},
    geometry: {
      type: "LineString" as const,
      coordinates: points.map((p) => [p.lng, p.lat] as [number, number]),
    },
  };
}

// --- gap-aware trail building ---------------------------------------------
// A trail splits only when the signal was actually lost: a long time gap
// while displaced (subway), or a physically impossible speed. Distance alone
// never splits, so fast drives with sparse fixes stay connected.
const GAP_MS = 150_000; // 2.5 min without a fix
const GAP_M = 150;
const MAX_SPEED_MPS = 80; // ~290 km/h

function haversineM(a: TrackPoint, b: TrackPoint): number {
  const R = 6371000;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLng = ((b.lng - a.lng) * Math.PI) / 180;
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((a.lat * Math.PI) / 180) *
      Math.cos((b.lat * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

export type Trail = {
  lines: GeoJSON.FeatureCollection | null;
  gapDots: GeoJSON.FeatureCollection | null;
  miles: number;
};

export function buildTrail(points: TrackPoint[]): Trail {
  const segments: TrackPoint[][] = [];
  let current: TrackPoint[] = [];
  let meters = 0;

  for (const p of points) {
    const prev = current[current.length - 1];
    if (!prev) {
      current.push(p);
      continue;
    }
    const d = haversineM(prev, p);
    const dt = p.ts - prev.ts;
    const speed = d / Math.max(dt / 1000, 1);
    const isGap = (dt > GAP_MS && d > GAP_M) || speed > MAX_SPEED_MPS;
    if (isGap) {
      segments.push(current);
      current = [p];
    } else {
      meters += d;
      current.push(p);
    }
  }
  if (current.length > 0) segments.push(current);

  const lineFeatures = segments
    .filter((seg) => seg.length > 1)
    .map((seg) => ({
      type: "Feature" as const,
      properties: {},
      geometry: {
        type: "LineString" as const,
        coordinates: seg.map((p) => [p.lng, p.lat] as [number, number]),
      },
    }));

  const dots: GeoJSON.Feature[] = [];
  for (let i = 0; i < segments.length - 1; i++) {
    const end = segments[i][segments[i].length - 1];
    const start = segments[i + 1][0];
    dots.push(
      {
        type: "Feature",
        properties: {},
        geometry: { type: "Point", coordinates: [end.lng, end.lat] },
      },
      {
        type: "Feature",
        properties: {},
        geometry: { type: "Point", coordinates: [start.lng, start.lat] },
      }
    );
  }

  return {
    lines:
      lineFeatures.length > 0
        ? { type: "FeatureCollection", features: lineFeatures }
        : null,
    gapDots:
      dots.length > 0 ? { type: "FeatureCollection", features: dots } : null,
    miles: meters / 1609.34,
  };
}
