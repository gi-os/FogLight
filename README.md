# FogLight

A [Fog of World](https://fogofworld.app) companion client for the Light Phone III.
Record where you go, watch the fog clear over an OpenStreetMap base map, and send the
tracks to the cloud so the real Fog of World app on another device can import them.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/FogLight.png" alt="Scan to open FogLight in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open FogLight there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

The main app stays the source of truth. FogLight is a thin client on purpose. No
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
FogLight (LP3)  --GPX-->  Dropbox  --Import GPX-->  Fog of World (iPhone)
FogLight (LP3)  <--read-only Sync/ tiles--  Fog of World cloud auto-sync
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

## The crash loop, and what it taught

Worth writing down, because the failure was much larger than the bug.

`RecorderService` went foreground *before* checking the location permission. On Android 14 a
`location`-typed foreground service is validated against the permission at `startForeground`, so
without the grant it threw `SecurityException` — out of `onStartCommand`, killing the process,
before ever reaching the permission check a few lines below that would have stopped it politely.
Then everything conspired to try again: `START_STICKY` brought the service back, `BootReceiver`
restarted it at every boot, `healRecording()` ran on every focus of the map tab, and the map tab
also called `PermissionsAndroid.request` on every focus. Each pass died with a permission dialog
still open, orphaning its task.

The phone was found with **367 live `com.android.permissioncontroller` tasks** and LightOS — the
Light Phone's launcher, which runs as uid 1000 — dead. An alarm couldn't be turned off. The app
that broke the phone was never in the foreground.

Four rules came out of it, and they generalise past this app:

- **Check the permission before going foreground, not after.** The typed-FGS check throws; it is
  not a return value.
- **`startForeground` never crashes the process.** Three things throw there — a missing permission,
  a background start outside an exemption, older timing edges — and none of them are worth a dead
  app. A logger that can't log takes its notification away.
- **A component that gives up must clear the flag that restarts it.** The running flag is the
  handshake between a service that stood down and every caller that would otherwise keep asking:
  `BootReceiver`, sticky restarts, `ensureRunning`, the UI.
- **Never retry what can't work.** `start` now returns a reason instead of nothing, callers show it
  instead of calling again, and the permission is `check`ed everywhere except the one screen whose
  job is to ask.

## Install

Download the APK from
[Releases](https://github.com/gi-os/FogLight/releases/latest), or add
`https://github.com/gi-os/FogLight` to
[Obtainium](https://github.com/ImranR98/Obtainium) for updates.

```sh
adb install -r LightFog-vX.Y.Z.apk
```

Turn off battery optimization for FogLight, and grant background location if you want
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

- **[garado](https://github.com/garado)** wrote
  [light-topographic](https://github.com/garado/light-topographic), an outdoor maps app
  for the Light Phone III. FogLight is a fork of it, not a fork of a bare template. The
  map layer here is his work: `utils/mapStyle.ts` and `utils/mapStyle/`, `utils/geo.ts`,
  `utils/parseGpx.ts` and `utils/units.ts` all come from light-topographic, and this repo
  only strips the layer picker out of `buildMapStyle` and adds the fog source. Thank you.
- **[vandamd](https://github.com/vandamd)** wrote
  [light-template](https://github.com/vandamd/light-template), the Expo starter that
  light-topographic itself came from. The component set, the `n()` scaling helper, the
  `ContentContainer` pattern and the build scripts in `scripts/` are his. Thank you.
- **[CaviarChen](https://github.com/CaviarChen)** wrote
  [Fog-of-World-Data-Parser](https://github.com/CaviarChen/Fog-of-World-Data-Parser),
  which documents the Fog of World sync format. The native tile codec in this repo reads
  that format because of that work. The repo is archived now and
  [fog-machine](https://github.com/CaviarChen/fog-machine) succeeds it. Thank you.
- **[Fog of World](https://fogofworld.app)** is the app this one feeds. FogLight is an
  unofficial companion and the Fog of World team has no part in it.
- **[MapLibre](https://maplibre.org/)** renders the map. Map data is copyright
  OpenStreetMap contributors.
- **[The Light Phone](https://www.thelightphone.com/)** for the hardware and LightOS.

The release workflow comes from [LightQR](https://github.com/gi-os/LightQR). Plain
Gradle in GitHub Actions beats an EAS build for a single-developer project, and it needs
no Expo token.


## License

Unsettled, and worth reading before you copy anything from here.

Neither [light-topographic](https://github.com/garado/light-topographic) nor
[light-template](https://github.com/vandamd/light-template) carries a LICENSE file, so
both stay under default copyright. FogLight contains code from both. This repo therefore
cannot put an open-source license over the whole tree, and it does not try to.

The original work in this repo, which is `modules/recorder/`, `utils/fog/`, the fog
overlay and the Dropbox flow, is offered under MIT. Everything inherited from the two
projects above belongs to its authors under their terms. I have asked both authors to
add a license. Until they do, treat this repository as source-available and ask them
before you redistribute.

<!-- bright-footer:begin -->
---

## Bright\*

*Fog of World for a phone that will never have an App Store: record where you go, watch the fog clear.*

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightMusic](https://github.com/gi-os/BrightMusic) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightRemote](https://github.com/gi-os/BrightRemote) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->
