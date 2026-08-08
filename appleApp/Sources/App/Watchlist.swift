import Foundation
import SwiftUI
import Shared

/**
 The profile's watchlist, owned and persisted the way downloads are.

 One blob per profile under its own key — never a new field on a shared
 data class, which would break every Swift call site that legitimately
 omitted it (Kotlin default arguments do not cross the bridge).

 An ObservableObject rather than a plain `@State` per screen because the
 same list is read on the home screen and written from a detail page two
 pushes deeper: adding a title there has to move the home row, and a
 screen that only re-reads on `.task` would stay stale on the way back.
 */
@MainActor
final class WatchlistStore: ObservableObject {
    @Published private(set) var watchlist: Watchlist

    private let profileKey: String

    init(profileKey: String) {
        self.profileKey = profileKey
        // A corrupt blob costs the list, not the launch
        self.watchlist = UserDefaults.standard.string(forKey: Self.key(profileKey))
            .flatMap { Watchlist.companion.fromJson(json: $0) }
            ?? Watchlist(entries: [])
    }

    func contains(entry: WatchlistEntry) -> Bool { watchlist.contains(entry: entry) }

    func toggle(entry: WatchlistEntry) { publish(watchlist.toggled(entry: entry)) }

    /**
     Fills in item ids for entries whose title has since landed.

     Handed whatever the home screen already loaded — an entry added from
     search only knows a TMDb id, and nothing can open a TMDb id. Written
     back only when it actually changed, or the home row would re-save
     (and re-render) on every pass.
     */
    func reconcile(onServer: [BaseItem]) {
        let next = watchlist.reconciled(onServer: onServer)
        guard next.toJson() != watchlist.toJson() else { return }
        publish(next)
    }

    /// A stable stamp of the list, for `.task(id:)`: the bridged Kotlin
    /// objects are classes, and their identity changes on every copy.
    var stamp: String {
        watchlist.entries.map(\.rowKey).joined(separator: ",")
    }

    private func publish(_ updated: Watchlist) {
        watchlist = updated
        UserDefaults.standard.set(updated.toJson(), forKey: Self.key(profileKey))
    }

    private static func key(_ profileKey: String) -> String {
        "dev.jellystream.watchlist.\(profileKey)"
    }

    /// Signing out must take the list with it — nothing should survive an
    /// account the install no longer knows.
    static func drop(profileKey: String) {
        UserDefaults.standard.removeObject(forKey: key(profileKey))
    }
}

/**
 Which arrivals have already been announced, per profile.

 Persisted for one reason: without it every cold start re-announces
 everything ever requested, and the toast becomes noise within a day.
 A missing blob is the profile's first look, which announces nothing.
 */
enum ArrivalStore {
    private static func key(_ profileKey: String) -> String {
        "dev.jellystream.arrivals.\(profileKey)"
    }

    /// Nil means "never looked" — and an unreadable blob is treated the
    /// same, which costs one silent poll instead of a burst of toasts.
    static func load(profileKey: String) -> AnnouncedArrivals? {
        UserDefaults.standard.string(forKey: key(profileKey))
            .flatMap { AnnouncedArrivals.companion.fromJson(json: $0) }
    }

    static func save(_ announced: AnnouncedArrivals, profileKey: String) {
        UserDefaults.standard.set(announced.toJson(), forKey: key(profileKey))
    }

    static func drop(profileKey: String) {
        UserDefaults.standard.removeObject(forKey: key(profileKey))
    }
}

extension WatchlistEntry {
    /// Identity for `ForEach`: an entry has either an item id or a TMDb
    /// id, and the bridged object's own hash changes with every copy.
    var rowKey: String {
        if let itemId { return itemId }
        if let tmdbId { return "tmdb-\(tmdbId.intValue)" }
        return title
    }
}

/// The active profile's watchlist, reachable from a detail page without
/// threading it through every navigation destination — the downloader's
/// pattern, for the same reason.
private struct WatchlistKey: EnvironmentKey {
    static let defaultValue: WatchlistStore? = nil
}

extension EnvironmentValues {
    var watchlist: WatchlistStore? {
        get { self[WatchlistKey.self] }
        set { self[WatchlistKey.self] = newValue }
    }
}

/// Add to / remove from the watchlist. Offered on every search row, on
/// the detail screen and on the series screen — the three places where
/// someone decides they mean to watch something.
struct WatchlistButton: View {
    let entry: WatchlistEntry
    @Environment(\.watchlist) private var store

    var body: some View {
        // No profile, no list: better a missing control than a dead one
        if let store {
            WatchlistToggle(store: store, entry: entry)
        }
    }
}

/// Its own view so the icon follows the list: `@Environment` hands over
/// the object but does not observe it — the same trap `DownloadControl`
/// documents, where the row stayed on "Download".
private struct WatchlistToggle: View {
    @ObservedObject var store: WatchlistStore
    let entry: WatchlistEntry

    var body: some View {
        let listed = store.contains(entry: entry)
        Button {
            store.toggle(entry: entry)
        } label: {
            Image(systemName: listed ? "bookmark.fill" : "bookmark")
                .font(.title3)
        }
        // tvOS keeps the system button so the focus ring applies
        #if !os(tvOS)
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        #endif
        .accessibilityLabel(listed ? "Remove from watchlist" : "Add to watchlist")
    }
}
