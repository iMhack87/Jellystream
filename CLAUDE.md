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
- **Toute fonction `suspend` exposée à Swift doit porter `@Throws(Throwable::class)`.** Sans elle, une exception ne remonte pas comme erreur Swift : Kotlin/Native **termine le processus**. Symptôme : l'app Apple meurt au lancement dès que le serveur est injoignable, sans qu'aucun `try?` côté Swift ne serve à rien.
- **tvOS : ne JAMAIS remplacer un bouton focalisé par un autre via une branche `if`.** SwiftUI donne une identité propre à chaque branche : l'ancien bouton est détruit, le moteur de focus n'a rien où retomber (la racine n'est pas focalisable tant qu'une surcouche est ouverte), et le nouveau **dessine un anneau de focus qu'il ne possède pas**. La carte a l'air parfaitement normale et ignore chaque appui. Parade : **un seul bouton** dont le libellé et l'action changent (`primaryTitle(for:)`), jamais deux dans des branches distinctes.
  - Vérifié au simulateur tvOS, en pilotant la télécommande. **Deux tentatives de correction par le focus ont empiré les choses** (`focus = nil` puis réaffectation au tick suivant, puis un `.id()` stable) : le problème n'était pas la valeur du focus mais l'identité de la vue. Un `@FocusState` qui « affiche » le bon anneau ne prouve rien.
  - Corollaire de méthode : ce bug est passé à travers deux relectures adversariales et deux compilations vertes. Seul le pilotage réel l'a trouvé — `open -a Simulator` puis flèches/Entrée via computer-use (attention : une autre app peut reprendre le premier plan entre deux appels, cliquer d'abord dans la fenêtre du simulateur).
- **Android : la priorité de `BackHandler` dépend de l'ordre d'enregistrement, pas de l'imbrication.** Le dispatcher appelle le *dernier* callback activé. Un overlay dont le handler apparaît après coup (quand un état bascule) gagne bien sur celui de `MainActivity` — mais c'est un argument de timing, pas une garantie. Vérifié à l'émulateur pour le sélecteur de saisons et la carte de fin d'épisode ; à re-tester si l'un des deux change de structure.
- Les arguments par défaut Kotlin ne sont pas exportés vers Swift : toute API du module `shared` appelée depuis Swift doit être invocable avec tous ses paramètres explicites. **Corollaire qui mord** : ajouter un champ avec valeur par défaut à une data class partagée (ex. `PersistedSession.jellyseerr`) casse tous les appels Swift existants, qui l'omettaient légitimement.
- **SwiftUI : un `Group { if cond { … } }` dont la condition est fausse se réduit à `EmptyView`, qui n'est PAS dans l'arbre — les modificateurs posés dessus (`.task`, `.onAppear`) ne s'exécutent jamais.** Une rangée « masquée quand vide » qui charge son contenu dans son propre `.task` reste donc vide à vie : elle n'a jamais pu demander. Utiliser un vrai conteneur (`VStack`) autour du `if`. Vaut pour les trois rangées home (demandes, watchlist, favoris).
- **La fenêtre du player (`fullScreenCover`) est une modale UIKit au-dessus de toute la hiérarchie du présentateur** : un `.overlay`/`ZStack` sur RootView est peint EN DESSOUS, quel que soit l'ordre des modificateurs. Un élément vraiment global (le toast d'arrivée) exige sa propre `UIWindow` (`windowLevel = .alert + 1`, jamais `makeKeyAndVisible` — cela déplacerait la key window et, sur tvOS, le moteur de focus).
- Logique protocole Jellyfin (auth, headers `MediaBrowser`, décision Direct Play) : toujours dans `shared/`, jamais dans le code plateforme.
- **`ProviderIds` (donc le TMDb id) n'arrive QUE sur `getItem`**, jamais dans les DTO de liste — vérifié sur `demo.jellyfin.org`. Une fonctionnalité qui relie Jellyfin à Jellyseerr doit re-fetcher l'item, pas se contenter de celui de la liste. La clé est `Tmdb` (lookup insensible à la casse : elle a bougé selon les versions) et la valeur est une **chaîne**, alors que Jellyseerr veut un entier.
- **Jellyseerr répond `202` quand il n'y a rien à demander.** `POST /api/v1/request` sur des saisons déjà toutes prises lève `NoSeasonsAvailableError`, que la route traduit en **202** — vérifié dans les sources Overseerr *et* Seerr. Un client qui lit « 2xx = envoyé » annonce donc à l'utilisateur une saison qui n'a jamais été créée. Le banc renvoie ce 202 exprès, pour que l'erreur se produise ici et pas dans le salon.
- **Le spec OpenAPI de Jellyseerr est incomplet là où ça compte** : `downloadStatus` (progression Sonarr/Radarr) n'y figure pas du tout, et `MediaRequest` y omet `seasons`. Modéliser à partir des entités TypeScript, pas du spec. Deux pièges de forme : `seasons` en POST est soit `"all"` soit un tableau de **numéros**, et `mediaInfo.seasons` est **creux** (seulement les saisons connues — une absence veut dire « demandable »).

