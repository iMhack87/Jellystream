# Building Jellystream

## Prerequisites

- JDK 17+ (project builds with 21)
- Android SDK (compileSdk 36) — set `sdk.dir` in `local.properties`
- Xcode 16+ (Apple targets)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)

## Android / Android TV

```bash
./gradlew :androidApp:assembleDebug
```

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` — one APK serves both mobile and Android TV (leanback launcher declared).

## iOS / iPadOS / tvOS

The Apple apps consume the shared module as an XCFramework. Build it first, then generate the Xcode project:

```bash
./gradlew :shared:assembleSharedDebugXCFramework
cd appleApp && xcodegen generate
```

Then open `appleApp/Jellystream.xcodeproj` in Xcode. Schemes:

- `Jellystream` — iOS / iPadOS
- `JellystreamTV` — tvOS

Or from the CLI (simulator, no signing):

```bash
xcodebuild -project appleApp/Jellystream.xcodeproj -scheme Jellystream -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
xcodebuild -project appleApp/Jellystream.xcodeproj -scheme JellystreamTV -destination 'generic/platform=tvOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

> ⚠️ After changing shared Kotlin code, re-run `:shared:assembleSharedDebugXCFramework` before building in Xcode — the framework is not rebuilt automatically.

## Tests

```bash
./gradlew :shared:testDebugUnitTest
```
