import { darkMatter } from "./mapStyle/darkMatter";
import { positron } from "./mapStyle/positron";
import type { Palette } from "./mapStyle/types";

const TILE_BASE = "https://tiles.openstreetmap.us/vector";

export function buildMapStyle(invertColors = false, offlineOnly = false) {
  const tileUrl = (path: string) =>
    offlineOnly
      ? `http://localhost:0/${path}/{z}/{x}/{y}`
      : `${TILE_BASE}/${path}/{z}/{x}/{y}.mvt`;

  const c: Palette = invertColors ? positron : darkMatter;

  return JSON.stringify({
    version: 8,
    glyphs: "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf",
    sources: {
      osm: {
        type: "vector",
        tiles: [tileUrl("openmaptiles")],
        minzoom: 0,
        maxzoom: 14,
        attribution: "© OpenStreetMap contributors",
      },
    },
    layers: [
      { id: "background", type: "background", paint: { "background-color": c.background } },
      { id: "landcover", type: "fill", source: "osm", "source-layer": "landcover", paint: { "fill-color": c.landcover } },
      { id: "landuse", type: "fill", source: "osm", "source-layer": "landuse", paint: { "fill-color": c.landuse } },
      { id: "park", type: "fill", source: "osm", "source-layer": "park", paint: { "fill-color": c.park } },
      { id: "water", type: "fill", source: "osm", "source-layer": "water", paint: { "fill-color": c.waterFill, "fill-opacity": 0.3 } },
      { id: "waterway", type: "line", source: "osm", "source-layer": "waterway", paint: { "line-color": c.waterway, "line-width": 1, "line-opacity": 0.3 } },
      {
        id: "roads-minor", type: "line", source: "osm", "source-layer": "transportation",
        filter: ["match", ["get", "class"], ["minor", "service", "track", "path"], true, false],
        paint: { "line-color": c.roads.minor, "line-width": 1 },
      },
      {
        id: "roads-medium", type: "line", source: "osm", "source-layer": "transportation",
        filter: ["match", ["get", "class"], ["secondary", "tertiary"], true, false],
        paint: { "line-color": c.roads.medium, "line-width": 1.5 },
      },
      {
        id: "roads-major", type: "line", source: "osm", "source-layer": "transportation",
        filter: ["match", ["get", "class"], ["motorway", "trunk", "primary"], true, false],
        paint: { "line-color": c.roads.major, "line-width": 2 },
      },
      {
        id: "place-labels", type: "symbol", source: "osm", "source-layer": "place",
        layout: {
          "text-field": ["get", "name"],
          "text-font": ["Noto Sans Regular"],
          "text-size": ["match", ["get", "class"], "city", 14, "town", 12, 10],
        },
        paint: { "text-color": c.labels, "text-halo-color": c.textHalo, "text-halo-width": 1.5 },
      },
    ],
  });
}

export function trailColor(invertColors: boolean): string {
  return invertColors ? positron.route : darkMatter.route;
}
