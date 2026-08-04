# Architecture decisions

## Decision 1 — Tech stack: KMP shared core + native UIs + native players

**Chosen:** Kotlin Multiplatform (KMP) for the shared core, fully native UI and player on each platform.

| Layer | Android / Android TV | iOS / iPadOS / tvOS |
|-------|----------------------|---------------------|
| UI | Jetpack Compose + Compose for TV | SwiftUI |
| Player | Media3 (ExoPlayer) + FFmpeg audio decoders; libmpv as fallback for exotic formats | MPVKit (mpv + FFmpeg, LGPL builds) |
| Shared core (KMP) | Jellyfin API client (Ktor), auth, library browsing, playback decision engine (Direct Play vs transcode), settings, watch state | same module, compiled to an Apple framework |

### Why

- **tvOS kills the all-in-one cross-platform options.** Flutter and Compose Multiplatform have no tvOS support; React Native only via a community fork. Apple TV is a first-class target here, not an afterthought.
- **Direct Play "all formats" requires FFmpeg-based engines.** On Apple platforms AVPlayer will never read MKV/TrueHD/DTS-HD/ASS — mpv (via MPVKit, which ships iOS+tvOS xcframeworks) is the proven path (same family of approach as Infuse's own engine). On Android, Media3 + FFmpeg extension covers audio codecs and hardware handles most video; libmpv remains available as a fallback.
- **The Infuse-level feel demands native UI**, especially TV navigation (D-pad focus model on Android TV, focus engine + Siri Remote on tvOS). Compose for TV and SwiftUI are the first-party answers.
- **KMP avoids writing the boring 40% twice.** API client, auth flows (Quick Connect, tokens), library/model layer, the Direct Play decision logic (capability profiles per device) — all shared. Kotlin/Native targets iosArm64 and tvosArm64; Ktor, kotlinx.serialization and coroutines all publish those targets.
- What stays per-platform is exactly what benefits from being native: UI and the player integration.

### Repo layout (target)

```
/shared        KMP module (API, models, playback decision engine)
/androidApp    Android + Android TV (Compose)
/appleApp      iOS + iPadOS + tvOS (SwiftUI, Xcode project)
/docs
```

### Risks / notes

- KMP adds build complexity (Gradle + Xcode integration). Accepted: cost is front-loaded, payoff is permanent.
- mpv/FFmpeg licensing: use LGPL builds (MPVKit provides them); avoid GPL-only FFmpeg components. Required for App Store distribution.
- Capability profiles (what each device can Direct Play) are the heart of the product — they live in the shared module and must be heavily tested.

## Decision 2 — License: MPL-2.0

**Chosen:** Mozilla Public License 2.0.

### Why

- **GPL is a no-go on the App Store** (incompatibility is why VLC was pulled from it in 2011 before relicensing). Distribution on the App Store is non-negotiable for this project.
- **MIT/Apache is too permissive** for the goal: anyone could ship a closed paid clone.
- **MPL-2.0 is the middle ground:** file-level copyleft — modifications to Jellystream files must stay open, but the app can link proprietary modules. That keeps the door open for optional paid features later while the core stays free and open. It's also what Swiftfin (the Jellyfin Apple client) uses, so it's a proven fit in this exact ecosystem.
