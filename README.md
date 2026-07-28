# LightFog

A light [Fog of World](https://fogofworld.app) companion client for the Light Phone III.

Record where you go (even while the phone sleeps), watch the fog clear on an
OpenStreetMap base map, and ship your tracks to the cloud so the real Fog of
World app on another device can import them. The main app stays the source of
truth — LightFog is deliberately a thin client: no achievements, no editing.

## Status

- [x] **P0** — app scaffold, map view, foreground GPS recorder, daily track store
- [ ] **P1** — live local fog overlay from recorded tracks
- [ ] **P2** — Dropbox + OneDrive GPX auto-upload
- [ ] **P3** — read-only Fog of World `Sync/` import: render your full real fog history (Fog Machine-style)

## Install

Grab the APK from [Releases](https://github.com/gi-os/LightFog/releases/latest), or add
this repo URL to [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.

```sh
adb install -r LightFog-vX.Y.Z.apk
```

Recording survives sleep because it runs as a typed foreground service (the
same pattern GPSLogger uses). Disable battery optimization for LightFog, and
optionally grant background location:

```sh
adb shell pm grant com.gios.lightfog android.permission.ACCESS_BACKGROUND_LOCATION
```

## How it fits together

```
LightFog (LP3)  --GPX-->  Dropbox/OneDrive  --Import GPX-->  Fog of World (iPhone)
LightFog (LP3)  <--read-only Sync/ tiles--  Fog of World's own cloud auto-sync
```

## Development

Built on [vandamd's light-template](https://github.com/vandamd/light-template)
(Expo + MapLibre), with a native Kotlin expo-module (`modules/recorder`) for the
foreground location service. Map style follows
[light-topographic](https://github.com/garado/light-topographic).

```sh
bun install
bun dev          # expo run:android
```

CI builds a release APK on every push to `main` (plain Gradle, no EAS) and
publishes it as a GitHub release.

Map data © OpenStreetMap contributors. Fog of World sync-format knowledge from
[CaviarChen/Fog-of-World-Data-Parser](https://github.com/CaviarChen/Fog-of-World-Data-Parser).
