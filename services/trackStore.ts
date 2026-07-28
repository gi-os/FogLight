import * as FileSystem from "expo-file-system/legacy";
import { tracksDir } from "recorder";

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
