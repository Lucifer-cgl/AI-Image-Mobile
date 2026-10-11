"""Small LAN-only static server with HTTP byte-range support for device tests."""

from __future__ import annotations

import argparse
import mimetypes
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


class RangeHandler(BaseHTTPRequestHandler):
    root: Path

    def _path(self) -> Path | None:
        relative = unquote(urlsplit(self.path).path).lstrip("/")
        candidate = (self.root / relative).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError:
            return None
        return candidate if candidate.is_file() else None

    def _serve(self, include_body: bool) -> None:
        path = self._path()
        if path is None:
            self.send_error(404)
            return

        size = path.stat().st_size
        start, end = 0, size - 1
        range_header = self.headers.get("Range")
        status = 200
        if range_header:
            try:
                unit, value = range_header.split("=", 1)
                if unit.strip() != "bytes" or "," in value:
                    raise ValueError
                first, last = value.split("-", 1)
                start = int(first) if first else 0
                end = int(last) if last else end
                if start < 0 or start >= size or end < start:
                    raise ValueError
                end = min(end, size - 1)
                status = 206
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return

        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        if not include_body:
            return
        with path.open("rb") as source:
            source.seek(start)
            remaining = length
            while remaining:
                chunk = source.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def do_HEAD(self) -> None:  # noqa: N802
        self._serve(False)

    def do_GET(self) -> None:  # noqa: N802
        self._serve(True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    RangeHandler.root = args.directory.resolve()
    ThreadingHTTPServer((args.bind, args.port), RangeHandler).serve_forever()


if __name__ == "__main__":
    main()
