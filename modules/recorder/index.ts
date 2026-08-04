import { requireOptionalNativeModule } from "expo-modules-core";

const RecorderModule = requireOptionalNativeModule("Recorder");

/**
 * Start the foreground recording service. Interval in ms between GPS fixes.
 *
 * Returns null on success, or the reason it refused. It can refuse — the location permission may
 * not be granted, or the system may decline a background start — and the caller needs to know,
 * because the alternative is asking again. Retrying a start that cannot work is how this became a
 * crash loop that filled the task stack with permission dialogs and took the phone's UI with it.
 */
export function startRecording(intervalMs = 10_000): string | null {
  return RecorderModule?.start(intervalMs) ?? "recorder module unavailable";
}

/** Why recording last stopped itself, or null. Survives the app being killed. */
export function recordingError(): string | null {
  return RecorderModule?.lastError() ?? null;
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

/**
 * If recording is enabled but the service died (OS kill), restart it — once.
 *
 * `isRecording` reads the native running flag, and the service clears that flag when it stands
 * down, so a service that failed for a reason it cannot recover from is no longer "enabled" and
 * this does nothing. That is the loop breaker: the flag is the handshake between a service that
 * gave up and a caller that would otherwise keep asking.
 */
export function ensureRunning(intervalMs = 10_000): string | null {
  if (isRecording() && !isServiceAlive()) {
    return startRecording(intervalMs);
  }
  return null;
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

/**
 * Mark the Wi-Fi you are on now as home or work, and return its name.
 *
 * The primary way a privacy zone is recognised. A GPS fix indoors drifts or vanishes, so a circle
 * round your front door misses the arrival and then teleports you back out of it; the network you are
 * connected to is exact and immediate. The coordinate zone stays as a backstop, so switching Wi-Fi
 * off does not quietly start recording your address.
 *
 * Null when Android will not name the network — location permission missing, or location switched off.
 */
export function setPrivacyNetwork(name: "home" | "work"): string | null {
  return RecorderModule?.setPrivacyNetwork(name) ?? null;
}

export function clearPrivacyNetwork(name: "home" | "work"): void {
  RecorderModule?.clearPrivacyNetwork(name);
}

/** The network saved for a zone, so settings can show what it will match. */
export function privacyNetwork(name: "home" | "work"): string | null {
  return RecorderModule?.privacyNetwork(name) ?? null;
}

/** What you are connected to right now, or null when Android will not say. */
export function currentNetwork(): string | null {
  return RecorderModule?.currentNetwork() ?? null;
}

/** Why the GPS is off, if it is. */
export type PowerState = "ACTIVE" | "PAUSED_ZONE" | "PAUSED_STILL";

/**
 * What the recorder is doing with the GPS radio.
 *
 * A paused recorder and a broken one produce the same thing — no fixes — so this is what lets the
 * UI say "paused, you're on your home Wi-Fi" instead of showing an on switch and an empty track.
 */
export function powerState(): PowerState {
  return (RecorderModule?.powerState() as PowerState) ?? "ACTIVE";
}

/**
 * Switch the GPS off when the phone has not moved, and let a hardware motion trigger wake it.
 *
 * On by default. The zones handle home and work; this handles everywhere else you sit still, which
 * on most days is more hours than both of them. The trigger is a sensor-hub interrupt, so a paused
 * recorder costs a fraction of a milliamp — and an hourly alarm looks again anyway, in case the
 * trigger is never delivered.
 */
export function setMotionGating(enabled: boolean): void {
  RecorderModule?.setMotionGating(enabled);
}

export function motionGating(): boolean {
  return RecorderModule?.motionGating() ?? true;
}

/** Minutes of not moving before the radio goes off. Clamped to 1–60 natively. */
export function setStillAfterMinutes(minutes: number): void {
  RecorderModule?.setStillAfterMinutes(minutes);
}

export function stillAfterMinutes(): number {
  return RecorderModule?.stillAfterMinutes() ?? 6;
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
