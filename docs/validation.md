# Validation report

Every number in this report comes from a test run or a recorded trace in this repository. Nothing is estimated or
copied from elsewhere. Where something has not been measured, the report says so.

**Bottom line.** In simulation, the corrected track is 1.6–3.5 m from the truth on average, against 4.7–9.8 m for raw
GPS. Distance is within ±2.1 % in every moving scenario. Running is recognised from 9.4 to 18 km/h, and a brisk walk,
a bus ride and five minutes of standing in drifting GPS add no running time. On an Android emulator the app works end
to end, and that run found three bugs the simulator had missed; all three are fixed and kept as tests. On the same
emulator the app survived backgrounding and location being switched off. When a revoked permission killed it mid-run,
the run was rebuilt from its trace.
**Nosferatu has not yet been measured on a real phone outdoors, and battery use has not been measured at all.**

## Reproducing

```sh
./gradlew test                                   # all suites
./gradlew :engine:test --tests '*ScenarioTest*' -i | grep -E '^\s+(err|seed|across|\()|mean'   # scenario table
./gradlew :bombaychitra:test -i                  # BombayChitra graph numbers (needs the extracted graph)
./gradlew :bombaychitra:replay --args="traces/emulator-hill-road.txt"
```

The scenarios are deterministic: the same commit gives the same numbers. At the time of writing the suites hold 61
engine tests, 23 BombayChitra adapter tests and 14 app tests, and all pass. The adapter tests that need the real graph
are skipped, not failed, when it hasn't been extracted; here none were skipped.

## What was tested where

| Level | What | Ground truth |
|---|---|---|
| Simulator (`:engine`, JVM) | 16 scenario tests covering the plan's list, plus a seed sweep | exact, every second |
| BombayChitra graph (`:bombaychitra`, JVM) | loading, query speed, real Mumbai streets | the dataset's own counts |
| Android emulator (Android 16, API 36) | the app end to end, backgrounding, permission loss | length of the driven route |
| Real phone outdoors | **not done yet** | — |

## Method

### Simulated runs

`RunSimulator` generates a complete trace for a person following a route through a list of legs (stand, walk, brisk
walk, jog, run, ride). It produces GPS at 1 Hz, accelerometer and gyroscope at 50 Hz, and ground truth at 1 Hz.

- **GPS error.** White noise of σ 3 m per axis, plus a slowly wandering bias (Gauss–Markov, σ 3 m, τ 40 s). The phone
  reports 5 m accuracy. Speed has 0.15 m/s noise and bearing 4°. Faults can be injected: outages, jumps (multipath
  with an honest-looking accuracy) and degraded stretches.
- **Motion.** `ImuSimulator` models each gait's cadence, vertical bounce and landing impact. The phone is held at
  80° roll and 10° pitch and can be rotated in the hand mid-run. Vehicle rides have road vibration and no steps.
- **Map.** A 4 × 4 grid of 120 m blocks at 19.06° N, 72.84° E, standing in for Bandra's streets. The exceptions are
  the "no map data" scenario (no network at all) and the unmapped path, which cuts through the blocks.

Each trace is replayed through the same `Tracker` the app runs (`Replay.run`), with a tick every second, exactly as on
the phone. Scenarios that need it tamper with the trace, for example freezing fixes, a stuck bearing or wrong first
fixes, or removing the gyroscope.

### Metrics

These are the definitions `Metrics.of` uses.

| Metric | Definition |
|---|---|
| Position error | Distance from the corrected position to the truth, every second that has both; mean and 95th percentile |
| Raw error | The same for the raw GPS fix that arrived that second |
| Distance error | Tracked on-foot distance against the truth path length (the sum of 1 s truth steps) |
| Raw distance | The sum of distances between consecutive raw fixes: the naive approach |
| Running precision / recall | Per second, after an 8 s warm-up. Truth "running" means jogging or running; predicted "running" means the classifier says so |
| Activity accuracy | Exact class match; jogging and running are told apart |
| False running | Seconds classified as running while the person was not |
| Recovery | Seconds from the end of the last GPS fault until the error is back under 10 m |

