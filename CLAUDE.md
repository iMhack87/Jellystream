# Jellystream

Lecteur Jellyfin Direct Play. Monorepo : `shared/` (KMP — API, modèles, moteur de décision Direct Play), `androidApp/` (Compose, mobile + Android TV), `appleApp/` (SwiftUI, iOS/iPadOS/tvOS via XcodeGen).

## Maintenance de ce fichier

Toute session qui découvre un piège, change une commande de build ou de déploiement, ou ajoute un outil met ce fichier à jour **dans le même commit** que le changement. Un `CLAUDE.md` faux coûte plus cher que pas de `CLAUDE.md`.

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
- Les arguments par défaut Kotlin ne sont pas exportés vers Swift : toute API du module `shared` appelée depuis Swift doit être invocable avec tous ses paramètres explicites. **Corollaire qui mord** : ajouter un champ avec valeur par défaut à une data class partagée (ex. `PersistedSession.jellyseerr`) casse tous les appels Swift existants, qui l'omettaient légitimement.
- Logique protocole Jellyfin (auth, headers `MediaBrowser`, décision Direct Play) : toujours dans `shared/`, jamais dans le code plateforme.

## Vérification E2E (simulateur / émulateur)

- Serveur de test public : `demo.jellyfin.org/stable`, utilisateur `demo`, sans mot de passe. Ne jamais saisir les identifiants du serveur personnel de Matthieu.
- **La démo publique n'a AUCUN sous-titre** (0 item sur 17) et il n'existe **aucune instance Jellyseerr publique**. D'où deux bancs locaux dans `tools/` :
  ```bash
  cd tools/subtitle-bench && ./make_fixtures.sh && python3 fake_jellyfin.py 8097
  cd tools/jellyseerr-bench && python3 fake_jellyseerr.py 5055   # mot de passe : bench
  ```
  Émulateur Android → `10.0.2.2:<port>` ; simulateurs Apple → `localhost:<port>`. Vérifier avec `lsof -nP -iTCP:<port> -sTCP:LISTEN` qu'un banc d'une session précédente ne squatte pas le port.
- **Saisie clavier dans un simulateur Apple : ne pas compter dessus.** Sur iOS l'injection `type` sort du charabia même après avoir forcé QWERTY (`defaults write .GlobalPreferences AppleKeyboards`) ; sur tvOS le clavier de recherche ne valide aucune touche, ni au clavier ni à la souris. Préférer la **pré-injection d'état** (session, réglages, cookie) dans le plist du conteneur.
- **Émulateur Android** : les taps par coordonnées cassent dès que le clavier s'ouvre (le layout remonte, `safeDrawingPadding` inclut l'IME). Fiable : tap sur le 1er champ, puis `input keyevent 61` (TAB) pour passer au champ suivant, `KEYCODE_BACK` pour fermer le clavier avant de taper un bouton. L'autocorrect peut réécrire le texte ("demo"→"demon ") : toujours vérifier par capture avant de valider.

- **Keychain + builds non signés** : `SecItemAdd` échoue (errSecMissingEntitlement) sur les builds simulateur `CODE_SIGNING_ALLOWED=NO` → `SessionStore` retombe sur UserDefaults dans ce cas. Sur appareil signé, c'est bien le Keychain qui est utilisé. Corollaire utile en E2E : on peut pré-injecter une session (`simctl spawn <udid> defaults write dev.jellystream.tv dev.jellystream.session -string '<json PersistedSession>'`) pour sauter l'écran de login.
- **Simulateur tvOS : Échap ≠ Menu** (tvOS 26.5) : la touche Échap du clavier n'atteint jamais l'app (`onExitCommand` ne se déclenche pas, même hors panneau). Ne pas conclure à un bug app ; pour tester Menu, passer par Window > Show Apple TV Remote ou du matériel réel. Flèches et Entrée (select), eux, fonctionnent.

## Déploiement

Aucun pour l'instant (pas de store, pas de CI). Merge sur `main` uniquement via PR approuvée par Matthieu.
