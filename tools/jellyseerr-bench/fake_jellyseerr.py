#!/usr/bin/env python3
"""Minimal Jellyseerr stand-in.

There is no public Jellyseerr to test against, and pointing the app at a
real one would mean handling somebody's password. This answers the calls
the app makes, with fixtures covering every request state the UI has to
render.

Two things here are not in Jellyseerr's OpenAPI spec but are very much on
the wire, and the app depends on both:

  * `mediaInfo.downloadStatus` — what Sonarr/Radarr are fetching. Progress
    is computed from wall-clock, so a poll loop shows a bar that actually
    moves instead of a frozen number.
  * `mediaInfo.seasons` — a SPARSE list, holding only the seasons anybody
    has touched. Severance is seeded with season 1 available and season 2
    absent from that list, which is the case the season picker and the
    end-of-season prompt both exist for.

Run: python3 fake_jellyseerr.py [port]
Emulator reaches the host at 10.0.2.2; simulators at localhost.

Sign in with any username and the password "bench"; anything else is
rejected, so the failure path is testable too.

State is mutated in place by every request. POST /api/v1/bench/reset puts
the fixtures back, which is cheaper than restarting between E2E runs.
"""
import copy
import json
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COOKIE = "connect.sid=bench-session"
PASSWORD = "bench"

# MediaStatus: 1 unknown, 2 pending, 3 processing, 4 partial, 5 available
UNKNOWN, PENDING, PROCESSING, PARTIAL, AVAILABLE, DELETED = 1, 2, 3, 4, 5, 6

# `status` and `seasonStatus` are internal; wire() turns them into the
# mediaInfo block Jellyseerr actually sends.
CATALOGUE = [
    {"id": 693134, "mediaType": "movie", "title": "Dune: Part Two",
     "releaseDate": "2024-02-27", "posterPath": "/dune2.jpg",
     "overview": "Paul unites with the Fremen.", "status": None},
    {"id": 438631, "mediaType": "movie", "title": "Dune",
     "releaseDate": "2021-09-15", "posterPath": "/dune1.jpg",
     "overview": "A noble family is betrayed.", "status": AVAILABLE},
    {"id": 242582, "mediaType": "movie", "title": "Sicario",
     "releaseDate": "2015-09-17", "posterPath": "/sicario.jpg",
     "overview": "An idealistic agent joins a task force.",
     "status": PROCESSING},
    # Season 1 landed, season 2 has not: the season picker's whole reason
    # to exist, and the title the Jellyfin bench's show points at
    {"id": 95396, "mediaType": "tv", "name": "Severance",
     "firstAirDate": "2022-02-18", "posterPath": "/sev.jpg",
     "overview": "Work-life balance, surgically.",
     "status": PARTIAL, "seasonStatus": {1: AVAILABLE}},
    {"id": 1399, "mediaType": "tv", "name": "Game of Thrones",
     "firstAirDate": "2011-04-17", "posterPath": "/got.jpg",
     "overview": "Noble families vie for the throne.",
     "status": PROCESSING, "seasonStatus": {1: AVAILABLE, 2: PROCESSING}},
    {"id": 27205, "mediaType": "movie", "title": "Inception",
     "releaseDate": "2010-07-15", "posterPath": "/incep.jpg",
     "overview": "A thief who steals secrets.", "status": PENDING},
    # People come back from the same endpoint and must be filtered out
    {"id": 55934, "mediaType": "person", "name": "Denis Villeneuve",
     "profilePath": "/dv.jpg"},
]

# Every season TMDb knows about, whether or not anyone has it. Season 0
# is here on purpose: "all" excludes the specials server-side, so a
# picker that offers them promises what the request cannot deliver.
SEASONS = {
    95396: [
        {"seasonNumber": 0, "name": "Specials", "episodeCount": 2,
         "airDate": "2022-04-01"},
        # THREE, matching the three episodes the Jellyfin bench serves.
        # The end-of-season prompt compares the two: a season whose real
        # length exceeds what the server holds is still downloading, not
        # finished, and offering the NEXT season there would be a lie.
        # Break this number and the prompt correctly goes quiet — which
        # looks exactly like the feature being broken.
        {"seasonNumber": 1, "name": "Season 1", "episodeCount": 3,
         "airDate": "2022-02-18"},
        {"seasonNumber": 2, "name": "Season 2", "episodeCount": 10,
         "airDate": "2025-01-17"},
    ],
    1399: [
        {"seasonNumber": n, "name": f"Season {n}", "episodeCount": 10,
         "airDate": f"{2010 + n}-04-17"}
        for n in range(1, 9)
    ],
}

