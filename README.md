# LightFog

A [Fog of World](https://fogofworld.app) companion client for the Light Phone III.
Record where you go, watch the fog clear over an OpenStreetMap base map, and send the
tracks to the cloud so the real Fog of World app on another device can import them.

The main app stays the source of truth. LightFog is a thin client on purpose. No
achievements, no editing.

Part of the [gi-os Light App collection](#the-gi-os-light-app-collection).

## Status

| Stage | State |
| --- | --- |
| **P0** app scaffold, map view, foreground GPS recorder, daily track store | Done |
| **P1** local fog overlay from recorded tracks | Done |
| **P2** Dropbox GPX upload and import | Done |
| **P3** read-only Fog of World `Sync/` import, so the map shows your real history | Done |

## How it fits together

```
LightFog (LP3)  --GPX-->  Dropbox  --Import GPX-->  Fog of World (iPhone)
LightFog (LP3)  <--read-only Sync/ tiles--  Fog of World cloud auto-sync
```

## What it does

- Records a track as a typed foreground service, so it keeps running while the phone
  sleeps. This follows the pattern GPSLogger uses.
- Recovers the recording state from a persisted intent, so a killed process does not
  lose the session.
- Draws a continent-contrast MapLibre style with map rotation locked, which reads well
  on a gray panel.
- Imports Fog of World sync tiles through a native codec and renders both an overview
  bitmap and viewport tiles. The overview caps at 2048 px to keep the phone inside its
  memory budget.
- Validates every downloaded tile against the zlib magic bytes, and heals a corrupt
  import instead of failing the whole map.
- Authenticates to Dropbox with PKCE, and imports GPX.
- Shows fog diagnostics on the map and surfaces native errors instead of swallowing
  them.

## Install

Download the APK from
[Releases](https://github.com/gi-os/LightFog/releases/latest), or add
`https://github.com/gi-os/LightFog` to
[Obtainium](https://github.com/ImranR98/Obtainium) for updates.

```sh
adb install -r LightFog-vX.Y.Z.apk
```

Turn off battery optimization for LightFog, and grant background location if you want
it:

```sh
adb shell pm grant com.gios.lightfog android.permission.ACCESS_BACKGROUND_LOCATION
```

## Build

```sh
bun install
bun dev          # expo run:android
```

Use bun, not npm. `bun run check` lints and `bun run fix` repairs what it can.
`bun run sync-version` copies the version from `app.json` into `package.json` and
`build.gradle`.

CI builds a release APK on every push to `main` with plain Gradle, no EAS, and publishes
it as a GitHub release. The screen, safe-area and gesture-handler packages are pinned to
the versions the template is known to work with, because codegen breaks on a mismatch.

## Layout

| Path | What it is |
| --- | --- |
| `app/(tabs)/` | Map, record, tracks and settings screens |
| `app/oauth.tsx`, `app/confirm.tsx` | Dropbox PKCE flow |
| `modules/recorder/` | Native Kotlin expo-module, the foreground location service |
| `components/` | Template UI components |
| `assets/` | Public Sans, icon art |

## Origin and credits

- **[vandamd](https://github.com/vandamd)** wrote
  [light-template](https://github.com/vandamd/light-template), the Expo plus MapLibre
  starter this app forks. The component set, the `n()` scaling helper, the
  `ContentContainer` pattern and the build scripts are his. Thank you.
- **[garado](https://github.com/garado)** wrote
  [light-topographic](https://github.com/garado/light-topographic). The map style here
  follows it.
- **[CaviarChen](https://github.com/CaviarChen)** wrote
  [Fog-of-World-Data-Parser](https://github.com/CaviarChen/Fog-of-World-Data-Parser),
  which documents the Fog of World sync format. The native tile codec in this repo
  reads that format because of that work. Thank you.
- **[Fog of World](https://fogofworld.app)** is the app this one feeds. LightFog is an
  unofficial companion and the Fog of World team has no part in it.
- **[MapLibre](https://maplibre.org/)** renders the map. Map data is copyright
  OpenStreetMap contributors.
- **[The Light Phone](https://www.thelightphone.com/)** for the hardware and LightOS.

The release workflow comes from [LightQR](https://github.com/gi-os/LightQR). Plain
Gradle in GitHub Actions beats an EAS build for a single-developer project, and it needs
no Expo token.

## The gi-os Light App collection

Eight tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| [chat](https://github.com/gi-os/chat) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| **LightFog** (this repo) | Fog of World companion, GPS recorder and fog map | Expo, [vandamd/light-template](https://github.com/vandamd/light-template) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| [LightSolitaire](https://github.com/gi-os/LightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |

The Light Phone does not sponsor or endorse any of these.

## License

MIT. See [LICENSE](LICENSE).
