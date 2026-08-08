# Subtitle bench

`demo.jellyfin.org` carries **no subtitles at all**, so the smart default,
the size and the resync have nothing to act on there. This is the smallest
server that does.

Three fixtures, differing only in how their tracks are tagged:

| Fixture | Audio | Subtitles | Expected with preference = French |
|---|---|---|---|
| 1 | English | forced EN, full FR | **full FR** — you don't understand the audio |
| 2 | French | forced EN, full FR | **none** — that forced track is for English speakers |
| 3 | French | forced FR, full FR | **forced FR** — only the foreign bits |

```bash
./make_fixtures.sh          # needs ffmpeg
python3 fake_jellyfin.py 8097
```

Point a client at `http://10.0.2.2:8097` from the Android emulator, or
`http://localhost:8097` from an Apple simulator. Any username, no password.

The `.mkv` files are generated, never committed.

## A show whose next season is missing

The same three files are served a second time as **season 1 of a show,
and only season 1**. Watch episode 3 to the end and the player should
offer to request season 2.

Forty-second episodes are the point: the end of a season arrives in a
minute rather than an hour.

Two details make it a real test rather than a rehearsal:

- The show reports **TMDb id 95396**, which the [Jellyseerr
  bench](../jellyseerr-bench/README.md) knows as Severance — season 1
  available, season 2 requestable. Run both and the chain works end to end.
  The two must agree on **how long season 1 is**: three episodes here,
  `episodeCount: 3` there. The prompt compares them on purpose, because a
  season whose real length exceeds what the server holds is still
  downloading rather than finished. Change one number and the prompt goes
  quiet — correctly, and confusingly.
- `ProviderIds` is served **only** on the single-item fetch, never in the
  library listing, exactly as real Jellyfin trims it. The app has to
  re-fetch the series to find the TMDb id, and this bench catches it if it
  does not.
