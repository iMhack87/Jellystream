import SwiftUI
import Shared

/**
 One search over the library AND Jellyseerr.

 Asking for something used to mean going into Settings to find the
 requests screen, which is a detour nobody takes — so both answers are
 one list here, and the only difference between two rows is what the
 button on them does. Without a Jellyseerr (not configured, or off), the
 shared searcher answers with the library half alone: this is then
 exactly the old search screen, never a broken one.
 */
struct SearchView: View {
    let api: JellyfinApi
    let seerr: JellyseerrApi

    @State private var query = ""
    @State private var hits: [SearchHit] = []
    @State private var kind: Kind = .all
    @State private var availability: Availability = .all
    @State private var notice: String?
    /// Optimistic per-title state, keyed by TMDb id: the row has to react
    /// before the server agrees, or it looks broken. Same rule as the
    /// requests screen it replaces.
    @State private var justRequested: [Int: RequestState] = [:]

    /// Native mirrors of the shared enums: SwiftUI selection wants a
    /// value type, and a bridged Kotlin enum is a class.
    private enum Kind: String, CaseIterable, Identifiable {
        case all, films, series
        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: return "All"
            case .films: return "Films"
            case .series: return "Series"
            }
        }

        var shared: SearchKind {
            switch self {
            case .all: return .all
            case .films: return .films
            case .series: return .series
            }
        }
    }

    private enum Availability: String, CaseIterable, Identifiable {
        case all, onServer, requestable
        var id: String { rawValue }

        var label: String {
            switch self {
            case .all: return "All"
            case .onServer: return "On the server"
            case .requestable: return "Requestable"
            }
        }

        var shared: SearchAvailability {
            switch self {
            case .all: return .all
            case .onServer: return .onServer
            case .requestable: return .requestable
            }
        }
    }

    var body: some View {
        // A ScrollView, not a List: `.searchable` does not attach to a
        // plain List on tvOS (the requests screen documents the fallback)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                filters

                if let notice {
                    Text(notice)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, HomeMetrics.edgePadding)
                        .padding(.bottom, 8)
                }

                ForEach(hits, id: \.rowKey) { hit in
                    SearchRow(
                        api: api,
                        seerr: seerr,
                        hit: hit,
                        // requestState is nil on the server — there is
                        // nothing to ask for, so nothing to override
                        state: hit.requestState.map { known in
                            hit.tmdbId.flatMap { justRequested[$0.intValue] } ?? known
                        },
                        notice: $notice,
                        onRequest: { request(hit) }
                    )
                    .padding(.horizontal, HomeMetrics.edgePadding)
                    .padding(.vertical, 6)
                }
            }
            .padding(.bottom, 40)
        }
        .navigationTitle("Search")
        .searchable(text: $query)
        .task(id: searchKey) { await run() }
    }

    /// Pills in the season-pill style. The availability axis only appears
    /// with a Jellyseerr configured: without one, "Requestable" could
    /// never match anything and would read as a broken filter.
    private var filters: some View {
        VStack(alignment: .leading, spacing: 10) {
            pillRow {
                ForEach(Kind.allCases) { choice in
                    pill(choice.label, selected: choice == kind) { kind = choice }
                }
            }
            if seerr.isConfigured {
                pillRow {
                    ForEach(Availability.allCases) { choice in
                        pill(choice.label, selected: choice == availability) {
                            availability = choice
                        }
                    }
                }
            }
        }
        .padding(.vertical, 12)
    }

    /// Scrolling, like the season pills: "On the server · Requestable"
    /// does not fit an iPhone, and a pill that wraps mid-word reads as a
    /// layout bug rather than a filter.
    @ViewBuilder
    private func pillRow<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                content()
            }
            .padding(.horizontal, HomeMetrics.edgePadding)
            .padding(.vertical, 4)
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
    }

    @ViewBuilder
    private func pill(
        _ label: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        #if os(tvOS)
        // Untouched system button so Liquid Glass and the focus engine
        // stay intact; unlike the season pills there is no shelf below
        // saying which one is on, so the state is a checkmark
        Button(action: action) {
            HStack(spacing: 6) {
                if selected { Image(systemName: "checkmark") }
                Text(label)
            }
            .font(.headline)
        }
        #else
        Button(action: action) { Text(label).lineLimit(1).fixedSize() }
            .buttonStyle(SeasonPillStyle(selected: selected))
        #endif
    }

    /// Filters are part of the query: changing one re-asks rather than
    /// filtering what is already on screen, because the merge that
    /// decides what "on the server" means lives in the shared module.
    private var searchKey: String {
        "\(query)|\(kind.rawValue)|\(availability.rawValue)"
    }

    private func run() async {
        guard query.trimmingCharacters(in: .whitespaces).count >= 2 else {
            hits = []
            return
        }
        try? await Task.sleep(nanoseconds: 400_000_000) // debounce
        guard !Task.isCancelled else { return }
        let searcher = UnifiedSearcher(jellyfin: api, seerr: seerr)
        let found = (try? await searcher.search(
            query: query,
            kind: kind.shared,
            availability: availability.shared
        )) ?? []
        // Re-check: a cancelled stale task must not overwrite newer results
        guard !Task.isCancelled else { return }
        hits = found
    }

    /// A film is asked for on the spot; a series goes through the season
    /// picker, which calls back here for "All seasons" so this row's chip
    /// flips too.
    private func request(_ hit: SearchHit) {
        guard let result = hit.jellyseerr else { return }
        Task {
            let outcome = try? await seerr.request(
                tmdbId: result.id,
                isSeries: result.isSeries
            )
            switch outcome {
            case is RequestOutcome.Sent:
                justRequested[Int(result.id)] = .pending
                notice = "Requested \(result.displayTitle)"
            case is RequestOutcome.AlreadyRequested:
                justRequested[Int(result.id)] = .pending
                notice = "\(result.displayTitle) was already requested"
            case is RequestOutcome.NotSignedIn:
                notice = "Sign in to Jellyseerr again in Settings"
            case let failure as RequestOutcome.Failed:
                notice = failure.message
            default:
                notice = "Could not reach Jellyseerr"
            }
        }
    }
}

