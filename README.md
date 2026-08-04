# Jellystream

A free, modern media player for [Jellyfin](https://jellyfin.org/) — built for **Direct Play first**.

Like Infuse, but free.

## Vision

Jellystream aims to play **every format your Jellyfin server can throw at it, natively on the device**, without forcing the server to transcode: MKV, HEVC, AV1, Dolby Vision/HDR10(+), lossless audio (TrueHD, DTS-HD MA), PGS/ASS subtitles, etc.

- **Direct Play everywhere** — transcoding only as a last resort.
- **Fast, native-feeling UI** on every platform, including proper TV navigation (D-pad / Siri Remote).
- **Free** — core playback will always be free. Optional paid extras may come later.

## Target platforms

| Platform | Status |
|----------|--------|
| Android | 🚧 In progress — login, library browsing, direct playback (Media3) |
| Android TV | 🚧 In progress — login, library browsing, direct playback (Media3) |
| iOS / iPadOS | 🚧 In progress — login, library browsing, direct playback (mpv) |
| Apple TV (tvOS) | 🚧 In progress — login, library browsing, direct playback (mpv) |

## Tech stack

**Kotlin Multiplatform shared core + native UI and native player on each platform.**

- **Shared (KMP):** Jellyfin API client (Ktor), auth, library, Direct Play decision engine.
- **Android / Android TV:** Jetpack Compose + Compose for TV, Media3 (ExoPlayer) with FFmpeg audio decoders.
- **iOS / iPadOS / tvOS:** SwiftUI, MPVKit (mpv + FFmpeg) — the only realistic path to MKV/TrueHD/ASS on Apple platforms.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full rationale.

## Status

🚧 **Early days.** All four targets build and run: login against any Jellyfin server (https→http fallback for LAN servers), browse your library views, and tap a movie/episode to **Direct Play the original file** — mpv+FFmpeg on Apple platforms (the all-formats engine), Media3/ExoPlayer on Android (FFmpeg audio decoders coming next). No seeking/controls UI yet. See [docs/BUILDING.md](docs/BUILDING.md) to build.

## License

[MPL-2.0](LICENSE) — the core stays open and free; file-level copyleft keeps improvements open while remaining App Store-compatible (unlike GPL).
