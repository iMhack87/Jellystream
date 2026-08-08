package dev.jellystream.android

import android.content.Context
import dev.jellystream.shared.Watchlist

/**
 * App-private storage for the watchlist blob (the shared module owns the
 * JSON). One entry per profile, exactly like the downloads store: two
 * accounts sharing a tablet do not share what they mean to watch, and one
 * signing out must not take the other's list.
 *
 * The profile lives in the KEY, never as a field on a shared data class:
 * Kotlin default arguments are not exported to Swift, so adding a field to
 * a shared class breaks every Swift call site that legitimately omitted it.
 */
class WatchlistStore(context: Context, profileKey: String) {
    private val prefs =
        context.getSharedPreferences("jellystream_watchlist", Context.MODE_PRIVATE)
    private val key = "watchlist:$profileKey"

    fun load(): Watchlist =
        prefs.getString(key, null)?.let { Watchlist.fromJson(it) }
        // A blob we cannot read costs the list, not the launch
            ?: Watchlist()

    fun save(watchlist: Watchlist) {
        prefs.edit().putString(key, watchlist.toJson()).apply()
    }

    /** Nothing outlives an account the install no longer knows. */
    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
