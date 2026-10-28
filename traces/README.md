# Traces

Recorded runs in Nosferatu's trace format, replayable through the tracker:

```sh
./gradlew :bombaychitra:replay --args="traces/emulator-hill-road.txt --timeline 5"
```

The format is plain text, one event per line, after a `# nosferatu-trace 1` header. Times are milliseconds on the
phone's monotonic clock (`elapsedRealtime`); every line type is written by `TraceFormat` in `:engine`.

| Line | Fields |
|---|---|
| `g` | GPS fix: `t, lat, lon, accuracy m, speed m/s, speed accuracy m/s, bearing °, altitude m` (empty when the phone didn't report it) |
| `a` | accelerometer: `t, x, y, z` in m/s², phone axes |
| `y` | gyroscope: `t, x, y, z` in rad/s, phone axes |
| `T` | ground truth: `t, lat, lon, activity` (simulated traces only) |
| `n` | note: `t, text` |

## emulator-hill-road.txt

Recorded by the app itself on an Android 16 emulator, driven by `scripts/emulator_run.py`:

```sh
python scripts/emulator_run.py --street 1443 --seconds 160 --speed 3.0 --cadence 168 --jump 60 --outage 100:20
```

That is a run east along Hill Road, Bandra (BombayChitra street 1443, the first 450 m), at 10.8 km/h and 168 steps
per minute, with a 45 m GPS jump at 60 s and a 20 s outage at 100 s. It has no ground truth lines, but the driven
distance is known: about 450 m.

It is kept because it exposed three problems the simulator had not:

1. The emulator reports every bearing as 0°; the tracker trusted it (fixed: a stuck or disagreeing Doppler bearing
   is now detected).
2. The emulator was left at a position 350 m from the start, and the teleport check then locked out the real fixes
   for 25 s (fixed: consistent "teleports" re-anchor the gate).
3. During the "outage" the emulator repeats its last fix, speed included; the tracker took it for a stop (fixed:
   stale copies are rejected and the gap is bridged by dead reckoning).

Replayed with the fixes and BombayChitra's graph it gives 465 m (+3 %); the app on the device, before the fixes,
recorded 330 m (−27 %). `EmulatorTraceTest` keeps it that way.

Put private traces in `traces/private/`; it is ignored by git.
