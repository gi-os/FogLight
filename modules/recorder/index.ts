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

/** Rasterize a Fog of World tile file to a PNG. Returns file path or null. */
export async function fowRenderTile(
  path: string,
  sizePx: number,
  color: number
): Promise<string | null> {
  return (await RecorderModule?.fowRenderTile(path, sizePx, color)) ?? null;
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
