import type { TrackPoint } from "./trackStore";

/** Build a GPX 1.1 document (Fog of World-importable) from a day's points. */
export function buildGpx(day: string, points: TrackPoint[]): string {
  const trkpts = points
    .map((p) => {
      const time = new Date(p.ts).toISOString();
      return `      <trkpt lat="${p.lat}" lon="${p.lng}"><time>${time}</time></trkpt>`;
    })
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="LightFog" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${day}</name>
    <trkseg>
${trkpts}
    </trkseg>
  </trk>
</gpx>
`;
}