## Results: simulated scenarios

Errors are in metres; "raw" is raw GPS over the same seconds.

| Scenario | Plan item | Error mean / p95 | Raw mean / p95 | Distance | Run P / R | Activity | False run | Recovery |
|---|---|---|---|---|---|---|---|---|
| Normal run, 9 min loop | normal run | 2.5 / 5.4 | 5.3 / 10.4 | 1760 of 1766 m (−0.3 %) | 1.00 / 1.00 | 100 % | 1 s | – |
| Brisk walk, 7.2 km/h | brisk walk | 1.9 / 2.9 | 5.3 / 10.7 | 797 of 798 m (−0.1 %) | – | 100 % | 0 s | – |
| Run, 9.4 km/h | > 8 km/h | 2.0 / 3.6 | 5.0 / 10.1 | −0.4 % | 1.00 / 1.00 | 69 % | 0 s | – |
| Run, 13.7 km/h | > 8 km/h | 2.1 / 3.9 | 5.0 / 10.1 | +0.7 % | 1.00 / 1.00 | 100 % | 0 s | – |
| Run, 18.0 km/h | > 8 km/h | 1.6 / 3.7 | 5.0 / 10.1 | +0.0 % | 1.00 / 1.00 | 100 % | 0 s | – |
| Two multipath jumps, 100 m and 126 m | GPS jumps | 2.7 / 5.5 | 7.2 / 11.1 | +0.1 % | 1.00 / 1.00 | 100 % | 0 s | 0 s |
| Standing 5 min, GPS drifting | stationary drift | 4.0 / 15.3 | 9.8 / 19.0 | 0 m added | – | 100 % | 0 s | – |
| GPS outage, 25 s | GPS loss | 2.0 / 4.4 | 4.7 / 9.2 | +0.3 % | 1.00 / 1.00 | 99 % | 0 s | 0 s |
| Outage over a corner | GPS loss | 2.1 / 3.6 | 5.1 / 10.1 | −0.2 % | 1.00 / 1.00 | 99 % | 0 s | 0 s |
| Zigzag through junctions, noisier GPS | parallel roads, junctions | 2.7 / 4.9 | 9.5 / 19.8 | +1.4 % | 1.00 / 1.00 | 99 % | 0 s | – |
| Unmapped diagonal path | unmapped paths | 3.5 / 7.2 | 4.7 / 9.9 | +1.3 % | 1.00 / 1.00 | 98 % | 0 s | – |
| Sharp turns and stops | sharp turns and stops | 2.7 / 6.0 | 4.9 / 9.3 | −0.9 % | 0.99 / 0.99 | 97 % | 3 s | – |
| Phone rotated three times | orientation changes | 2.0 / 3.7 | 5.0 / 10.1 | +0.2 % | 1.00 / 1.00 | 100 % | 0 s | – |
| No map data | missing map data | 2.9 / 4.5 | 5.0 / 10.1 | +0.3 % | 1.00 / 1.00 | 100 % | 0 s | – |
| No gyroscope, outage over a corner | unsupported features | 2.8 / 3.7 | 5.1 / 10.1 | −0.4 % | 1.00 / 1.00 | 99 % | 0 s | 2 s |
| … and no map either | unsupported features | 3.7 / 4.5 | 5.1 / 10.1 | −0.4 % | 1.00 / 1.00 | 99 % | 0 s | 2 s |
| Bus ride, 29 km/h | (not running) | 1.9 / 3.8 | 4.9 / 10.1 | 0 of 1192 m counted | – | 100 % | 0 s | – |
| Doppler bearing stuck at 0° | (found on the emulator) | 3.1 / 6.3 | 5.3 / 10.3 | +2.1 % | 1.00 / 1.00 | 100 % | 0 s | – |
| First 3 fixes 350 m off | (found on the emulator) | 9.0 / 5.9 | 8.0 / 11.0 | −1.0 % | 1.00 / 1.00 | 100 % | 0 s | 4 s |
| GPS frozen for 20 s | (found on the emulator) | 2.1 / 3.9 | 6.5 / 12.4 | −0.6 % | 1.00 / 1.00 | 99 % | 0 s | – |

