import AsyncStorage from "@react-native-async-storage/async-storage";
import { PermissionsAndroid } from "react-native";
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

/**
 * If the user wants recording on, and it can work, (re)start the service.
 *
 * The permission is checked — `check`, never `request`; asking is the map screen's job and only
 * once — because a location foreground service started without it used to throw out of
 * `startForeground` and kill the process. This runs on every focus of the map tab, so "start
 * something that will crash" and "run on every focus" were the two halves of a loop that restarted
 * the app hundreds of times and left an orphaned permission dialog behind on each pass.
 *
 * Returns null if recording is on and running, or a reason it isn't.
 */
export async function healRecording(): Promise<string | null> {
  if (!(await getRecordingOn())) return null;
  const granted = await PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
  );
  if (!granted) return "location permission not granted";
  return startRecording(await getIntervalMs());
}
