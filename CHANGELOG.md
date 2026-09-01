# Changelog

## v0.13.0 — Weather rides along

The rest of the collection keeps wanting one line of weather, and every app that fetches its own
would need a location permission to do it. This app already knows where the phone is — it is the
only one allowed to — so it now serves today's weather to whoever asks, under the same discipline
that governs everything else it does with a coordinate.

**Strictly piggybacked, never chased.** A fetch happens in exactly two situations: a fix the track
already paid for arrives and the cache is older than 45 minutes, or WorkManager's hourly batch
window opens with a network attached. The hourly path reuses the **last known** coordinate — it
never asks a location API for anything, and with no coordinate ever seen it does nothing at all.
No alarm, no wake, no GPS request belongs to weather; a phone in a drawer pays zero. This is the
app that once crash-looped a location service and took the phone down with it, so the rules are
in a pure `Weather` object where the tests can hold them: `WeatherTest` pins the 45-minute
staleness gate, the rounding, and the WMO vocabulary.

**One request, one neighbourhood.** Open-Meteo (keyless, free), a single GET for current
temperature, WMO weather code, today's high/low and precipitation probability. The coordinate is
rounded to two decimals — about a kilometre — before it leaves the phone, which is both the
privacy line and all the resolution weather has anyway.

**Served on `content://com.gios.lightfog.weather/today`.** One read-only row: `updatedAt`,
`tempC`, `hiC`, `loC`, `code`, `description`, `precipPct`. No coordinate in any column. A failed
fetch keeps the stale cache and serves it with an honest timestamp — how old is too old is the
reader's call — and nothing cached is an empty cursor, not an error.

## v0.12.1 — The GPS was already off; the Wi-Fi callback was the drain

v0.12.0 did switch the radio off at work. The Record screen said so, the state was `PAUSED_ZONE`,
and the battery still went. The pause was never the problem — what was left running around it was.

**`onCapabilitiesChanged` is a firehose, not an event.** `WifiInfo` lives inside
`NetworkCapabilities`, so every RSSI and link-speed change on the connected network is delivered to
the callback — seconds apart, all day. v0.12.0 ran the entire power policy on each one, *plus* a
second run 2.5s later, and every run made a `WifiManager` binder call (which notes an app-op in
`system_server`), read prefs, and looked up a sensor. On a quiet home router that is waste. In an
office — dozens of APs, constant roaming, a hundred other clients moving the RSSI around — it is a
process that is never permitted to go idle. That asymmetry is exactly why home improved and work
did not.

Now the SSID is read from the capabilities object already in hand, via
`FLAG_INCLUDE_LOCATION_INFO`, and if it has not changed the callback returns having done nothing.
No binder call, no app-op, no prefs read. The sensor lookup is cached for the life of the process.

**An unreadable name is no longer read as "you left".** This is the expensive one. `getSSID()`
returns `<unknown ssid>` whenever the platform declines, and a momentary app-op or
location-attribution hiccup is enough. v0.12.0 took that as leaving the zone and started the GPS —
then the next event stopped it again. Flapping costs more than either state, because a cold GPS
reacquisition is the most expensive thing that receiver does, and doing it all day is worse than
simply having left it on. Presence now comes from the Wi-Fi transport (`hasTransport`, plain
`ACCESS_NETWORK_STATE`, nothing to redact) and identity from the SSID; while Wi-Fi is up, an
unreadable name keeps the last known one. The re-read is capped at two attempts, because an
unconditional retry is how a poll gets built by accident.

**"Not recording — recorder module unavailable" was never true.** A pre-existing bug, unrelated to
battery but visible the whole time: `start()` returns `null` to mean *started*, and the JS wrapper
was `RecorderModule?.start(…) ?? "recorder module unavailable"`, so `??` turned every success into
that error. It also meant `if (!refused) setRecordOn(true)` never ran, so the Start button never
stuck — recording only stayed on for anyone whose preference predated the bug.

**Settings → Battery now shows the live SSID against the saved ones.** A saved network and a live
one that differ by a band suffix read identically in a sentence and not at all side by side.
`NetworkCallback`'s flags constructor is API 31, so pre-31 keeps the no-arg one — `minSdk` is 24
here and a constructor that does not exist is a `NoSuchMethodError`, not a graceful fallback.

## v0.12.0 — The GPS is off when a fix would be thrown away

LightFog was using around 70% of a day's battery. The cause was not the interval or the
foreground service: it was that home and work were a filter on the *output*. The phone asked
the GPS where it was every ten seconds while sitting on a desk, and then deleted the answer
because it had promised not to write your address down. It paid for every one of those fixes.

Both privacy zones now switch the radio off instead of filtering what it returns, and a second
switch covers the rest of the day.

**Zero drain on your home and work Wi-Fi.** Connecting to a network you have named home or work
stops the location request entirely. The pause ends when the Wi-Fi drops, which is what happens
on its own when you walk out of the door — no fix needed to notice, so nothing has to stay on to
watch for it. The arrival line ("went home, 19:40") is still written, still with no coordinate in
it anywhere.

**Pause when still, everywhere else.** Six minutes without moving more than 70m and the radio
goes off; a hardware significant-motion trigger turns it back on. That trigger lives in the
sensor hub and costs a fraction of a milliamp against the tens of milliamps a GPS fix costs, so
a desk, a restaurant, a cinema seat and a night's sleep are all now free. Adjustable 2–30 minutes
in Settings → Battery, or off.

**Fixes 20m apart instead of 5m.** A fog cell is about ten metres across and the trail between
two fixes is filled in, so fixes five metres apart drew nothing the previous one had not already
drawn — they only woke the process. Roughly three quarters fewer callbacks on a walk, same fog.

**It says which pause it is.** A paused recorder and a broken recorder look identical from
outside: no points either way. The Record screen and the notification now name the reason, so
"on your home network" cannot be mistaken for a bug.

Two things kept deliberately, both of them the difference between a feature and a regression:

- **A radius zone still cannot switch the radio off.** Leaving a circle is only knowable from a
  fix, so a radius pause would have no way to end. The 500m zones go on dropping fixes with the
  radio on; only a named network pauses it.
- **A still pause has a second way out.** A one-shot motion trigger can be consumed and lost to a
  process kill, or never fired by a phone carried smoothly in a bag, and recording would be over
  with no symptom. An hourly `setAndAllowWhileIdle` alarm — the only kind that fires in Doze —
  looks again regardless, so the worst a missed trigger costs is one hour of track.

## v0.11.x

- Home is a network, not an address: privacy zones match the Wi-Fi SSID, with the GPS radius kept
  as a backstop so turning Wi-Fi off cannot quietly start recording your address.
- Never crash-loop a foreground service: the location permission is checked before going
  foreground, and a recorder that stands down clears the flag that would restart it.
