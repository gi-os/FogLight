import AsyncStorage from "@react-native-async-storage/async-storage";
import * as FileSystem from "expo-file-system/legacy";
import { Linking } from "react-native";
import { decodeTileFilename } from "@/utils/fog/fowMath";

/**
 * Dropbox OAuth2 PKCE (method "plain") + GPX import.
 * Create a "Scoped access / App folder" app at dropbox.com/developers/apps,
 * enable files.metadata.read + files.content.read/write scopes, and add
 * redirect URI: lightfog://oauth
 */
export const REDIRECT_URI = "lightfog://oauth";

const KEY_APP_KEY = "dropboxAppKey";
const KEY_AUTH = "dropboxAuth";
const KEY_VERIFIER = "dropboxVerifier";

type Auth = {
  refreshToken: string;
  accessToken: string;
  expiresAt: number; // epoch ms
};

function randomVerifier(length = 64): string {
  const chars =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
  let out = "";
  for (let i = 0; i < length; i++) {
    out += chars[Math.floor(Math.random() * chars.length)];
  }
  return out;
}

export async function getAppKey(): Promise<string | null> {
  const raw = await AsyncStorage.getItem(KEY_APP_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

export async function setAppKey(key: string): Promise<void> {
  await AsyncStorage.setItem(KEY_APP_KEY, JSON.stringify(key.trim()));
}

export async function isConnected(): Promise<boolean> {
  return (await AsyncStorage.getItem(KEY_AUTH)) != null;
}

export async function disconnect(): Promise<void> {
  await AsyncStorage.removeItem(KEY_AUTH);
}

/** Step 1: open the Dropbox consent page in the browser. */
export async function startAuth(): Promise<void> {
  const appKey = await getAppKey();
  if (!appKey) throw new Error("Set your Dropbox app key first.");
  const verifier = randomVerifier();
  await AsyncStorage.setItem(KEY_VERIFIER, verifier);
  const url =
    "https://www.dropbox.com/oauth2/authorize" +
    `?client_id=${encodeURIComponent(appKey)}` +
    "&response_type=code" +
    `&code_challenge=${encodeURIComponent(verifier)}` +
    "&code_challenge_method=plain" +
    "&token_access_type=offline" +
    `&redirect_uri=${encodeURIComponent(REDIRECT_URI)}`;
  await Linking.openURL(url);
}

/** Step 2: called from the oauth deep-link route with ?code=... */
export async function finishAuth(code: string): Promise<void> {
  const appKey = await getAppKey();
  const verifier = await AsyncStorage.getItem(KEY_VERIFIER);
  if (!appKey || !verifier) throw new Error("No pending authorization.");
  const body =
    `code=${encodeURIComponent(code)}` +
    "&grant_type=authorization_code" +
    `&client_id=${encodeURIComponent(appKey)}` +
    `&code_verifier=${encodeURIComponent(verifier)}` +
    `&redirect_uri=${encodeURIComponent(REDIRECT_URI)}`;
  const res = await fetch("https://api.dropboxapi.com/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) throw new Error(`Token exchange failed: ${await res.text()}`);
  const json = await res.json();
  const auth: Auth = {
    refreshToken: json.refresh_token,
    accessToken: json.access_token,
    expiresAt: Date.now() + (json.expires_in - 60) * 1000,
  };
  await AsyncStorage.setItem(KEY_AUTH, JSON.stringify(auth));
  await AsyncStorage.removeItem(KEY_VERIFIER);
}

async function getAccessToken(): Promise<string> {
  const raw = await AsyncStorage.getItem(KEY_AUTH);
  if (!raw) throw new Error("Dropbox not connected.");
  let auth: Auth = JSON.parse(raw);
  if (Date.now() >= auth.expiresAt) {
    const appKey = await getAppKey();
    const body =
      `grant_type=refresh_token` +
      `&refresh_token=${encodeURIComponent(auth.refreshToken)}` +
      `&client_id=${encodeURIComponent(appKey ?? "")}`;
    const res = await fetch("https://api.dropboxapi.com/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    if (!res.ok) throw new Error(`Token refresh failed: ${await res.text()}`);
    const json = await res.json();
    auth = {
      ...auth,
      accessToken: json.access_token,
      expiresAt: Date.now() + (json.expires_in - 60) * 1000,
    };
    await AsyncStorage.setItem(KEY_AUTH, JSON.stringify(auth));
  }
  return auth.accessToken;
}

type DbxEntry = { ".tag": string; name: string; path_lower: string };

/** List every .gpx file in the app folder (recursive). */
export async function listGpxFiles(): Promise<DbxEntry[]> {
  const token = await getAccessToken();
  const entries: DbxEntry[] = [];
  let body: Record<string, unknown> = { path: "", recursive: true };
  let url = "https://api.dropboxapi.com/2/files/list_folder";
  for (;;) {
    const res = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`list_folder failed: ${await res.text()}`);
    const json = await res.json();
    entries.push(...json.entries);
    if (!json.has_more) break;
    url = "https://api.dropboxapi.com/2/files/list_folder/continue";
    body = { cursor: json.cursor };
  }
  return entries.filter((e) => e[".tag"] === "file");
}

const B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

function toBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += B64[b0 >> 2];
    out += B64[((b0 & 3) << 4) | (b1 >> 4)];
    out += i + 1 < bytes.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : "=";
    out += i + 2 < bytes.length ? B64[b2 & 63] : "=";
  }
  return out;
}

export function importsDir(): string {
  return `${FileSystem.documentDirectory}imports`;
}

export function fowDir(): string {
  return `${importsDir()}/fow`;
}

/** Native filesystem path (no file:// scheme) of the FoW tiles dir. */
export function fowDirPath(): string {
  return fowDir().replace("file://", "");
}

export type ImportResult = {
  gpxAdded: number;
  fowAdded: number;
  gpxTotal: number;
  fowTotal: number;
};

/**
 * Download everything new: .gpx files, plus any Fog of World sync tiles
 * (recognized by their masked filenames — drop your FoW "Sync" folder
 * anywhere inside the app folder).
 */
export async function importNewGpx(): Promise<ImportResult> {
  const token = await getAccessToken();
  const remote = await listGpxFiles();
  await FileSystem.makeDirectoryAsync(importsDir(), { intermediates: true }).catch(
    () => undefined
  );
  await FileSystem.makeDirectoryAsync(fowDir(), { intermediates: true }).catch(
    () => undefined
  );
  const localGpx = new Set(await FileSystem.readDirectoryAsync(importsDir()));
  const localFow = new Set(await FileSystem.readDirectoryAsync(fowDir()));

  const result: ImportResult = { gpxAdded: 0, fowAdded: 0, gpxTotal: 0, fowTotal: 0 };

  for (const entry of remote) {
    const isGpx = entry.name.toLowerCase().endsWith(".gpx");
    const isFow = !isGpx && decodeTileFilename(entry.name) != null;
    if (isGpx) result.gpxTotal++;
    if (isFow) result.fowTotal++;
    if (!isGpx && !isFow) continue;
    if (isGpx && localGpx.has(entry.name)) continue;
    if (isFow && localFow.has(entry.name)) continue;

    const res = await fetch("https://content.dropboxapi.com/2/files/download", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Dropbox-API-Arg": JSON.stringify({ path: entry.path_lower }),
      },
    });
    if (!res.ok) continue;
    if (isGpx) {
      await FileSystem.writeAsStringAsync(
        `${importsDir()}/${entry.name}`,
        await res.text()
      );
      result.gpxAdded++;
    } else {
      const b64 = toBase64(await res.arrayBuffer());
      await FileSystem.writeAsStringAsync(`${fowDir()}/${entry.name}`, b64, {
        encoding: FileSystem.EncodingType.Base64,
      });
      result.fowAdded++;
    }
  }
  return result;
}

export async function listImported(): Promise<string[]> {
  try {
    const files = await FileSystem.readDirectoryAsync(importsDir());
    return files.filter((f) => f.toLowerCase().endsWith(".gpx")).sort();
  } catch {
    return [];
  }
}

export async function readImported(name: string): Promise<string> {
  return FileSystem.readAsStringAsync(`${importsDir()}/${name}`);
}
