#!/usr/bin/env python3
"""Minimal Jellyseerr stand-in.

There is no public Jellyseerr to test against, and pointing the app at a
real one would mean handling somebody's password. This answers the four
calls the app makes, with fixtures covering every request state the UI has
to render.

Run: python3 fake_jellyseerr.py [port]
Emulator reaches the host at 10.0.2.2; simulators at localhost.

Sign in with any username and the password "bench"; anything else is
rejected, so the failure path is testable too.
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "connect.sid=bench-session"
PASSWORD = "bench"

# MediaStatus: 1 unknown, 2 pending, 3 processing, 4 partial, 5 available
CATALOGUE = [
    {"id": 693134, "mediaType": "movie", "title": "Dune: Part Two",
     "releaseDate": "2024-02-27", "posterPath": "/dune2.jpg",
     "overview": "Paul unites with the Fremen.", "mediaInfo": None},
    {"id": 438631, "mediaType": "movie", "title": "Dune",
     "releaseDate": "2021-09-15", "posterPath": "/dune1.jpg",
     "overview": "A noble family is betrayed.", "mediaInfo": {"status": 5}},
    {"id": 95396, "mediaType": "tv", "name": "Severance",
     "firstAirDate": "2022-02-18", "posterPath": "/sev.jpg",
     "overview": "Work-life balance, surgically.", "mediaInfo": {"status": 4}},
    {"id": 1399, "mediaType": "tv", "name": "Game of Thrones",
     "firstAirDate": "2011-04-17", "posterPath": "/got.jpg",
     "overview": "Noble families vie for the throne.", "mediaInfo": {"status": 3}},
    {"id": 27205, "mediaType": "movie", "title": "Inception",
     "releaseDate": "2010-07-15", "posterPath": "/incep.jpg",
     "overview": "A thief who steals secrets.", "mediaInfo": {"status": 2}},
    # People come back from the same endpoint and must be filtered out
    {"id": 55934, "mediaType": "person", "name": "Denis Villeneuve",
     "profilePath": "/dv.jpg"},
]

# Seeded so "my requests" has something to show; new ones are appended
REQUESTS = [
    {"id": 1, "status": 2, "createdAt": "2026-08-01T10:00:00.000Z",
     "media": {"tmdbId": 1399, "mediaType": "tv", "status": 3}},
    {"id": 2, "status": 3, "createdAt": "2026-07-28T18:30:00.000Z",
     "media": {"tmdbId": 12345, "mediaType": "movie", "status": 1}},
    {"id": 3, "status": 1, "createdAt": "2026-08-04T21:15:00.000Z",
     "media": {"tmdbId": 27205, "mediaType": "movie", "status": 2}},
]


def find(tmdb_id):
    return next((c for c in CATALOGUE if c["id"] == tmdb_id), None)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))

    def _json(self, payload, code=200, cookie=None):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", f"{cookie}; Path=/; HttpOnly")
        self.end_headers()
        self.wfile.write(body)

    def _signed_in(self):
        return COOKIE in (self.headers.get("Cookie") or "")

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            body = {}

        if path == "/api/v1/auth/jellyfin":
            if body.get("password") != PASSWORD:
                return self._json({"message": "Invalid credentials"}, 401)
            return self._json(
                {"id": 7, "displayName": body.get("username") or "bench"},
                cookie=COOKIE,
            )

        if path == "/api/v1/request":
            if not self._signed_in():
                return self._json({"message": "Unauthorized"}, 401)
            tmdb_id = body.get("mediaId")
            entry = find(tmdb_id)
            status = (entry or {}).get("mediaInfo") or {}
            # Already known to Jellyseerr: this is an answer, not a failure
            if status.get("status") in (2, 3, 5):
                return self._json({"message": "Request already exists"}, 409)
            new_id = max((r["id"] for r in REQUESTS), default=0) + 1
            REQUESTS.append({
                "id": new_id, "status": 1, "createdAt": "2026-08-05T22:00:00.000Z",
                "media": {"tmdbId": tmdb_id, "mediaType": body.get("mediaType"),
                          "status": 2},
            })
            if entry is not None:
                entry["mediaInfo"] = {"status": 2}
            return self._json({"id": new_id, "status": 1}, 201)

        return self._json({"message": "Not found"}, 404)

    def do_GET(self):
        path = self.path.split("?")[0]
        query = self.path.split("?")[1] if "?" in self.path else ""

        if path == "/api/v1/status":
            return self._json({"version": "2.1.0", "commitTag": "bench"})

        if path == "/api/v1/auth/me":
            if not self._signed_in():
                return self._json({"message": "Unauthorized"}, 401)
            return self._json({"id": 7, "displayName": "bench"})

        if path == "/api/v1/search":
            m = re.search(r"query=([^&]*)", query)
            term = (m.group(1) if m else "").replace("+", " ").replace("%20", " ").lower()
            hits = [
                c for c in CATALOGUE
                if term and term in (c.get("title") or c.get("name") or "").lower()
            ]
            return self._json({"page": 1, "totalPages": 1,
                               "totalResults": len(hits), "results": hits})

        if path == "/api/v1/request":
            if not self._signed_in():
                return self._json({"message": "Unauthorized"}, 401)
            newest = sorted(REQUESTS, key=lambda r: r["createdAt"], reverse=True)
            return self._json({"pageInfo": {"results": len(newest)},
                               "results": newest})

        return self._json({"message": "Not found"}, 404)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5055
    print(f"Jellyseerr bench on :{port} — password is '{PASSWORD}'")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
