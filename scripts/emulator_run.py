#!/usr/bin/env python3
"""Drive an Android emulator through a simulated run, end to end through the real app.

The emulator's sensors sit still by default, so Nosferatu (correctly) sees no steps and counts no distance. This
script talks to the emulator console and plays a runner:

  * GPS: `geo fix` once a second along a line, with speed, realistic noise (a slowly wandering bias plus jitter),
    optional jumps and an optional outage. (The emulator ignores NMEA sentences and always reports bearing 0°,
    which the tracker detects as a stuck bearing and works around.)
  * accelerometer: a running gait (one landing peak per step) at ~25 Hz on the phone's vertical axis.

The route is a GeoJSON LineString or MultiLineString file, or a street fetched from a BombayChitra server.

  python scripts/emulator_run.py --street 1443 --seconds 150
  python scripts/emulator_run.py --route my-route.geojson --speed 2.8 --cadence 164 --jump 60 --outage 90:20

Needs the emulator console (port 5554 by default) and its auth token in ~/.emulator_console_auth_token.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import socket
import time
import urllib.request
from pathlib import Path

EARTH_R = 6_371_008.8
GRAVITY = 9.80665


class Console:
    def __init__(self, port: int):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.buf = b""
        self._until_ok()  # banner
        token = (Path.home() / ".emulator_console_auth_token").read_text().strip()
        self.send(f"auth {token}")

    def _until_ok(self) -> str:
        while b"OK\r\n" not in self.buf and b"KO" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("emulator console closed")
            self.buf += chunk
        if b"KO" in self.buf:
            line = self.buf.decode(errors="replace")
            self.buf = b""
            raise RuntimeError(f"console refused: {line.strip()}")
        out, _, self.buf = self.buf.partition(b"OK\r\n")
        return out.decode(errors="replace")

    def send(self, command: str) -> str:
        self.sock.sendall(command.encode() + b"\n")
        return self._until_ok()


def load_route(args) -> list[tuple[float, float]]:
    if args.street is not None:
        url = f"{args.server.rstrip('/')}/api/v1/streets/{args.street}?format=geojson"
        with urllib.request.urlopen(url, timeout=10) as r:
            geo = json.load(r)
    else:
        geo = json.loads(Path(args.route).read_text(encoding="utf-8"))
    geom = geo.get("geometry", geo)
    if geom["type"] == "LineString":
        parts = [geom["coordinates"]]
    elif geom["type"] == "MultiLineString":
        parts = geom["coordinates"]
    else:
        raise SystemExit(f"unsupported geometry {geom['type']}")
    return stitch([[(lat, lon) for lon, lat in part] for part in parts])


def distance(a, b) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def bearing(a, b) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lo2 - lo1) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(lo2 - lo1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def offset(p, east_m: float, north_m: float):
    lat = p[0] + math.degrees(north_m / EARTH_R)
    lon = p[1] + math.degrees(east_m / (EARTH_R * math.cos(math.radians(p[0]))))
    return lat, lon


def stitch(parts: list[list[tuple[float, float]]]) -> list[tuple[float, float]]:
    """Joins a street's ways into one line: start at the westmost end, keep taking the nearest way end."""
    parts = [p for p in parts if len(p) >= 2]
    start = min(parts, key=lambda p: min(p[0][1], p[-1][1]))
    parts.remove(start)
    line = start if start[0][1] <= start[-1][1] else start[::-1]
    while parts:
        tail = line[-1]
        best = min(parts, key=lambda p: min(distance(tail, p[0]), distance(tail, p[-1])))
        if min(distance(tail, best[0]), distance(tail, best[-1])) > 40:
            break
        parts.remove(best)
        line += best if distance(tail, best[0]) <= distance(tail, best[-1]) else best[::-1]
    return line


def along(line, s: float):
    """The point and direction [s] metres along [line] (clamped to its end)."""
    for a, b in zip(line, line[1:]):
        d = distance(a, b)
        if s <= d and d > 0:
            f = s / d
            return (a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])), bearing(a, b)
        s -= d
    return line[-1], bearing(line[-2], line[-1])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--route", help="GeoJSON LineString/MultiLineString file")
    src.add_argument("--street", type=int, help="street id on a BombayChitra server")
    ap.add_argument("--server", default="http://127.0.0.1:8080")
    ap.add_argument("--port", type=int, default=5554)
    ap.add_argument("--seconds", type=float, default=120)
    ap.add_argument("--speed", type=float, default=3.0, help="m/s (3.0 = 10.8 km/h)")
    ap.add_argument("--cadence", type=float, default=168, help="steps per minute; 0 for no steps (a vehicle)")
    ap.add_argument("--peak", type=float, default=10.5, help="vertical acceleration peak per step, m/s²")
    ap.add_argument("--noise", type=float, default=3.0, help="GPS jitter, metres (1 sigma)")
    ap.add_argument("--jump", type=float, action="append", default=[], help="seconds at which GPS jumps ~45 m for 3 fixes")
    ap.add_argument("--outage", action="append", default=[], help="START:LENGTH seconds with no GPS fixes")
    ap.add_argument("--rate", type=float, default=25, help="accelerometer updates per second")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    rnd = random.Random(args.seed)
    line = load_route(args)
    length = sum(distance(a, b) for a, b in zip(line, line[1:]))
    outages = [tuple(map(float, o.split(":"))) for o in args.outage]
    print(f"route {length:.0f} m, {len(line)} points; running {args.seconds:.0f} s at {args.speed * 3.6:.1f} km/h")

    con = Console(args.port)
    bias_e = bias_n = 0.0
    t0 = time.time()
    next_fix = 0.0
    phase = 0.0
    last = 0.0
    fixes = 0
    while (now := time.time() - t0) < args.seconds:
        dt = now - last
        last = now
        phase += 2 * math.pi * args.cadence / 60.0 * dt
        up = 0.0
        if args.cadence > 0:
            up = args.peak * (0.75 * math.sin(phase) + 0.25 * math.sin(2 * phase + 0.6)) + rnd.gauss(0, 0.1)
        # Portrait in a hand or pocket: the phone's y axis is roughly vertical.
        con.send(f"sensor set acceleration {rnd.gauss(0, 0.15):.3f}:{GRAVITY + up:.3f}:{rnd.gauss(0, 0.15):.3f}")

        if now >= next_fix:
            next_fix += 1.0
            s = min(args.speed * now, length)
            p, _ = along(line, s)
            # A bias that wanders slowly (multipath, satellite geometry) plus per-fix jitter.
            bias_e = 0.95 * bias_e + rnd.gauss(0, 0.6)
            bias_n = 0.95 * bias_n + rnd.gauss(0, 0.6)
            east, north = bias_e + rnd.gauss(0, args.noise), bias_n + rnd.gauss(0, args.noise)
            if any(j <= now < j + 3 for j in args.jump):
                east += 45
            if not any(a <= now < a + n for a, n in outages):
                lat, lon = offset(p, east, north)
                knots = max(0.0, args.speed + rnd.gauss(0, 0.2)) / 0.514444
                con.send(f"geo fix {lon:.7f} {lat:.7f} 10 9 {knots:.2f}")
                fixes += 1
        time.sleep(max(0.0, 1.0 / args.rate - (time.time() - t0 - now)))

    con.send(f"sensor set acceleration 0:{GRAVITY:.3f}:0")
    print(f"done: {fixes} fixes over {args.seconds:.0f} s")


if __name__ == "__main__":
    main()
