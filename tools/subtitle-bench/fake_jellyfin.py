#!/usr/bin/env python3
"""Minimal Jellyfin stand-in for subtitle and end-of-season checks.

The public demo server carries no subtitles at all, so the smart default,
the size and the resync have nothing to act on there. This serves three
files that differ only in how their tracks are tagged:

  1. English audio   + forced EN + full FR  -> full FR expected (foreign audio)
  2. French audio    + forced EN + full FR  -> nothing expected (forced track
                                               is for English speakers)
  3. French audio    + forced FR + full FR  -> forced FR expected

The same three files are served a second time as season 1 of a show whose
season 2 is deliberately missing. Watch episode 3 to the end and the
player should offer to request season 2 — the show's TMDb id is the one
the Jellyseerr bench knows as Severance, where season 2 is requestable, so
the two benches line up. Forty-second episodes make that testable in a
minute rather than an hour.

Run: python3 fake_jellyfin.py [port]
Emulator reaches the host at 10.0.2.2; simulators at localhost.
"""
import json
import os
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
TICKS = 10_000_000

USER_ID = "11111111111111111111111111111111"
TOKEN = "bench-token"

# index/type/language/forced mirror what ffprobe reports for each file
ITEMS = [
    {
        "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "name": "1 - English audio, forced EN + full FR",
        "file": "english_audio.mkv",
        "audio_lang": "eng",
        "subs": [("eng", True), ("fre", False)],
    },
    {
        "id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "name": "2 - French audio, forced EN + full FR",
        "file": "french_audio.mkv",
        "audio_lang": "fra",
        "subs": [("eng", True), ("fre", False)],
    },
    {
        "id": "cccccccccccccccccccccccccccccccc",
        "name": "3 - French audio, forced FR + full FR",
        "file": "french_audio_forced_fr.mkv",
        "audio_lang": "fra",
        "subs": [("fre", True), ("fre", False)],
    },
]
SERIES_ID = "dddddddddddddddddddddddddddddddd"
SEASON_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
SERIES_NAME = "Severance"
# Ties this show to the Jellyseerr bench's fixture of the same TMDb id,
# whose season 1 is available and season 2 is not — which is exactly the
# state the end-of-season prompt exists for.
SERIES_TMDB = "95396"

# The same three files again, as season 1. Only season 1 exists here on
# purpose: season 2 has to be missing for the prompt to have a job.
EPISODES = [
    dict(item, id=episode_id, name=title, index=n + 1)
    for n, (item, episode_id, title) in enumerate(zip(
        ITEMS,
        ("e1111111111111111111111111111111",
         "e2222222222222222222222222222222",
         "e3333333333333333333333333333333"),
        ("Good News About Hell", "Half Loop", "In Perpetuity"),
    ))
]

BY_ID = {i["id"]: i for i in ITEMS}
BY_ID.update({e["id"]: e for e in EPISODES})


def streams(item):
    out = [{"Index": 0, "Type": "Video", "Codec": "h264"}]
    out.append({
        "Index": 1, "Type": "Audio", "Codec": "aac",
        "Language": item["audio_lang"], "IsDefault": True,
        "DisplayTitle": f"{item['audio_lang']} - AAC",
    })
    for n, (lang, forced) in enumerate(item["subs"]):
        out.append({
            "Index": 2 + n, "Type": "Subtitle", "Codec": "subrip",
            "Language": lang, "IsForced": forced, "IsDefault": False,
            "IsExternal": False, "IsHearingImpaired": False,
            "DisplayTitle": f"{lang}{' forced' if forced else ''}",
        })
    return out


def dto(item):
    return {
        "Id": item["id"],
        "Name": item["name"],
        "Type": "Movie",
        "ProductionYear": 2026,
        "RunTimeTicks": 40 * TICKS,
        "Overview": "Subtitle bench fixture.",
        "MediaStreams": streams(item),
        "UserData": {"PlaybackPositionTicks": 0, "Played": False},
    }


def series_dto(with_provider_ids):
    """The show itself.

    ProviderIds is left off the list form on purpose: real Jellyfin trims
    it out of the DTO unless a single-item fetch asks for it, and the
    prompt depends on the app re-fetching the series to find the TMDb id.
    Handing it out everywhere would hide that bug rather than catch it.
    """
    payload = {
        "Id": SERIES_ID,
        "Name": SERIES_NAME,
        "Type": "Series",
        "ProductionYear": 2022,
        "Overview": "Season 1 is here; season 2 is the point of the bench.",
        "UserData": {"PlaybackPositionTicks": 0, "Played": False},
    }
    if with_provider_ids:
        payload["ProviderIds"] = {"Tmdb": SERIES_TMDB, "Imdb": "tt11280740"}
    return payload


def season_dto():
    return {
        "Id": SEASON_ID,
        "Name": "Season 1",
        "Type": "Season",
        "IndexNumber": 1,
        "SeriesId": SERIES_ID,
        "SeriesName": SERIES_NAME,
        "UserData": {"PlaybackPositionTicks": 0, "Played": False},
    }


