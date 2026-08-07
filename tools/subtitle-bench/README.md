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
