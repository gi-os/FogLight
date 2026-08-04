# Changelog

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
