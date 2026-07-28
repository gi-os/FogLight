/**
 * Fog of World sync-format constants and coordinate math.
 * Format (reverse-engineered, see CaviarChen/Fog-of-World-Data-Parser):
 *   world = 512x512 tiles; tile = 128x128 blocks; block = 64x64 bitmap.
 */
export const MAP_WIDTH = 512;
export const TILE_WIDTH = 128;
export const BITMAP_WIDTH = 64;
export const CELLS_PER_TILE = TILE_WIDTH * BITMAP_WIDTH; // 8192

const FILENAME_MASK1 = "olhwjsktri";

/** Web-mercator tile corner -> lng/lat (tile coords may be fractional). */
export function tileXYToLngLat(x: number, y: number): [number, number] {
  const lng = (x / MAP_WIDTH) * 360 - 180;
  const lat = (Math.atan(Math.sinh(Math.PI - (2 * Math.PI * y) / MAP_WIDTH)) * 180) / Math.PI;
  return [lng, lat];
}

/** lng/lat -> fractional FoW tile coordinates. */
export function lngLatToTileXY(lng: number, lat: number): [number, number] {
  const x = ((lng + 180) / 360) * MAP_WIDTH;
  const rad = (lat * Math.PI) / 180;
  const y = ((Math.PI - Math.asinh(Math.tan(rad))) / (2 * Math.PI)) * MAP_WIDTH;
  return [x, y];
}

/** lng/lat -> global cell indices (0 .. 512*8192). */
export function lngLatToCell(lng: number, lat: number): [number, number] {
  const [tx, ty] = lngLatToTileXY(lng, lat);
  return [Math.floor(tx * CELLS_PER_TILE), Math.floor(ty * CELLS_PER_TILE)];
}

export function tileId(x: number, y: number): number {
  return y * MAP_WIDTH + x;
}

/** Decode a Sync/ filename's embedded tile id, or null if not a tile file. */
export function decodeTileFilename(filename: string): number | null {
  const body = filename.slice(4, -2);
  let id = 0;
  for (const ch of body) {
    const v = FILENAME_MASK1.indexOf(ch);
    if (v < 0) return null;
    id = id * 10 + v;
  }
  return id;
}

/** [west, south, east, north] bounds of a FoW tile. */
export function tileBounds(x: number, y: number): [number, number, number, number] {
  const [w, n] = tileXYToLngLat(x, y);
  const [e, s] = tileXYToLngLat(x + 1, y + 1);
  return [w, s, e, n];
}
