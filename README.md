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
| Android | 🚧 In progress — browse, search, series, profiles, settings, Direct Play (Media3), resume |
| Android TV | 🚧 In progress — same, driven by the D-pad |
| iOS / iPadOS | 🚧 In progress — browse, search, series, profiles, settings, Direct Play (mpv), resume |
| Apple TV (tvOS) | 🚧 In progress — same, driven by the Siri Remote |

## Tech stack

**Kotlin Multiplatform shared core + native UI and native player on each platform.**

- **Shared (KMP):** Jellyfin API client (Ktor), auth, library, Direct Play decision engine.
- **Android / Android TV:** Jetpack Compose + Compose for TV, Media3 (ExoPlayer) with FFmpeg audio decoders.
- **iOS / iPadOS / tvOS:** SwiftUI, MPVKit (mpv + FFmpeg) — the only realistic path to MKV/TrueHD/ASS on Apple platforms.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full rationale.

## Status

🚧 **Early days**, but all four targets build and run.

**Getting in.** Log in against any Jellyfin server (https→http fallback for LAN servers, with a warning before credentials cross an insecure link) or pair with **Quick Connect** — the sane way to sign in on a TV. Several accounts share one install through a **"Who's watching?" picker**, each with its own device id and its own preferences.

**Browsing.** A home screen with **Continue Watching** and **Next Up**, movie and episode detail pages, **series browsing** (seasons and episodes), and **search**. Detail pages show the **audience score and the Rotten Tomatoes tomatometer**, colored fresh or rotten. Watched episodes are ticked, and unwatched previews stay blurred so a thumbnail never spoils the next episode. You choose **which libraries reach the home screen** — the music and photo ones a video player has no use for start switched off, one tap from coming back.

**Playing.** Anything playable **Direct Plays the original file** — mpv+FFmpeg on Apple platforms (the all-formats engine), Media3/ExoPlayer on Android (FFmpeg audio decoders coming next) — with pause/seek controls, **audio and subtitle track selection**, **Skip Intro** driven by the server's media segments, cross-device resume, and progress reporting back to the server. A per-profile **Always transcode** switch is there for the rare file a device cannot decode.

**Subtitles.** Films start with the right track already on: **forced subtitles when you understand the audio, full subtitles when you don't**, matched across the `fr`/`fre`/`fra` spellings that different muxers write for the same language. Size is adjustable, and **timing can be nudged in both directions** when a file's subtitles drift.

**Requesting.** Point a profile at a [Jellyseerr](https://github.com/fallenbagel/jellyseerr) and you can **search for what the library does not have, ask for it, and follow what you asked for** — on the phone, the tablet and the television. Requests go through the profile's own Jellyfin account, so quotas and history stay per person; only the session is kept, never the password.

See [docs/BUILDING.md](docs/BUILDING.md) to build, and `tools/` for the local
servers used to test subtitles and requests — the public Jellyfin demo has
neither.

## License

[MPL-2.0](LICENSE) — the core stays open and free; file-level copyleft keeps improvements open while remaining App Store-compatible (unlike GPL).
