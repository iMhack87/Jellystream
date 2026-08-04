# Jellystream

Lecteur Jellyfin Direct Play. Monorepo : `shared/` (KMP — API, modèles, moteur de décision Direct Play), `androidApp/` (Compose, mobile + Android TV), `appleApp/` (SwiftUI, iOS/iPadOS/tvOS via XcodeGen).

## Commandes

```bash
# Android (APK debug, sert mobile + TV)
./gradlew :androidApp:assembleDebug

# Tests du module partagé
./gradlew :shared:testDebugUnitTest

# Apple : XCFramework PUIS génération du projet Xcode
./gradlew :shared:assembleSharedDebugXCFramework
cd appleApp && xcodegen generate
xcodebuild -project Jellystream.xcodeproj -scheme Jellystream -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO   # iOS
xcodebuild -project Jellystream.xcodeproj -scheme JellystreamTV -destination 'generic/platform=tvOS Simulator' build CODE_SIGNING_ALLOWED=NO # tvOS
```

## Pièges connus

- **Le XCFramework n'est PAS rebuildé par Xcode** : tout changement dans `shared/` exige de relancer `:shared:assembleSharedDebugXCFramework` avant le build Apple, sinon on teste du code périmé.
- `appleApp/Jellystream.xcodeproj` est **généré** (gitignoré) : toute modif de projet passe par `appleApp/project.yml` + `xcodegen generate`, jamais par Xcode directement.
- Les arguments par défaut Kotlin ne sont pas exportés vers Swift : toute API du module `shared` appelée depuis Swift doit être invocable avec tous ses paramètres explicites.
- Logique protocole Jellyfin (auth, headers `MediaBrowser`, décision Direct Play) : toujours dans `shared/`, jamais dans le code plateforme.

## Déploiement

Aucun pour l'instant (pas de store, pas de CI). Merge sur `main` uniquement via PR approuvée par Matthieu.
