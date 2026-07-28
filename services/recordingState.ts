import AsyncStorage from "@react-native-async-storage/async-storage";
import { startRecording } from "@/modules/recorder";

const INTERVAL_MS: Record<string, number> = {
  "5s": 5_000,
  "10s": 10_000,
  "30s": 30_000,
  "60s": 60_000,
};

export async function getRecordingOn(): Promise<boolean> {
  try {
    return JSON.parse((await AsyncStorage.getItem("recordingOn")) ?? "false") === true;
  } catch {
    return false;
  }
}

export async function getIntervalMs(): Promise<number> {
  try {
    const v = JSON.parse((await AsyncStorage.getItem("recordInterval")) ?? '"10s"');
    return INTERVAL_MS[v] ?? 10_000;
  } catch {
    return 10_000;
  }
}

/** If the user wants recording on, (re)start the service. Idempotent. */
export async function healRecording(): Promise<void> {
  if (await getRecordingOn()) {
    startRecording(await getIntervalMs());
  }
}