private extension SearchHit {
    /// Identity for `ForEach`: a hit is either a library item or a TMDb
    /// title, and the bridged object's hash changes with every copy.
    var rowKey: String {
        if let item = jellyfin { return item.id }
        if let tmdbId { return "tmdb-\(tmdbId.intValue)" }
        return title
    }
}

/**
 One result, whichever server it came from.

 The whole row is the target when there is somewhere to go — the item on
 the server, or the season picker — and the watchlist button sits outside
 it, because a control nested inside a NavigationLink is unreachable.
 */
private struct SearchRow: View {
    let api: JellyfinApi
    let seerr: JellyseerrApi
    let hit: SearchHit
    let state: RequestState?
    @Binding var notice: String?
    let onRequest: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            primary
            Spacer(minLength: 8)
            if let state { StateChip(state: state) }
            WatchlistButton(entry: WatchlistEntry.companion.of(hit: hit))
        }
    }

    @ViewBuilder
    private var primary: some View {
        if let item = hit.jellyfin {
            // On the server: opens exactly as it does everywhere else —
            // a series lands on the series screen, anything else on detail
            NavigationLink(value: item) { content }
                // tvOS keeps the system style so the focus ring applies
                #if !os(tvOS)
                .buttonStyle(.plain)
                #endif
        } else if let result = hit.jellyseerr, state?.canRequest == true, hit.isSeries {
            // A show is rarely wanted whole; the picker leads with "All
            // seasons". A Jellyseerr row cannot be a NavigationLink(value:)
            // — itemDestination is registered for BaseItem alone.
            NavigationLink {
                SeasonPickerView(
                    seerr: seerr,
                    tmdbId: result.id,
                    showTitle: result.displayTitle,
                    showYear: result.year,
                    notice: $notice,
                    onRequestAll: onRequest
                )
            } label: {
                content
            }
            #if !os(tvOS)
            .buttonStyle(.plain)
            #endif
        } else if state?.canRequest == true {
            Button(action: onRequest) { content }
                #if !os(tvOS)
                .buttonStyle(.plain)
                #endif
        } else {
            // Nothing to ask for and nowhere to go: a target here would
            // be a lie, so the row is just a row
            content
        }
    }

    private var content: some View {
        HStack(spacing: 14) {
            poster
            VStack(alignment: .leading, spacing: 3) {
                Text(hit.title)
                    .font(.headline)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(hit.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }

    private var poster: some View {
        AsyncImage(url: posterUrl.flatMap { URL(string: $0) }) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            // Never the system's broken-image glyph: a title Jellyseerr
            // knows without artwork still deserves a row
            Rectangle().fill(Color(white: 0.15))
        }
        .frame(width: 48, height: 72)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private var posterUrl: String? {
        if let item = hit.jellyfin {
            return api.imageUrl(item: item, maxWidth: 300)
        }
        return JellyseerrApi.companion.posterUrl(
            posterPath: hit.jellyseerr?.posterPath,
            width: 185
        )
    }
}