def episode_dto(episode):
    return {
        "Id": episode["id"],
        "Name": episode["name"],
        "Type": "Episode",
        "SeriesId": SERIES_ID,
        "SeriesName": SERIES_NAME,
        "IndexNumber": episode["index"],
        "ParentIndexNumber": 1,
        "RunTimeTicks": 40 * TICKS,
        "Overview": "Forty seconds, so the end of a season arrives quickly.",
        "PremiereDate": "2022-02-18T00:00:00.0000000Z",
        "MediaStreams": streams(episode),
        "UserData": {"PlaybackPositionTicks": 0, "Played": False},
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        if "stream" not in (args[0] if args else ""):
            sys.stderr.write("  %s\n" % (fmt % args))

    def _json(self, payload, code=200):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _query(self):
        return urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        if path == "/Users/AuthenticateByName":
            return self._json({
                "AccessToken": TOKEN,
                "ServerId": "bench",
                "User": {"Id": USER_ID, "Name": "bench"},
            })
        m = re.match(r"^/Items/([^/]+)/PlaybackInfo$", path)
        if m:
            item = BY_ID.get(m.group(1))
            if not item:
                return self._json({"MediaSources": []}, 404)
            return self._json({
                "PlaySessionId": "bench-session",
                "MediaSources": [{
                    "Id": item["id"],
                    "Container": "mkv",
                    "SupportsDirectPlay": True,
                    "MediaStreams": streams(item),
                }],
            })
        if path.startswith("/Sessions/Playing"):
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        return self._json({}, 404)

    def do_GET(self):
        path = self.path.split("?")[0]

        if path == "/System/Info/Public":
            return self._json({
                "ServerName": "Subtitle Bench", "Version": "10.11.11",
                "ProductName": "Jellyfin Server", "Id": "bench",
            })
        # Downloading is a per-account permission, and the public demo has
        # it switched off — the bench says yes so the path is testable
        m = re.match(r"^/Users/([^/]+)$", path)
        if m:
            return self._json({
                "Id": USER_ID, "Name": "bench",
                "Policy": {"EnableContentDownloading": True},
            })

        # The original file, untouched. Same bytes as the stream, but the
        # endpoint a download uses.
        m = re.match(r"^/Items/([^/]+)/Download$", path)
        if m:
            item = BY_ID.get(m.group(1))
            if item:
                return self._serve(os.path.join(HERE, item["file"]))

        if path == "/UserViews":
            return self._json({"Items": [
                {"Id": "view-movies", "Name": "Movies",
                 "Type": "CollectionFolder", "CollectionType": "movies"},
                {"Id": "view-shows", "Name": "Shows",
                 "Type": "CollectionFolder", "CollectionType": "tvshows"},
            ], "TotalRecordCount": 2})
        if path.endswith("/Items/Latest"):
            parent = self._query().get("parentId", [None])[0]
            if parent == "view-shows":
                return self._json([series_dto(with_provider_ids=False)])
            if parent == "view-movies":
                return self._json([dto(i) for i in ITEMS])
            return self._json(
                [dto(i) for i in ITEMS] + [series_dto(with_provider_ids=False)]
            )
        if path.endswith("/Items/Resume") or path == "/Shows/NextUp":
            return self._json({"Items": [], "TotalRecordCount": 0})

        m = re.match(r"^/Shows/([^/]+)/Seasons$", path)
        if m:
            if m.group(1) != SERIES_ID:
                return self._json({"Items": [], "TotalRecordCount": 0})
            return self._json({"Items": [season_dto()], "TotalRecordCount": 1})

        m = re.match(r"^/Shows/([^/]+)/Episodes$", path)
        if m:
            season = self._query().get("seasonId", [None])[0]
            if m.group(1) != SERIES_ID or season not in (None, SEASON_ID):
                return self._json({"Items": [], "TotalRecordCount": 0})
            items = [episode_dto(e) for e in EPISODES]
            return self._json({"Items": items, "TotalRecordCount": len(items)})

        m = re.match(r"^/Users/[^/]+/Items/([^/]+)$", path)
        if m:
            item_id = m.group(1)
            # The single-item fetch is the only place ProviderIds exists
            if item_id == SERIES_ID:
                return self._json(series_dto(with_provider_ids=True))
            if item_id == SEASON_ID:
                return self._json(season_dto())
            episode = next((e for e in EPISODES if e["id"] == item_id), None)
            if episode:
                return self._json(episode_dto(episode))
            if item_id in BY_ID:
                return self._json(dto(BY_ID[item_id]))
        if path.startswith("/MediaSegments/"):
            return self._json({"Items": [], "TotalRecordCount": 0})
        # One track as WebVTT — what a player asks for when it needs to
        # own the cue list (Android cannot shift Media3's own timing)
        m = re.match(r"^/Videos/([^/]+)/[^/]+/Subtitles/(\d+)/0/Stream\.vtt$", path)
        if m:
            item = BY_ID.get(m.group(1))
            if item:
                # Stream index 2 is the first subtitle, 3 the second
                which = int(m.group(2)) - 2
                if 0 <= which < len(item["subs"]):
                    lang, forced = item["subs"][which]
                    name = ("forced_en" if (forced and lang == "eng") else
                            "forced_fr" if forced else "full_fr")
                    return self._vtt(os.path.join(HERE, name + ".srt"))
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        m = re.match(r"^/Videos/([^/]+)/stream$", path)
        if m:
            item = BY_ID.get(m.group(1))
            if item:
                return self._serve(os.path.join(HERE, item["file"]))
        # Images and anything else: a 404 costs a placeholder, never a crash
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _vtt(self, srt_path):
        """SRT on disk, WebVTT on the wire — the conversion a server does."""
        with open(srt_path, encoding="utf-8") as handle:
            body = handle.read().replace(",", ".")
        payload = ("WEBVTT\n\n" + body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/vtt; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _serve(self, filepath):
        size = os.path.getsize(filepath)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
        length = end - start + 1
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", "video/x-matroska")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(filepath, "rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(65536, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                remaining -= len(chunk)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8096
    print(f"Subtitle bench on :{port} — {len(ITEMS)} films, "
          f"1 show with season 1 only ({len(EPISODES)} episodes)")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
