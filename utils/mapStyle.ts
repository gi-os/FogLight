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
      { id: "background", type: "background", paint: { "background-color": invertColors ? c.background : "#161616" } },
      { id: "landcover", type: "fill", source: "osm", "source-layer": "landcover", paint: { "fill-color": c.landcover } },
      { id: "landuse", type: "fill", source: "osm", "source-layer": "landuse", paint: { "fill-color": c.landuse } },
      { id: "park", type: "fill", source: "osm", "source-layer": "park", paint: { "fill-color": c.park } },
      // Solid water so land/ocean read clearly even at world zoom.
      {
        id: "water", type: "fill", source: "osm", "source-layer": "water",
        paint: { "fill-color": invertColors ? "#c2c8ca" : "#050607", "fill-opacity": 1 },
      },
      { id: "waterway", type: "line", source: "osm", "source-layer": "waterway", paint: { "line-color": c.waterway, "line-width": 1, "line-opacity": 0.5 } },
      // Country borders help continents read when zoomed out.
      {
        id: "boundary-country", type: "line", source: "osm", "source-layer": "boundary",
        filter: ["<=", ["get", "admin_level"], 2],
        paint: { "line-color": invertColors ? "#b0b0b0" : "#3a3a3a", "line-width": 1 },
      },
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
