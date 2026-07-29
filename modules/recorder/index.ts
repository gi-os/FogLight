import { requireOptionalNativeModule } from "expo-modules-core";

const RecorderModule = requireOptionalNativeModule("Recorder");

/** Start the foreground recording service. Interval in ms between GPS fixes. */
export function startRecording(intervalMs = 10_000): void {
  RecorderModule?.start(intervalMs);
}

export function stopRecording(): void {
  RecorderModule?.stop();
}

export function isRecording(): boolean {
  return RecorderModule?.isRunning() ?? false;
}

export function isServiceAlive(): boolean {
  return RecorderModule?.isServiceAlive() ?? false;
}

/** If recording is enabled but the service died (OS kill), restart it. */
export function ensureRunning(intervalMs = 10_000): void {
  if (isRecording() && !isServiceAlive()) {
    RecorderModule?.start(intervalMs);
  }
}

/** Absolute path of the directory containing daily track CSVs (ts,lat,lng,acc). */
export function tracksDir(): string | null {
  return RecorderModule?.tracksDir() ?? null;
}

/** Fog styles: 0 smooth, 1 pixels, 2 pixels+Scale2x, 3 smooth without blur. */
export type FogStyle = 0 | 1 | 2 | 3;

/** Rasterize a Fog of World tile file to a PNG. Returns file path or null. */
export async function fowRenderTile(
  path: string,
  sizePx: number,
  color: number,
  style: FogStyle = 0,
  blurRadius = 1
): Promise<string | null> {
  return (
    (await RecorderModule?.fowRenderTile(path, sizePx, color, style, blurRadius)) ??
    null
  );
}

/** Rasterize a world overview PNG from a directory of FoW tile files. */
export async function fowRenderOverview(
  dirPath: string,
  sizePx: number,
  color: number
): Promise<string | null> {
  return (await RecorderModule?.fowRenderOverview(dirPath, sizePx, color)) ?? null;
}

/** True if the file inflates and parses as a Fog of World tile. */
export async function fowValidate(path: string): Promise<boolean> {
  return (await RecorderModule?.fowValidate(path)) ?? false;
}

/** Debug: step-by-step decode report for a FoW tile file. */
export async function fowInspect(path: string): Promise<string> {
  return (await RecorderModule?.fowInspect(path)) ?? "native module missing";
}

/** Toggle LightOS forced grayscale (needs WRITE_SECURE_SETTINGS via adb). */
export function setGrayscale(enabled: boolean): void {
  RecorderModule?.setGrayscale(enabled);
}

/** Suppress recording within radiusM of this point (e.g. home/work). */
export function setPrivacyZone(
  name: "home" | "work",
  lat: number,
  lng: number,
  radiusM = 500
): void {
  RecorderModule?.setPrivacyZone(name, lat, lng, radiusM);
}

export function clearPrivacyZone(name: "home" | "work"): void {
  RecorderModule?.clearPrivacyZone(name);
}

/** Hand Dropbox credentials to the native nightly tile-sync. */
export function setDropboxCreds(appKey: string, refreshToken: string): void {
  RecorderModule?.setDropboxCreds(appKey, refreshToken);
}

/** Enable/disable the nightly tracks -> FoW tiles -> Dropbox sync. */
export function setTileSync(enabled: boolean): void {
  RecorderModule?.setTileSync(enabled);
}

export function tileSyncReport(): string {
  return RecorderModule?.tileSyncReport() ?? "";
}

/** Convert completed days into FoW tiles and upload now. Returns a report. */
export async function fowConvertNow(): Promise<string> {
  return (await RecorderModule?.fowConvertNow()) ?? "native module missing";
}
