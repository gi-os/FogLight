import * as FileSystem from "expo-file-system/legacy";
import { fowDir, fowDirPath } from "@/services/cloud/dropbox";
import { decodeTileFilename, MAP_WIDTH, tileBounds } from "./fowMath";

export type Corners = [
  GeoJSON.Position,
  GeoJSON.Position,
  GeoJSON.Position,
  GeoJSON.Position,
];

export type FowTile = {
  id: number;
  x: number;
  y: number;
  /** native fs path for the Kotlin codec */
  path: string;
  /** [[w,n],[e,n],[e,s],[w,s]] corners for ImageSource */
  corners: Corners;
};

export async function scanFowTiles(): Promise<FowTile[]> {
  try {
    const names = await FileSystem.readDirectoryAsync(fowDir());
    const tiles: FowTile[] = [];
    for (const name of names) {
      const id = decodeTileFilename(name);
      if (id == null) continue;
      const x = id % MAP_WIDTH;
      const y = Math.floor(id / MAP_WIDTH);
      const [w, s, e, n] = tileBounds(x, y);
      tiles.push({
        id,
        x,
        y,
        path: `${fowDirPath()}/${name}`,
        corners: [
          [w, n],
          [e, n],
          [e, s],
          [w, s],
        ] as Corners,
      });
    }
    return tiles;
  } catch {
    return [];
  }
}

/** FoW tiles intersecting [[maxLng,maxLat],[minLng,minLat]] visible bounds. */
export function tilesInBounds(
  tiles: FowTile[],
  ne: [number, number],
  sw: [number, number],
  limit = 12
): FowTile[] {
  const hits = tiles.filter((t) => {
    const [w, s, e, n] = [t.corners[0][0], t.corners[3][1], t.corners[1][0], t.corners[0][1]];
    return e >= sw[0] && w <= ne[0] && n >= sw[1] && s <= ne[1];
  });
  if (hits.length <= limit) return hits;
  const cx = (ne[0] + sw[0]) / 2;
  const cy = (ne[1] + sw[1]) / 2;
  return hits
    .sort((a, b) => {
      const da = (a.corners[0][0] - cx) ** 2 + (a.corners[0][1] - cy) ** 2;
      const db = (b.corners[0][0] - cx) ** 2 + (b.corners[0][1] - cy) ** 2;
      return da - db;
    })
    .slice(0, limit);
}

/** World corners for the overview image (web-mercator extent). */
export const WORLD_CORNERS: Corners = [
  [-180, 85.0511],
  [180, 85.0511],
  [180, -85.0511],
  [-180, -85.0511],
];
