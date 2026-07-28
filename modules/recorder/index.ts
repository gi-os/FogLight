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