**Across seeds.** Each row above uses one random seed, which could be a lucky one. `theBoundsHoldAcrossSeeds`
reruns a 7-minute run with a 100 m jump and a 25 s outage under ten seeds. The seed changes the GPS noise, the bias
walk and the gait:

| Measure | Range over 10 seeds |
|---|---|
| Error mean | 1.8–3.9 m (raw GPS 5.6–6.9 m) |
| Error p95 | 3.4–7.8 m (raw GPS 9.1–11.5 m) |
| Distance error | −0.5 % to +0.6 % |
| Running recall | 1.00 in every run |
| Recovery after the outage | 0 s in every run |

Notes on individual rows:

- **Standing still.** The distance stays at zero, because motion says the person is still and the filter holds.
  The *position* still follows the GPS bias part of the way (p95 15 m), so a long stop shows the dot wandering
  without adding distance.
- **9.4 km/h.** It is labelled "running" in the truth and the classifier calls part of it "jogging". Both count as
  running, so recall is 1.00, but the exact class matches 69 % of the time. The jogging/running boundary is fuzzy by
  design, near 9–10 km/h.
- **Bus ride.** It is followed accurately (1.9 m), but none of its 1192 m counts as on-foot distance: there are no
  steps and the speed is too high for a person.
- **Wrong first fixes.** The mean (9.0 m) is above the p95 (5.9 m) because the first three seconds are 350 m out and
  dominate the mean. The track is back within 10 m four seconds after the bad fixes stop.
- **No gyroscope.** The cost sits in the ten or so seconds after the corner, before GPS returns: the course is
  held east while the runner has turned north. The p95 hides those seconds; the mean shows them (2.1 m with a
  gyroscope, 2.8 m without, 3.7 m without the map as well).
- **Sharp turns and stops.** The 3 s of false running are the seconds right after stopping, while the classifier's
  window still holds running steps.
- **Bearing stuck.** Compared with the same run with an honest bearing (2.4 / 5.4 m, −0.2 %), a stuck bearing costs
  0.7 m of mean error and 2 % of distance. It used to cost far more; see below.

### What these numbers do and do not show

- **The raw distance is a strawman.** The simulator's GPS noise is independent from fix to fix, so summing raw fixes
  overcounts by 22–257 %, and more the slower the movement. Real chipsets smooth their output, so a naive app on a real phone would overcount far less.
  The raw *position* error is the fairer comparison, and the tables use it.
- **The simulator and the engine share assumptions.** They were written for the same project. The gait models that
  generate the motion are the ones the classifier was tuned to recognise, so these results are an upper bound until
  real traces exist.
- **The grid is kinder than Mumbai.** Its parallel streets are 120 m apart. Service roads beside a highway, or a
  flyover above a road (BombayChitra marks Marine Lines Flyover as walkable), are much closer, and nothing here
  measures them.

## Results: BombayChitra's graph

`RealGraphTest` uses the real dataset, built 2026-10-17, covering the Mumbai Metropolitan Region.

| Measurement | Value |
|---|---|
| Size | 197,968 junctions, 247,098 segments, as BombayChitra documents |
| Load time | 1,180 ms on the JVM (desktop); 6,337 ms on the emulator |
| Candidate query (ways within the search radius) | 156 µs per query on the JVM, so 1 Hz matching costs nothing measurable |
| Sanity: Hill Road to Bandra station on foot | 1,477 m along ways, against 1,266 m in a straight line |
| Sanity: ways near Marine Lines station | Marine Lines Flyover (three segments) and Shamaldas Gandhi Road |

## Results: the emulator

The app ran on an Android 16 (API 36) emulator, driven by `scripts/emulator_run.py`. The run went east along Hill Road,
Bandra (BombayChitra street 1443) at 10.8 km/h and 168 steps per minute. It had a 45 m GPS jump at 60 s and a 20 s
outage at 100 s; about 450 m were driven. The app's raw trace is kept as `traces/emulator-hill-road.txt`.