# What Sonarr and Radarr are fetching. `seconds` is how long the whole
# grab takes and `delay` how long before it starts, so a season shows
# several bars at different points rather than one uniform sweep.
DOWNLOADS = {
    1399: [
        {"title": "Game.of.Thrones.S02E01.1080p.WEB-DL.x264", "size": 2.4e9,
         "seconds": 180, "delay": 0, "episode": {"seasonNumber": 2, "episodeNumber": 1}},
        {"title": "Game.of.Thrones.S02E02.1080p.WEB-DL.x264", "size": 2.6e9,
         "seconds": 240, "delay": 30, "episode": {"seasonNumber": 2, "episodeNumber": 2}},
        {"title": "Game.of.Thrones.S02E03.1080p.WEB-DL.x264", "size": 2.5e9,
         "seconds": 300, "delay": 120, "episode": {"seasonNumber": 2, "episodeNumber": 3}},
    ],
    242582: [
        {"title": "Sicario.2015.1080p.BluRay.x264", "size": 8.1e9,
         "seconds": 200, "delay": 0},
    ],
}

# Seeded so "my requests" has something to show; new ones are appended
REQUESTS = [
    {"id": 1, "status": 2, "createdAt": "2026-08-01T10:00:00.000Z",
     "media": {"tmdbId": 1399, "mediaType": "tv"},
     "seasons": [{"id": 1, "seasonNumber": 2, "status": 2}]},
    {"id": 2, "status": 3, "createdAt": "2026-07-28T18:30:00.000Z",
     "media": {"tmdbId": 12345, "mediaType": "movie", "status": UNKNOWN},
     "seasons": []},
    {"id": 3, "status": 1, "createdAt": "2026-08-04T21:15:00.000Z",
     "media": {"tmdbId": 27205, "mediaType": "movie"}, "seasons": []},
    {"id": 4, "status": 2, "createdAt": "2026-08-06T09:05:00.000Z",
     "media": {"tmdbId": 242582, "mediaType": "movie"}, "seasons": []},
]

START = time.monotonic()
PRISTINE = copy.deepcopy((CATALOGUE, REQUESTS))


def find(tmdb_id):
    return next((c for c in CATALOGUE if c["id"] == tmdb_id), None)


def grabs_for(tmdb_id):
    """The download queue as it stands this second.

    Sizes count DOWN, an item that has not started yet reports no estimate
    at all (Sonarr omits it), and one that has finished its bytes stays in
    the queue while it imports — all three states the progress bar has to
    survive.
    """
    now = time.monotonic() - START
    out = []
    for grab in DOWNLOADS.get(tmdb_id, []):
        elapsed = now - grab["delay"]
        total = grab["seconds"]
        if elapsed <= 0:
            status, remaining = "queued", total
        elif elapsed >= total:
            status, remaining = "completed", 0
        else:
            status, remaining = "downloading", total - elapsed

        item = {
            "externalId": tmdb_id,
            "mediaType": "tv" if "episode" in grab else "movie",
            "size": grab["size"],
            "sizeLeft": grab["size"] * remaining / total,
            "status": status,
            "title": grab["title"],
        }
        # A queued item has no ETA, which is exactly how a stalled one
        # looks — the app must render a bar without one
        if status == "downloading":
            hours, rest = divmod(int(remaining), 3600)
            minutes, seconds = divmod(rest, 60)
            item["timeLeft"] = f"{hours:02d}:{minutes:02d}:{seconds:02d}"
            item["estimatedCompletionTime"] = (
                datetime.now(timezone.utc) + timedelta(seconds=remaining)
            ).isoformat()
        if "episode" in grab:
            item["episode"] = grab["episode"]
        out.append(item)
    return out


def media_info(entry):
    """The mediaInfo block, or None for a title nobody has ever asked for."""
    if entry.get("status") is None:
        return None
    return {
        "tmdbId": entry["id"],
        "status": entry["status"],
        # Sparse on purpose: only seasons somebody has touched
        "seasons": [
            {"id": number, "seasonNumber": number, "status": status,
             "status4k": UNKNOWN}
            for number, status in sorted(entry.get("seasonStatus", {}).items())
        ],
        "downloadStatus": grabs_for(entry["id"]),
        "downloadStatus4k": [],
    }


def wire(entry):
    """A catalogue row as Jellyseerr sends it — internals swapped for mediaInfo."""
    payload = {k: v for k, v in entry.items() if k not in ("status", "seasonStatus")}
    if entry["mediaType"] != "person":
        payload["mediaInfo"] = media_info(entry)
    return payload


