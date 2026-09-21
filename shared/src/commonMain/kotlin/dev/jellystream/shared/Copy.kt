package dev.jellystream.shared

/**
 * Every string the user sees. French when the device is French, English
 * otherwise — hardcoded English was why switching the system language
 * changed nothing.
 */
object Copy {
    val french: Boolean
        get() = currentLanguageTag().lowercase().startsWith("fr")

    private fun t(fr: String, en: String): String = if (french) fr else en

    val connect get() = t("Connexion", "Connect")
    val quickConnect get() = t("Connexion rapide", "Use Quick Connect")
    val serverUrl get() = t("Adresse du serveur", "Server URL")
    val username get() = t("Identifiant", "Username")
    val password get() = t("Mot de passe", "Password")
    val server get() = t("Serveur", "Server")
    val addProfile get() = t("Ajouter un profil", "Add Profile")
    val backToProfiles get() = t("Retour aux profils", "Back to profiles")
    val whoIsWatching get() = t("Qui regarde ?", "Who's watching?")
    val unencryptedTitle get() = t("Connexion non chiffrée", "Unencrypted connection")
    val unencryptedBody get() = t(
        "Ce serveur n’est joignable qu’en HTTP. Le mot de passe et les flux circuleraient en clair sur le réseau.",
        "This server is only reachable over plain HTTP. Your password and streams would travel unencrypted on the network.",
    )
    val connectAnyway get() = t("Continuer quand même", "Connect Anyway")
    val cancel get() = t("Annuler", "Cancel")
    val save get() = t("Enregistrer", "Save")
    val remove get() = t("Retirer", "Remove")
    val close get() = t("Fermer", "Close")
    val done get() = t("OK", "Done")
    val ok get() = t("OK", "OK")
    val back get() = t("Retour", "Back")
    val search get() = t("Recherche", "Search")
    val home get() = t("Accueil", "Home")
    val settings get() = t("Réglages", "Settings")
    val downloads get() = t("Téléchargements", "Downloads")
    val requests get() = t("Demandes", "Requests")
    val account get() = t("Compte", "Account")
    val switchProfile get() = t("Changer de profil", "Switch Profile")
    val logOut get() = t("Déconnexion", "Log Out")
    val play get() = t("Lecture", "Play")
    val resume get() = t("Reprendre", "Resume")
    fun resumeMinutes(minutes: Int) = t("Reprendre ($minutes min)", "Resume ($minutes min)")
    val markWatched get() = t("Marquer comme vu", "Mark as watched")
    val markUnwatched get() = t("Marquer comme non vu", "Mark as unwatched")
    val addFavourite get() = t("Ajouter aux favoris", "Add favourite")
    val removeFavourite get() = t("Retirer des favoris", "Remove favourite")
    val addWatchlist get() = t("Ajouter à la liste", "Add to watchlist")
    val removeWatchlist get() = t("Retirer de la liste", "Remove from watchlist")
    val download get() = t("Télécharger", "Download")
    val downloading get() = t("Téléchargement…", "Downloading…")
    val queued get() = t("En file", "Queued")
    val queuedDownload get() = t("En file d’attente", "Queued for download")
    val onDevice get() = t("Sur l’appareil", "On device")
    val offlineAvailable get() = t("Disponible hors ligne", "Available offline")
    val downloadFailed get() = t("Échec du téléchargement", "Download failed")
    val failed get() = t("Échec", "Failed")
    val nothingDownloaded get() = t(
        "Rien de téléchargé pour l’instant. Ouvre un film ou un épisode et appuie sur Télécharger.",
        "Nothing downloaded yet. Open a film or an episode and tap Download to keep it on this device.",
    )
    val goToDownloads get() = t("Voir les téléchargements", "Go to downloads")
    val cantReachServer get() = t("Serveur injoignable.", "Can't reach the server.")
    fun downloadsStillOnDevice(n: Int) = if (french) {
        if (n == 1) "1 titre téléchargé est encore sur cet appareil."
        else "$n titres téléchargés sont encore sur cet appareil."
    } else {
        if (n == 1) "1 downloaded title is still on this device."
        else "$n downloaded titles are still on this device."
    }
    val continueWatching get() = t("Reprendre", "Continue Watching")
    val nextUp get() = t("À suivre", "Next Up")
    val collections get() = t("Collections", "Collections")
    val genres get() = t("Genres", "Genres")
    val movies get() = t("Films", "Movies")
    val shows get() = t("Séries", "Shows")
    val films get() = t("Films", "Films")
    val series get() = t("Séries", "Series")
    val film get() = t("Film", "Film")
    val all get() = t("Tout", "All")
    val onTheServer get() = t("Sur le serveur", "On the server")
    val requestable get() = t("À demander", "Requestable")
    val yourRequests get() = t("Tes demandes", "Your requests")
    val results get() = t("Résultats", "Results")
    val searchToRequest get() = t("Chercher un titre à demander", "Search for something to request")
    val nothingRequestedYet get() = t(
        "Aucune demande pour l’instant. Cherche ci-dessus pour un film ou une série.",
        "Nothing requested yet. Search above to ask for a film or a series.",
    )
    fun nothingFound(query: String) = t("Aucun résultat pour « $query ».", "Nothing found for \"$query\".")
    val nothingInHere get() = t("Rien ici pour l’instant", "Nothing in here yet")
    val couldntLoad get() = t("Impossible de charger", "Couldn't load this")
    val couldntReach get() = t("Serveur injoignable", "Couldn't reach the server")
    val signInSeerrAgain get() = t("Reconnecte-toi à Jellyseerr dans les Réglages", "Sign in to Jellyseerr again in Settings")
    val couldNotReachSeerr get() = t("Jellyseerr injoignable", "Could not reach Jellyseerr")
    val pickAtLeastOneSeason get() = t("Choisis au moins une saison", "Pick at least one season")
    val noSeerrServer get() = t("Aucun serveur Jellyseerr défini", "No Jellyseerr server set")
    fun seerrSaid(status: Int) = t("Jellyseerr a répondu $status", "Jellyseerr said $status")
    fun requested(title: String) = t("Demande envoyée : $title", "Requested $title")
    fun alreadyRequestedTitle(title: String) = t("$title était déjà demandé", "$title was already requested")
    fun requestedSeason(title: String, n: Int) = t("Saison $n de $title demandée", "Requested $title season $n")
    fun seasonAlreadyRequested(n: Int) = t("La saison $n était déjà demandée", "Season $n was already requested")
    val allSeasons get() = t("Toutes les saisons", "All seasons")
    val season get() = t("Saison", "Season")
    fun chapter(n: Int) = t("Chapitre $n", "Chapter $n")
    val chapters get() = t("Chapitres", "Chapters")
    val info get() = t("Infos", "Info")
    val audio get() = t("Audio", "Audio")
    val subtitles get() = t("Sous-titres", "Subtitles")
    val sync get() = t("Synchro", "Sync")
    val earlier get() = t("Plus tôt", "Earlier")
    val later get() = t("Plus tard", "Later")
    val reset get() = t("Réinitialiser", "Reset")
    val off get() = t("Désactivé", "Off")
    val inSync get() = t("Synchro", "In sync")
    val subtitlesInSync get() = t("Sous-titres synchronisés", "Subtitles in sync")
    fun subtitlesLater(seconds: String) = t("Sous-titres $seconds s plus tard", "Subtitles ${seconds}s later")
    fun subtitlesEarlier(seconds: String) = t("Sous-titres $seconds s plus tôt", "Subtitles ${seconds}s earlier")
    val external get() = t("Externe", "External")
    val skipIntro get() = t("Passer l’intro", "Skip Intro")
    val skipCredits get() = t("Passer le générique", "Skip Credits")
    val playNow get() = t("Lire", "Play now")
    val notNow get() = t("Plus tard", "Not now")
    val upNext get() = t("À suivre", "Up next")
    val nextSeason get() = t("Saison suivante", "Next season")
    val nextEpisode get() = t("Épisode suivant", "Next episode")
    fun playingIn(seconds: Int) = t("Lecture dans ${seconds}s", "Playing in ${seconds}s")
    fun requestSeason(n: Int) = t("Demander la saison $n", "Request season $n")
    val requestedShort get() = t("Demandé", "Requested")
    fun seasonRequestedLanding(n: Int) = t(
        "Saison $n demandée — elle apparaîtra une fois téléchargée.",
        "Season $n requested — it'll appear once it downloads.",
    )
    val requestedLanding get() = t(
        "Demandé — ça apparaîtra une fois téléchargé.",
        "Requested — it'll appear once it downloads.",
    )
    val closePlayer get() = t("Fermer le lecteur", "Close player")
    val couldNotPlay get() = t("Impossible de lire ce titre", "This item could not be played")
    val jellyseerrServer get() = t("Serveur Jellyseerr", "Jellyseerr server")
    val address get() = t("Adresse", "Address")
    val signIn get() = t("Connexion", "Sign in")
    val signedIn get() = t("Connecté", "Signed in")
    val notSet get() = t("Non défini", "Not set")
    val browseAndRequest get() = t("Parcourir et demander", "Browse and request")
    val homeScreen get() = t("Écran d’accueil", "Home Screen")
    val whenToShow get() = t("Quand afficher", "When to show")
    val language get() = t("Langue", "Language")
    val size get() = t("Taille", "Size")
    val playback get() = t("Lecture", "Playback")
    val playNextAuto get() = t("Enchaîner l’épisode suivant", "Play next episode automatically")
    val alwaysTranscode get() = t("Toujours transcoder", "Always transcode")
    val about get() = t("À propos", "About")
    val offline get() = t("Hors ligne", "Offline")
    val cast get() = t("Distribution", "Cast")
    val library get() = t("Médiathèque", "Library")
    val sessionExpired get() = t("Session expirée — reconnecte-toi", "Session expired — please sign in again")
    val quickConnectUnavailable get() = t("Connexion rapide indisponible sur ce serveur", "Quick Connect is unavailable on this server")
    val quickConnectExpired get() = t("Code de connexion rapide expiré — réessaie", "Quick Connect code expired — try again")
    val enterCode get() = t(
        "Saisis ce code dans Jellyfin sur ton téléphone ou ton navigateur",
        "Enter this code in Jellyfin on your phone or browser",
    )
    val seerrRefused get() = t("Jellyseerr a refusé ces identifiants.", "Jellyseerr refused those credentials.")
    val jellyfinPassword get() = t("Mot de passe Jellyfin", "Jellyfin password")
    val requestsFooter get() = t(
        "Les demandes partent avec le compte Jellyfin de ce profil, pour que quotas et historique restent les tiens. Seule la session est gardée, jamais le mot de passe.",
        "Requests are made with this profile's own Jellyfin account, so quotas and history stay yours. Only the session is kept — never the password.",
    )
    val subtitleHelp get() = t(
        "Intelligent : sous-titres complets si l’audio n’est pas dans ta langue, forcés seulement sinon. La langue de l’appareil suit le système.",
        "Smart turns on full subtitles when the audio is not in your language, and only forced ones when it is. Device language follows the system.",
    )
    val playNextHelp get() = t(
        "À la fin d’un épisode, le suivant démarre après un compte à rebours. Désactivé : la même carte s’affiche, elle attend juste que tu appuies.",
        "When an episode ends, the next one starts after a countdown you can stop. Off keeps the same card — it just waits for you.",
    )
    val transcodeHelp get() = t(
        "Direct Play envoie le fichier original. Active le transcodage seulement si un titre saccade : le serveur le ré-encode, au détriment du CPU et de la qualité.",
        "Direct Play sends the original file untouched — leave this off. Turn it on only if a title stutters: the server will re-encode it, at the cost of CPU and quality.",
    )
    val downloadNotAllowed get() = t(
        "Ton compte Jellyfin n’a pas le droit de télécharger. Demande au propriétaire du serveur.",
        "Your Jellyfin account is not allowed to download. Ask the server owner to enable it.",
    )
    val available get() = t("Disponible", "Available")
    val partlyAvailable get() = t("En partie dispo", "Partly available")
    val awaitingApproval get() = t("En attente", "Awaiting approval")
    val declined get() = t("Refusé", "Declined")
    val request get() = t("Demander", "Request")
    val forcedOnly get() = t("Forcés seulement", "Forced only")
    val smart get() = t("Intelligent", "Smart")
    val alwaysOn get() = t("Toujours", "Always on")
    val directPlay get() = t("Lecture directe", "Direct Play")
    val transcode get() = t("Transcodage", "Transcode")
    fun arrived(title: String, seasons: String?) = buildString {
        append(title)
        if (!seasons.isNullOrBlank()) {
            append(" ")
            append(if (french) seasons.lowercase() else seasons.lowercase())
        }
        append(if (french) " est arrivé" else " has arrived")
    }
    fun track(id: Int) = t("Piste $id", "Track $id")
    fun minutes(n: Int) = t("$n min", "$n min")
    fun episode(n: Int) = t("ÉPISODE $n", "EPISODE $n")
    fun audienceRating(score: String) = t("Note spectateurs $score sur 10", "Audience rating $score out of 10")
    fun criticRating(percent: String) = t("Note critiques $percent", "Critic rating $percent")
    val details get() = t("Fiche", "Details")
    val signingIn get() = t("Connexion…", "Signing in…")
    val signInToJellyseerr get() = t("Connexion à Jellyseerr", "Sign in to Jellyseerr")
    val watchlist get() = t("Liste à voir", "Watchlist")
    val favourites get() = t("Favoris", "Favourites")
    val normal get() = t("Normal", "Normal")
    val loading get() = t("Chargement…", "Loading…")
    val notOnServerYet get() = t("Pas encore sur le serveur", "Not on the server yet")
    val onTheServerLower get() = t("sur le serveur", "on the server")
    val requestedOnTheWay get() = t("Demandé et en route", "Requested & on the way")
    val seriesRequest get() = t("Demande de série", "Series request")
    val filmRequest get() = t("Demande de film", "Film request")
    val specials get() = t("Épisodes spéciaux", "Specials")
    val connectionFailed get() = t("Connexion impossible", "Connection failed")
    val downloadFailedRetry get() = t(
        "Échec du téléchargement — appuie sur Télécharger pour réessayer",
        "Download failed — tap Download to retry",
    )
    val finishingUp get() = t("Finalisation", "Finishing up")
    val anyMomentNow get() = t("D’un instant à l’autre", "Any moment now")
    val underAMinuteLeft get() = t("Moins d’une minute", "Under a minute left")
    val remainingAboutADay get() = t("Environ un jour restant", "About a day left")
    fun remainingMinutes(n: Long) = t("$n min restantes", "$n min left")
    fun remainingHours(n: Long) = t("$n h restantes", "$n h left")
    fun remainingHoursMinutes(hours: Long, minutes: Long) =
        t("$hours h $minutes min restantes", "$hours h $minutes min left")
    fun remainingDays(n: Long) = t("Environ $n jours restants", "About $n days left")
    fun episodesCount(n: Int) = if (french) {
        if (n == 1) "1 épisode" else "$n épisodes"
    } else {
        if (n == 1) "1 episode" else "$n episodes"
    }
    fun couldntLoadSeasons(title: String) = t(
        "Impossible de charger les saisons de $title.",
        "Couldn't load seasons for $title.",
    )
    fun seasonNumber(n: Int) = t("Saison $n", "Season $n")
    fun seasonsList(list: String) = t("Saisons $list", "Seasons $list")
    fun seasonOnTheWay(n: Int) = t("La saison $n est en route", "Season $n is on the way")
    fun seasonNotOnServer(n: Int) = t("La saison $n n’est pas sur le serveur", "Season $n isn't on the server")
    fun alreadyRequestedBody(name: String) = t(
        "$name · déjà demandée, rien à faire.",
        "$name · already requested, nothing to do.",
    )
    fun oneEpisodeLeft(season: Int, name: String) = t(
        "Un épisode restant de la saison $season de $name.",
        "One episode left of season $season of $name.",
    )
    fun episodesLeftOfSeason(n: Int, season: Int, name: String) = t(
        "$n épisodes restants de la saison $season de $name.",
        "$n episodes left of season $season of $name.",
    )
    fun lastEpisodeOfSeason(season: Int, name: String) = t(
        "C’était le dernier épisode de la saison $season de $name.",
        "That was the last episode of season $season of $name.",
    )
    fun subtitleHelpNamed(device: String) = t(
        "Intelligent : sous-titres complets si l’audio n’est pas dans ta langue, forcés seulement sinon. La langue de l’appareil suit le système : $device.",
        "Smart turns on full subtitles when the audio is not in your language, and only forced ones when it is. Device language follows the system: $device.",
    )
}