| | Distance | Error |
|---|---|---|
| Recorded by the app on the emulator, before the fixes below | 330 m | −27 % |
| The same trace replayed after the fixes, with BombayChitra's graph | 465 m | +3 % |
| The same, without the graph | 468 m | +4 % |

With the fixes, the replay reads as follows:

- **Activity:** jogging 87 %, stationary 10 % (before the driver starts), unknown 2 %.
- **Position source:** GPS + motion + map 43 %, GPS + motion 41 %, steps and gyroscope 13 % (the outage).
- **Map matching:** on a way 88 % of the time, ambiguous 10 %.
- **Corrections:** 32 rejected fixes (the jump, the wrong start and the frozen repeats), 18 road pulls, 3 GPS
  losses and 2 regains.

Every screen worked: start, live tracking with diagnostics, finish with confirmation, history, and the run's detail on
the map. The emulator is not a phone, though. Its accelerometer is driven by the script, its GPS has no real error
model, and the next three points describe how its GPS misbehaves.

### Three bugs the emulator found

Each is now a scenario test that fails without its fix. The before numbers come from those tests, run against the
code before the fix.

1. **Every bearing was 0°.** The tracker trusted the Doppler bearing, so the velocity pointed north while the runner
   went east. In simulation this gave 9.9 m mean error and −20 % distance. Now a bearing that disagrees with the
   course over ground three times running, or that never changes, is no longer trusted. The result is 3.1 m and +2.1 %.
2. **The emulator started 350 m from the route.** The jump detector then rejected every real fix as a teleport, and
   for 25 s nothing counted. Now five consistent "teleports" re-anchor the gate, and recovery takes 4 s.
3. **During the outage the emulator repeated its last fix, speed included.** The tracker took the repeats for a stop.
   This cost 5.2 % of distance with a p95 error of 30 m. Now a repeated position with a non-zero speed is rejected as
   stale, and the gap is bridged by dead reckoning: −0.6 %, 3.9 m.

## Results: backgrounding and permission loss

These checks were made on the same emulator, driven along Hill Road at 3.0 m/s by `scripts/emulator_run.py`.

| Event during a run | What happened | Result |
|---|---|---|
| **Home pressed, 35 s in the background** | The process stayed alive (same PID). The notification kept updating, from 0.07 km to 0.17 km in 30 s, and the run screen continued on return | Tracking continued |
| **Location permission revoked**, 75 s in | Android killed the process, as it always does on a revoke. At the next launch the run was rebuilt from its trace and marked *Recovered* | 220 m in 1:15, against about 225 m driven |
| **Location switched off for 25 s** | The app showed its "Location is turned off" banner, and steps and the gyroscope carried the position (*dead reckoning*). Android 16 also put its own "No location access" dialog over the app. When location came back the banner cleared | 267 m against 270 m driven; moving time 87.6 s of 90 s |
| **43 minutes standing**, with the emulator repeating its last moving fix | All 2,003 repeats were rejected as stale | No distance added |

Without real GPS hardware, what these show is the app's lifecycle handling, not tracking accuracy.

## Battery

**Not measured.** The emulator's battery is simulated, so measuring there would mean nothing. What the app does to
keep the drain low:

- GPS comes only from `GPS_PROVIDER`, at 1 Hz, and only during a run.
- The accelerometer and gyroscope run at 50 Hz, batched by the sensor hub for 500 ms, so the CPU wakes twice a second
  rather than 100 times.
- One partial wake lock is held during a run and nowhere else.
- There is no network use at all.

To measure it on a phone, run `adb shell dumpsys batterystats --reset`, do a 30-minute run with the screen off, then
run `adb shell dumpsys batterystats dev.nosferatu`. That still needs doing.

## Not yet validated

- Real outdoor runs, in particular urban-canyon multipath, under flyovers, and Mumbai's monsoon cloud.
- Different phones and GPS chipsets, which vary in how they report bearing and accuracy.
- Pocket, armband and hand-held carry. Only the simulator's hand-held model and rotations are covered.
- Runs longer than an hour, including memory use of the in-app trail and of trace recovery.
- Battery use.
- Parallel ways closer than 120 m, and flyovers.