def bookable_seasons(entry, asked):
    """The seasons a POST actually books.

    Mirrors Jellyseerr twice over: "all" never includes the specials, and
    seasons it already knows about are dropped silently rather than
    refused — the request succeeds with whatever is left.
    """
    known = [s["seasonNumber"] for s in SEASONS.get(entry["id"], [])
             if s["seasonNumber"] > 0]
    if asked == "all" or asked is None:
        wanted = known
    elif isinstance(asked, list):
        wanted = [int(n) for n in asked if int(n) in known]
    else:
        wanted = []
    taken = entry.get("seasonStatus", {})
    return [n for n in wanted if taken.get(n, UNKNOWN) in (UNKNOWN, DELETED)]


def request_row(row):
    """A stored request with its live media block attached."""
    entry = find(row["media"]["tmdbId"])
    media = dict(row["media"])
    if entry is not None:
        media["status"] = entry.get("status") or UNKNOWN
        media["downloadStatus"] = grabs_for(entry["id"])
    else:
        media.setdefault("downloadStatus", [])
    return {**row, "media": media}


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

        if path == "/api/v1/bench/reset":
            global START
            catalogue, requests = copy.deepcopy(PRISTINE)
            CATALOGUE[:] = catalogue
            REQUESTS[:] = requests
            # The clock too, or a bench left running for an hour resets
            # into "everything already finished" and the progress bars
            # have nothing left to show.
            START = time.monotonic()
            return self._json({"reset": True})

        if path == "/api/v1/request":
            if not self._signed_in():
                return self._json({"message": "Unauthorized"}, 401)
            return self._create_request(body)

        return self._json({"message": "Not found"}, 404)

    def _create_request(self, body):
        entry = find(body.get("mediaId"))
        if entry is None:
            return self._json({"message": "Not found"}, 404)

        booked = []
        if entry["mediaType"] == "tv":
            booked = bookable_seasons(entry, body.get("seasons"))
            if not booked:
                # Every season asked for is already spoken for. Jellyseerr
                # raises NoSeasonsAvailableError, and the request route
                # answers it with **202** — not 409, and not an error code
                # at all. Verified in the Overseerr and Seerr sources.
                # A bench returning 409 here would let a client that reads
                # "2xx means sent" pass, and it would tell people their
                # season is coming when nothing was created.
                return self._json(
                    {"message": "No seasons available to request"}, 202)
            seasons = entry.setdefault("seasonStatus", {})
            for number in booked:
                seasons[number] = PENDING
            # Partly on the server already? Then it stays partly available.
            entry["status"] = (
                PARTIAL if AVAILABLE in seasons.values() else PENDING
            )
        else:
            # Already known to Jellyseerr: this is an answer, not a failure
            if entry.get("status") in (PENDING, PROCESSING, AVAILABLE):
                return self._json({"message": "Request already exists"}, 409)
            entry["status"] = PENDING

        new_id = max((r["id"] for r in REQUESTS), default=0) + 1
        REQUESTS.append({
            "id": new_id, "status": 1,
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "media": {"tmdbId": entry["id"], "mediaType": entry["mediaType"]},
            "seasons": [{"id": n, "seasonNumber": n, "status": 1} for n in booked],
        })
        return self._json(
            {"id": new_id, "status": 1,
             "seasons": [{"id": n, "seasonNumber": n, "status": 1} for n in booked]},
            201,
        )

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
                wire(c) for c in CATALOGUE
                if term and term in (c.get("title") or c.get("name") or "").lower()
            ]
            return self._json({"page": 1, "totalPages": 1,
                               "totalResults": len(hits), "results": hits})

        m = re.match(r"^/api/v1/(tv|movie)/(\d+)$", path)
        if m:
            entry = find(int(m.group(2)))
            expected = "tv" if m.group(1) == "tv" else "movie"
            if entry is None or entry["mediaType"] != expected:
                return self._json({"message": "Not found"}, 404)
            payload = wire(entry)
            if expected == "tv":
                payload["seasons"] = [
                    {**s, "id": s["seasonNumber"], "overview": "",
                     "posterPath": entry.get("posterPath")}
                    for s in SEASONS.get(entry["id"], [])
                ]
            return self._json(payload)

        if path == "/api/v1/request":
            if not self._signed_in():
                return self._json({"message": "Unauthorized"}, 401)
            newest = sorted(REQUESTS, key=lambda r: r["createdAt"], reverse=True)
            return self._json({"pageInfo": {"results": len(newest)},
                               "results": [request_row(r) for r in newest]})

        return self._json({"message": "Not found"}, 404)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5055
    print(f"Jellyseerr bench on :{port} — password is '{PASSWORD}'")
    print("  progress moves for a few minutes from startup; "
          "POST /api/v1/bench/reset to start over")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
