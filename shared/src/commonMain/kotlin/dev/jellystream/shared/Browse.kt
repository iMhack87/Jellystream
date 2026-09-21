package dev.jellystream.shared

/**
 * What a catalog screen asks the server for, decided once so the two apps
 * cannot disagree about "this genre" vs "this collection".
 */
object CatalogQuery {
    @Throws(Throwable::class)
    suspend fun load(api: JellyfinApi, item: BaseItem): List<BaseItem> = when {
        item.isBoxSet -> api.getLibraryItems(
            parentId = item.id,
            includeItemTypes = "Movie,Series",
            genreIds = "",
            personIds = "",
            recursive = true,
            limit = 200,
        )
        item.isGenre -> api.getLibraryItems(
            parentId = "",
            includeItemTypes = "Movie,Series",
            genreIds = item.id,
            personIds = "",
            recursive = true,
            limit = 200,
        )
        item.isPerson -> api.getLibraryItems(
            parentId = "",
            includeItemTypes = "Movie,Series",
            genreIds = "",
            personIds = item.id,
            recursive = true,
            limit = 200,
        )
        else -> emptyList()
    }

    fun actors(people: List<PersonCredit>): List<PersonCredit> {
        val withId = people.filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
        val actors = withId.filter {
            it.type.equals("Actor", ignoreCase = true) ||
                it.type.equals("GuestStar", ignoreCase = true)
        }
        return (actors.ifEmpty { withId }).take(16)
    }
}