## Vérification E2E (simulateur / émulateur)

- Serveur de test public : `demo.jellyfin.org/stable`, utilisateur `demo`, sans mot de passe. Ne jamais saisir les identifiants du serveur personnel de Matthieu.
- **La démo publique n'a AUCUN sous-titre** (0 item sur 17), une seule série, et il n'existe **aucune instance Jellyseerr publique**. D'où deux bancs locaux dans `tools/` :
  ```bash
  cd tools/subtitle-bench && ./make_fixtures.sh && python3 fake_jellyfin.py 8097
  cd tools/jellyseerr-bench && python3 fake_jellyseerr.py 5055   # mot de passe : bench
  ```
  Les deux se complètent : le banc Jellyfin sert une série dont **seule la saison 1 existe**, avec le TMDb id 95396 que le banc Jellyseerr connaît comme Severance (S1 dispo, S2 demandable). Finir le 3ᵉ épisode (40 s) déclenche donc la proposition de demander la saison 2, de bout en bout. Le banc Jellyseerr fait **bouger la progression** avec l'horloge ; `POST /api/v1/bench/reset` remet les fixtures à zéro entre deux runs.
  Émulateur Android → `10.0.2.2:<port>` ; simulateurs Apple → `localhost:<port>`. **Tester hors ligne** : côté Android `adb shell settings put global airplane_mode_on 1` + broadcast ; côté Apple, tuer le banc (`pkill -f fake_jellyfin.py`) — c'est là qu'on trouve les vrais bugs. Vérifier avec `lsof -nP -iTCP:<port> -sTCP:LISTEN` qu'un banc d'une session précédente ne squatte pas le port.
- **Saisie clavier dans un simulateur Apple : ne pas compter dessus.** Sur iOS l'injection `type` sort du charabia même après avoir forcé QWERTY (`defaults write .GlobalPreferences AppleKeyboards`) ; sur tvOS le clavier de recherche ne valide aucune touche, ni au clavier ni à la souris. Préférer la **pré-injection d'état** (session, réglages, cookie) dans le plist du conteneur.
- **Émulateur Android** : les taps par coordonnées cassent dès que le clavier s'ouvre (le layout remonte, `safeDrawingPadding` inclut l'IME). Fiable : tap sur le 1er champ, puis `input keyevent 61` (TAB) pour passer au champ suivant, `KEYCODE_BACK` pour fermer le clavier avant de taper un bouton. L'autocorrect peut réécrire le texte ("demo"→"demon ") : toujours vérifier par capture avant de valider.

- **Keychain + builds non signés** : `SecItemAdd` échoue (errSecMissingEntitlement) sur les builds simulateur `CODE_SIGNING_ALLOWED=NO` → `SessionStore` retombe sur UserDefaults dans ce cas. Sur appareil signé, c'est bien le Keychain qui est utilisé. Corollaire utile en E2E : on peut pré-injecter une session (`simctl spawn <udid> defaults write dev.jellystream.tv dev.jellystream.session -string '<json PersistedSession>'`) pour sauter l'écran de login.
- **La pré-injection `simctl spawn … defaults write` ne peut pas écraser une clé que l'app a déjà écrite elle-même.** Les écritures de l'app vont dans le plist de son conteneur (`simctl get_app_container <udid> <bundle> data` → `Library/Preferences/<bundle>.plist`), qui l'emporte ; `simctl spawn defaults` écrit ailleurs et ne sert donc qu'aux clés vierges (une session sur une install neuve). Pour rejouer un état déjà écrit (watchlist, arrivées annoncées) : éditer le plist du conteneur (python `plistlib`), puis **`simctl shutdown` + `boot`** — sans le redémarrage, cfprefsd ressert sa copie en cache et l'app lit l'ancienne valeur. Symptôme trompeur : `defaults read` montre la nouvelle valeur, l'écran montre l'ancienne.
- **Simulateur tvOS : Échap ≠ Menu** (tvOS 26.5) : la touche Échap du clavier n'atteint jamais l'app (`onExitCommand` ne se déclenche pas, même hors panneau). Ne pas conclure à un bug app ; pour tester Menu, passer par Window > Show Apple TV Remote ou du matériel réel. Flèches et Entrée (select), eux, fonctionnent.

## Intégration continue

`.github/workflows/ci.yml` rejoue à chaque push et chaque PR ce qu'on faisait à la main : tests partagés + APK Android sur Linux, puis XCFramework → iOS → tvOS sur macOS (dans cet ordre, sinon on teste un framework périmé). Environ 4 min côté Linux, 7 min côté macOS.

Gratuit tant que le dépôt est **public** (minutes illimitées, runner macOS compris). S'il passait en privé : quota de 2 000 min/mois et **une minute macOS en coûte 10** → il faudrait réserver le job Apple à `main` ou au déclenchement manuel.

## Déploiement

Aucun pour l'instant (pas de store, pas de CI). Merge sur `main` uniquement via PR approuvée par Matthieu.
