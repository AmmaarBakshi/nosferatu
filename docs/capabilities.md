# Capabilities and limitations

What Nosferatu does today, what it does not, and where its judgement should not be trusted. "Measured" here means
measured in simulation (see [validation.md](validation.md)) unless it says otherwise.

## Capabilities

### Map (BombayChitra)

- Draws BombayChitra's own dark style from its PMTiles archive, fully offline once downloaded.
- Uses BombayChitra's routing graph for the whole MMR on the phone: 198k junctions, 247k way segments.
- Considers only ways BombayChitra marks as walkable; knows bridges, tunnels and steps.
- Reports coverage honestly: outside the MMR, map matching switches off and says *Outside map*.
- Quiet run map: hides BombayChitra's 155k places and house numbers while running (visibility only).

### Position

- Kalman filter over raw GPS with Doppler velocity, χ² gating of inconsistent fixes, and recovery when the filter,
  not the GPS, turns out to be wrong.
- Rejects teleports (> 12 m/s implied) and very inaccurate fixes (> 50 m); down-weights poor ones.
- Detects a stuck or stale Doppler bearing and falls back to speed only.
- Stops GPS drift while standing (zero-velocity updates from the accelerometer): five minutes of drifting GPS while
  standing added 0 m in simulation.
- Road-aware correction: a soft pull towards the matched way only when the map matcher is confident; never a snap.
- Keeps unmapped paths: an explicit off-network state in the matcher; a diagonal path across blocks stays diagonal.
- Flags ambiguity (parallel roads, junctions) instead of guessing.
- Bridges GPS outages up to 60 s with step-and-gyroscope dead reckoning, using a step length learned from good GPS.
- Reports a 1σ uncertainty with every position and which source produced it (GPS, GPS + motion, GPS + motion + map,
  steps and gyroscope, last known).

### Activity

- Stationary, walking, jogging, running and unknown, from a temporal HMM, with posterior probabilities and the
  evidence behind them (cadence, regularity, impact, speed).
- Independent of how the phone is held (gravity-aligned features).
- Fast running is running: recall 1.00 at 9.4, 13.7 and 18 km/h in simulation.
- A brisk walk at 7.2 km/h produced 0 s of false running in simulation; a bus ride produced 0 s and added 0 m.
- Distance counts only on foot.

### App

- Live run screen with distance, time, pace, cadence, activity, GPS quality and map-match state.
- Diagnostics toggle: raw fixes, rejected and down-weighted fixes, matched way, posterior bars, evidence, totals.
- Foreground service of type `location` with a notification and a partial wake lock, so runs continue with the
  screen off.
- Handles permission loss and location being switched off mid-run, with a banner, instead of crashing.
- Run history and detail, with the route on BombayChitra's map and correction counts.
- Every run's raw sensor stream saved for replay, pauses included.
- A run the app dies in (revoking location access kills it, and so may the system) is rebuilt from its raw trace at
  the next launch and marked *Recovered*. The trace is flushed every 5 s, so at most the last few seconds are lost.

## Limitations

### Not yet measured

- **No real-world validation yet.** All accuracy, distance and activity numbers come from a simulator plus one
  emulator run. The simulator's GPS noise is harsher fix to fix than modern chipsets, so the "raw GPS" baselines
  in the report are pessimistic and the improvement over them is overstated. Real traces with ground truth are the
  most useful contribution anyone can make.
- **Battery use is unmeasured.** The design aims low (GPS at 1 Hz, sensors batched so the CPU wakes twice a
  second), but there are no numbers.

### Map

- **MMR only.** Outside BombayChitra's bounds there is no map matching, by design.
- **Map quality is OpenStreetMap's.** Missing or misplaced paths in BombayChitra's data are treated as off-network,
  so they don't corrupt the track, but they don't help it either.
- **Large download.** The tiles are ~96 MB and the debug APK is ~114 MB (the road graph and MapLibre's native
  libraries for every ABI). No ABI splits yet.
- **Stacked ways.** A fix under a flyover is matched with a bridge/tunnel penalty, but genuinely stacked walkable
  ways (a skywalk over a footpath) can't be told apart from 2D GPS.

### Position

- **Dead reckoning is short-range.** After 60 s without GPS the position is held, not extrapolated. Step length is
  learned per run and is poor if GPS was bad from the start.
- **Without a gyroscope, outages hold the last course.** In simulation, a 25 s outage over a corner raised the run's
  mean error from 2.1 m to 2.8 m with the map, and to 3.7 m without it. The cost sits in the seconds after the
  corner, until GPS returns.
- **No barometer or altitude model.** Distance is horizontal.
- **Map-matched pull assumes you are on the way's centreline ± its half-width.** On very wide roads the pull is weak
  by design; on unmapped service lanes next to a mapped road the matcher can wrongly prefer the road for a few
  fixes before switching off-network.

### Activity

- **Jogging vs running is a pace boundary (~10 km/h), not a gait difference.** A 9.4 km/h run is classified as
  jogging; both count as running for distance and history.
- **Phone in a bag or on an armband** changes impact signatures; not tested.
- **Treadmills** (steps, no GPS movement) are classified from gait alone and get no distance.
- **Cycling** is reported as unknown, not as a separate activity.

### App

- **A killed run is recovered, not resumed.** If Android kills the app mid-run, the run ends there and is saved from
  its trace on the next launch; nothing is tracked between the kill and a new run.
- **Dark theme only** for the map (BombayChitra's light style is shipped but not wired to a setting).
- **No export** (GPX/FIT) and no sync. Runs stay on the phone.
- **English only.**
