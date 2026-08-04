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

## Vérification E2E (simulateur / émulateur)

- Serveur de test public : `demo.jellyfin.org/stable`, utilisateur `demo`, sans mot de passe. Ne jamais saisir les identifiants du serveur personnel de Matthieu.
- **Simulateur iOS** : l'hôte est en AZERTY → l'injection `text` sort du charabia. Avant de taper : `xcrun simctl spawn <udid> defaults write .GlobalPreferences AppleKeyboards -array "en_US@sw=QWERTY;hw=Automatic"` puis redémarrer le simulateur.
- **Émulateur Android** : les taps par coordonnées cassent dès que le clavier s'ouvre (le layout remonte, `safeDrawingPadding` inclut l'IME). Fiable : tap sur le 1er champ, puis `input keyevent 61` (TAB) pour passer au champ suivant, `KEYCODE_BACK` pour fermer le clavier avant de taper un bouton. L'autocorrect peut réécrire le texte ("demo"→"demon ") : toujours vérifier par capture avant de valider.

## Déploiement

Aucun pour l'instant (pas de store, pas de CI). Merge sur `main` uniquement via PR approuvée par Matthieu.
