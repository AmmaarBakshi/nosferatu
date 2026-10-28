#!/usr/bin/env python3
"""Copy what Nosferatu needs from BombayChitra into the app's assets.

    styles   BombayChitra's generated MapLibre styles (light, dark), fetched from a running BombayChitra server
             (`bombaychitra serve`). They are stored unmodified; the app rewrites the tile and glyph URLs.
    fonts    The Noto Sans glyph ranges from a BombayChitra checkout (web/public/fonts).
    graph    The road and path network: the `meta` and `routing` tables of bombaychitra.db, copied byte for byte
             into graph.db (~14 MB). Same schema as BombayChitra, so the adapter reads either file.

Examples:
    python scripts/bombaychitra_assets.py graph --db ../BombayChitra/data/build/bombaychitra.db
    python scripts/bombaychitra_assets.py graph --release            # downloads bombaychitra.db (~230 MB) first
    python scripts/bombaychitra_assets.py styles --server http://localhost:8080
    python scripts/bombaychitra_assets.py fonts --checkout ../BombayChitra
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
RELEASE = "https://github.com/AmmaarBakshi/BombayChitra/releases/download/data-latest"
THEMES = ("light", "dark")
GRAPH_TABLES = ("meta", "routing")


def fetch_styles(server: str) -> None:
    out = ASSETS / "bombaychitra" / "styles"
    out.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        url = f"{server.rstrip('/')}/styles/{theme}.json"
        with urllib.request.urlopen(url, timeout=30) as resp:
            style = json.loads(resp.read().decode("utf-8"))
        if "bc" not in style.get("sources", {}):
            sys.exit(f"{url}: no 'bc' source, not a BombayChitra style")
        # Keep BombayChitra's style intact; only make the output stable and readable for diffs.
        (out / f"{theme}.json").write_text(json.dumps(style, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"styles/{theme}.json  {len(style['layers'])} layers")


def copy_fonts(checkout: Path) -> None:
    src = checkout / "web" / "public" / "fonts"
    if not src.is_dir():
        sys.exit(f"{src} not found; pass the root of a BombayChitra checkout")
    dst = ASSETS / "fonts"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    count = sum(1 for _ in dst.rglob("*.pbf"))
    print(f"fonts/  {count} glyph ranges")


def download(url: str, dest: Path) -> None:
    print(f"downloading {url}")
    with urllib.request.urlopen(url, timeout=60) as resp, open(dest, "wb") as f:
        shutil.copyfileobj(resp, f, length=1 << 20)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def release_db(workdir: Path) -> Path:
    sums_path = workdir / "SHA256SUMS"
    download(f"{RELEASE}/SHA256SUMS", sums_path)
    sums = {}
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2:
            sums[parts[1].lstrip("*")] = parts[0].lower()
    db = workdir / "bombaychitra.db"
    download(f"{RELEASE}/bombaychitra.db", db)
    if "bombaychitra.db" in sums and sha256(db) != sums["bombaychitra.db"]:
        sys.exit("bombaychitra.db does not match the release checksum")
    return db


def extract_graph(db: Path) -> None:
    src = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    have = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    missing = [t for t in GRAPH_TABLES if t not in have]
    if missing:
        sys.exit(f"{db}: missing tables {missing}; the routing graph needs BombayChitra schema 2")
    out = ASSETS / "bombaychitra" / "graph.db"
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    if tmp.exists():
        tmp.unlink()
    dst = sqlite3.connect(tmp)
    for table in GRAPH_TABLES:
        (ddl,) = src.execute("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        dst.execute(ddl)
        cols = [r[1] for r in src.execute(f"PRAGMA table_info({table})")]
        rows = src.execute(f"SELECT {', '.join(cols)} FROM {table}").fetchall()
        dst.executemany(f"INSERT INTO {table} VALUES ({', '.join('?' * len(cols))})", rows)
    dst.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('nosferatu_extracted_from', ?)", (db.name,))
    dst.commit()
    dst.execute("VACUUM")
    dst.close()
    os.replace(tmp, out)
    meta = dict(src.execute("SELECT key, value FROM meta WHERE key LIKE 'routing_%' OR key IN ('built_at', 'bounds')"))
    print(f"bombaychitra/graph.db  {out.stat().st_size / 1e6:.1f} MB  {json.dumps(meta)}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("styles", help="fetch the generated styles from a BombayChitra server")
    s.add_argument("--server", default="http://localhost:8080")
    f = sub.add_parser("fonts", help="copy glyphs from a BombayChitra checkout")
    f.add_argument("--checkout", type=Path, required=True)
    g = sub.add_parser("graph", help="extract the routing graph from bombaychitra.db")
    src = g.add_mutually_exclusive_group(required=True)
    src.add_argument("--db", type=Path)
    src.add_argument("--release", action="store_true", help="download bombaychitra.db from data-latest first")
    args = p.parse_args()

    if args.cmd == "styles":
        fetch_styles(args.server)
    elif args.cmd == "fonts":
        copy_fonts(args.checkout)
    elif args.release:
        with tempfile.TemporaryDirectory() as tmp:
            extract_graph(release_db(Path(tmp)))
    else:
        extract_graph(args.db)


if __name__ == "__main__":
    main()
