import SwiftUI
import Shared

struct LibrarySection: Identifiable {
    let title: String
    let key: String
    let items: [BaseItem]
    var id: String { key }

    /// The two rows that are drawn as landscape cards with a progress
    /// bar, and the two the new rows have to come after.
    var isContinue: Bool { key == "resume" || key == "nextup" }
}

struct HomeView: View {
    let api: JellyfinApi
    let session: UserSession
    let settings: AppSettings
    let seerr: JellyseerrApi
    let downloader: Downloader?
    /// Per profile like the downloader, and handed down the same way so a
    /// detail screen two pushes deeper writes the list the home row reads.
    let watchlist: WatchlistStore?
    let profile: PersistedSession?
    let onSettingsChange: (AppSettings) -> Void
    let onProfileChange: (PersistedSession) -> Void
    let onLogout: () -> Void
    let onSwitchProfile: () -> Void

    @State private var sections: [LibrarySection]?
    @State private var error: String?
    @State private var playingItem: BaseItem?
    @State private var downloadingAllowed: Bool?
    @State private var offlineItem: DownloadedItem?
    @State private var showDownloads = false

    private var playableDownloads: Int {
        Int(downloader?.downloads.playable.count ?? 0)
    }
    #if os(tvOS)
    private enum Tab: Hashable { case home, search, profile }
    @State private var tab: Tab = .home
    #else
    @State private var showSearch = false
    @State private var showSettings = false
    #endif

    var body: some View {
        content
            // The player reads this at makeUIView time, before any
            // .onAppear would have run — hence an environment value
            .environment(\.appSettings, settings)
            .environment(\.downloader, downloader)
            .environment(\.watchlist, watchlist)
            .environment(\.downloadingAllowed, downloadingAllowed)
            .task { downloadingAllowed = try? await api.canDownload()?.boolValue }
            .preferredColorScheme(.dark)
            .fullScreenCover(item: $playingItem) { item in
                PlayerScreen(api: api, item: item, settings: settings, seerr: seerr)
            }
            #if !os(tvOS)
            .sheet(isPresented: $showDownloads) {
                if let downloader, let profile {
                    NavigationStack {
                        DownloadsView(
                            downloader: downloader,
                            profileKey: profile.profileKey,
                            onPlay: { item in
                                showDownloads = false
                                offlineItem = item
                            }
                        )
                        .toolbar { Button("Done") { showDownloads = false } }
                    }
                    .preferredColorScheme(.dark)
                }
            }
            .fullScreenCover(item: $offlineItem) { offline in
                if let downloader, let profile {
                    PlayerScreen(
                        api: api,
                        item: BaseItem(
                            id: offline.itemId, name: offline.name, type: "Movie",
                            collectionType: nil, productionYear: nil, imageTags: nil,
                            seriesName: nil, seriesId: nil, userData: nil, overview: nil,
                            runTimeTicks: offline.runTimeTicks, genres: nil,
                            communityRating: nil, criticRating: nil, officialRating: nil,
                            indexNumber: nil, parentIndexNumber: nil,
                            backdropImageTags: nil, parentBackdropItemId: nil,
                            parentBackdropImageTags: nil, premiereDate: nil,
                            providerIds: nil
                        ),
                        settings: settings,
                        // Offline: this synthetic BaseItem is a "Movie" with
                        // no series identity, so the advisor would answer nil
                        // anyway — no reason to hand it a Jellyseerr it
                        // cannot reach on a train
                        seerr: nil,
                        localFile: downloadedFileURL(
                            profileKey: profile.profileKey, item: offline
                        ),
                        onOfflinePosition: { ticks in
                            downloader.recordPosition(itemId: offline.itemId, ticks: ticks)
                        }
                    )
                }
            }
            #endif
    }

    #if os(tvOS)
    /// tvOS has no toolbar, so the top tab bar is where navigation lives —
    /// the Apple TV+ shape, with the account at the trailing end.
    @ViewBuilder
    private var content: some View {
        TabView(selection: $tab) {
            NavigationStack {
                homeScroll
                    .itemDestination(api: api, seerr: seerr)
            }
            .tabItem { Label("Home", systemImage: "house") }
            .tag(Tab.home)

            NavigationStack {
                SearchView(api: api, seerr: seerr)
                    .itemDestination(api: api, seerr: seerr)
            }
            .tabItem { Label("Search", systemImage: "magnifyingglass") }
            .tag(Tab.search)

            NavigationStack {
                settingsScreen
            }
            .tabItem { Label(session.displayName, systemImage: "person.crop.circle") }
            .tag(Tab.profile)
        }
    }
    #else
    @ViewBuilder
    private var content: some View {
        NavigationStack {
            homeScroll
                .itemDestination(api: api, seerr: seerr)
                .navigationDestination(isPresented: $showSearch) {
                    SearchView(api: api, seerr: seerr)
                }
                .toolbar {
                    Button {
                        showSearch = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    // The account button owns switching profile and signing
                    // out — both live one tap deeper, in Settings
                    Button {
                        showSettings = true
                    } label: {
                        AvatarCircle(initial: session.initial, size: 28)
                    }
                }
                .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                settingsScreen
                    .toolbar {
                        Button("Done") { showSettings = false }
                    }
            }
            .preferredColorScheme(.dark)
        }
    }
    #endif

    private var settingsScreen: some View {
        SettingsView(
            api: api,
            session: session,
            settings: settings,
            seerr: seerr,
            downloader: downloader,
            profile: profile,
            onChange: onSettingsChange,
            onProfileChange: onProfileChange,
            onPlayOffline: { offlineItem = $0 },
            onSwitchProfile: onSwitchProfile,
            onLogout: onLogout
        )
    }

    @ViewBuilder
    private var homeScroll: some View {
        Group {
            if let error {
                // Downloads exist precisely for this moment; a raw
                // NSURLError dump and no way to them is the worst possible
                // screen to meet on a train
                VStack(spacing: 14) {
                    Text("Can't reach the server.").font(.headline)
                    if playableDownloads > 0 {
                        Text(
                            "\(playableDownloads) downloaded "
                            + (playableDownloads == 1 ? "title is" : "titles are")
                            + " still on this device."
                        )
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        Button("Go to downloads") { showDownloads = true }
                            .buttonStyle(.borderedProminent)
                    } else {
                        Text(error).font(.caption).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(32)
            } else if let sections {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: HomeMetrics.rowSpacing) {
                        // Hero must be openable: first playable item or series
                        if let hero = sections.flatMap(\.items)
                            .first(where: { $0.isPlayable || $0.isSeries }) {
                            HeroSection(api: api, item: hero) {
                                playingItem = hero
                            }
                        }
                        // Split rather than one ForEach with a key
                        // dispatch: the three rows below are not
                        // LibrarySections (one of them is not even
                        // Jellyfin's) and they belong between the
                        // continue rows and the libraries.
                        ForEach(sections.filter(\.isContinue)) { section in
                            ContinueRow(api: api, section: section)
                        }

                        // Each of these loads on its own, OUTSIDE load():
                        // that function assigns its sections only at the
                        // very end, so folding a Jellyseerr call into it
                        // would make the hero itself wait on the request
                        // server — and block on its timeout when the NAS
                        // is off.
                        RequestedRow(seerr: seerr)

                        if let watchlist {
                            WatchlistRow(
                                api: api,
                                store: watchlist,
                                onServer: sections.flatMap(\.items)
                            )
                        }

                        FavouritesRow(api: api)
                        // Each of the three rows above collapses to zero
                        // height when empty, but the stack's spacing does
                        // not: three hidden rows still left 108 points of
                        // nothing between Continue Watching and the
                        // libraries. They cancel their own gap instead —
                        // see `hiddenRowSpacing`. Lifting the emptiness
                        // check up here would be tidier and is exactly the
                        // trap in CLAUDE.md: the row would resolve to
                        // EmptyView, its `.task` would never run, and it
                        // could never discover it had something to show.

                        ForEach(sections.filter { !$0.isContinue }) { section in
                            LibraryRow(api: api, section: section)
                        }
                    }
                    .padding(.bottom, 40)
                }
                .ignoresSafeArea(edges: .top)
            } else {
                ProgressView()
            }
        }
        .background(Color.black)
        // Keyed on the library choices: turning one on in Settings must
        // show its row on the way back, not on the next cold start
        .task(id: libraryChoiceKey) { await load() }
    }

    /// A stable, hashable stamp of the per-library switches. The bridged
    /// dictionary itself carries `KotlinBoolean` values, which is more
    /// than `task(id:)` should have to reason about.
    private var libraryChoiceKey: String {
        settings.libraryOverrides
            .map { "\($0.key)=\($0.value)" }
            .sorted()
            .joined(separator: ",")
    }

    private func load() async {
        do {
            var result: [LibrarySection] = []
            if let resume = try? await api.getResumeItems(limit: 12), !resume.isEmpty {
                result.append(LibrarySection(title: "Continue Watching", key: "resume", items: resume))
            }
            if let nextUp = try? await api.getNextUp(limit: 12), !nextUp.isEmpty {
                result.append(LibrarySection(title: "Next Up", key: "nextup", items: nextUp))
            }
            let views = settings.visibleLibraries(views: try await api.getUserViews())
            for view in views {
                // One failing view must not blank the whole home screen
                let latest = (try? await api.getLatestItems(viewId: view.id, limit: 12)) ?? []
                result.append(LibrarySection(title: view.name ?? "Library", key: view.id, items: latest))
            }
            sections = result
        } catch {
            self.error = error.localizedDescription
        }
    }
}

extension BaseItem: @retroactive Identifiable {}

extension View {
    /// Where a `NavigationLink(value: BaseItem)` lands. Every navigation
    /// stack that shows items declares it — on tvOS home and search are
    /// separate tabs, so separate stacks. `seerr` rides along because both
    /// destinations open the player, which offers the next season at the
    /// end of an episode.
    func itemDestination(api: JellyfinApi, seerr: JellyseerrApi) -> some View {
        navigationDestination(for: BaseItem.self) { item in
            if item.isSeries {
                SeriesView(api: api, seerr: seerr, series: item)
            } else {
                DetailView(api: api, seerr: seerr, item: item)
            }
        }
    }
}

/** Platform metrics: TV needs generous margins and focus breathing room. */
enum HomeMetrics {
    #if os(tvOS)
    static let edgePadding: CGFloat = 64
    static let rowVerticalPadding: CGFloat = 30
    /** ATV+ shelves leave ~40pt between lockups so the focus lift breathes. */
    static let cardSpacing: CGFloat = 40
    #else
    static let edgePadding: CGFloat = 24
    static let rowVerticalPadding: CGFloat = 12
    static let cardSpacing: CGFloat = 16
    #endif

    /** Gap between shelves on the home screen. */
    static let rowSpacing: CGFloat = 36

    /**
     A row that has nothing to show must not leave a hole.

     It can collapse to zero height on its own, but the stack's spacing is
     applied between children whatever their size — three hidden rows left
     108 points of nothing in the middle of the home screen. Cancelling
     the gap here is the local fix; the tidy one (not building the row at
     all) is the trap CLAUDE.md documents, because the row would resolve
     to EmptyView and its `.task` would never run to find out whether it
     had anything.
     */
    static let hiddenRowSpacing: CGFloat = -rowSpacing
}

/** Full-bleed backdrop melting into black — the ATV+ hero. */
private struct HeroSection: View {
    let api: JellyfinApi
    let item: BaseItem
    let onPlay: () -> Void

    #if os(tvOS)
    private let heroHeight: CGFloat = 620
    #else
    private let heroHeight: CGFloat = 440
    #endif

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: api.backdropUrl(item: item, maxWidth: 1920).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.1))
            }
            .frame(maxWidth: .infinity)
            .frame(height: heroHeight)
            .clipped()

            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0.0),
                    .init(color: .clear, location: 0.5),
                    .init(color: .black, location: 1.0),
                ],
                startPoint: .top,
                endPoint: .bottom
            )

            VStack(alignment: .leading, spacing: 10) {
                Text(item.name ?? "")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.white)
                    .lineLimit(2)

                if !metaLine.isEmpty {
                    Text(metaLine)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                #if os(tvOS)
                let ctaSpacing: CGFloat = 24
                #else
                let ctaSpacing: CGFloat = 14
                #endif
                // On tvOS the system button style draws the pill itself
                // (visible even unfocused, focus lift accounted for in
                // layout) — a .plain button would be bare text over the
                // backdrop and its focus effect would overlap the meta line
                HStack(spacing: ctaSpacing) {
                    if item.isPlayable {
                        Button(action: onPlay) {
                            Label(
                                item.resumePositionSeconds > 60 ? "Resume" : "Play",
                                systemImage: "play.fill"
                            )
                            .font(.headline)
                            #if !os(tvOS)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 10)
                            .background(.white, in: RoundedRectangle(cornerRadius: 10))
                            .foregroundStyle(.black)
                            #endif
                        }
                        #if !os(tvOS)
                        .buttonStyle(.plain)
                        #endif
                    }

                    NavigationLink(value: item) {
                        Text("Details")
                            .font(.headline)
                            #if !os(tvOS)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            #endif
                    }
                    #if !os(tvOS)
                    .buttonStyle(.plain)
                    #endif
                }
                #if os(tvOS)
                .padding(.top, 12)
                #endif
            }
            .padding(HomeMetrics.edgePadding)
        }
        .frame(height: heroHeight)
    }

    private var metaLine: String {
        var parts: [String] = []
        if let label = item.episodeLabel {
            parts.append("\(item.seriesName ?? "") \(label)".trimmingCharacters(in: .whitespaces))
        }
        if let year = item.productionYear { parts.append("\(year)") }
        if let minutes = item.runtimeMinutes { parts.append("\(minutes) min") }
        return parts.joined(separator: "  ·  ")
    }
}

/** Landscape cards with a progress bar — Continue Watching / Next Up. */
private struct ContinueRow: View {
    let api: JellyfinApi
    let section: LibrarySection

    #if os(tvOS)
    private let cardWidth: CGFloat = 440
    private let cardHeight: CGFloat = 248
    #else
    private let cardWidth: CGFloat = 250
    private let cardHeight: CGFloat = 141
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(section.title)
                .font(.title2.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, HomeMetrics.edgePadding)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: HomeMetrics.cardSpacing) {
                    ForEach(section.items, id: \.id) { item in
                        // ATV+ lockup recipe (Apple's media-catalog sample):
                        // borderless button, artwork and titles as separate
                        // siblings — the system lifts the artwork on focus
                        // and moves the titles out of the way itself
                        NavigationLink(value: item) {
                            #if os(tvOS)
                            artwork(for: item)
                                .hoverEffect(.highlight)
                            Text(item.name ?? "")
                                .font(.callout)
                                .lineLimit(1)
                            if let sub = episodeSubtitle(for: item) {
                                Text(sub)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            #else
                            VStack(alignment: .leading, spacing: 6) {
                                artwork(for: item)

                                Text(item.name ?? "")
                                    .font(.callout)
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                if let sub = episodeSubtitle(for: item) {
                                    Text(sub)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .frame(width: cardWidth)
                            #endif
                        }
                        .disabled(!item.isPlayable && !item.isSeries)
                        #if os(tvOS)
                        .buttonStyle(.borderless)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.vertical, HomeMetrics.rowVerticalPadding)
            }
            // Lets the focus lift scale outside the scroll bounds unclipped
            #if os(tvOS)
            .scrollClipDisabled()
            #endif
        }
    }

    private func episodeSubtitle(for item: BaseItem) -> String? {
        item.episodeLabel.map {
            "\(item.seriesName ?? "") \($0)".trimmingCharacters(in: .whitespaces)
        }
    }

    private func artwork(for item: BaseItem) -> some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: api.backdropUrl(item: item, maxWidth: 600).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: cardWidth, height: cardHeight)
            .clipped()

            if let fraction = item.playedFraction {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(.white.opacity(0.35))
                        Rectangle()
                            .fill(.white)
                            .frame(width: geo.size.width * CGFloat(truncating: fraction))
                    }
                }
                .frame(height: 4)
            }
        }
        .frame(width: cardWidth, height: cardHeight)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

private struct LibraryRow: View {
    let api: JellyfinApi
    let section: LibrarySection

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(section.title)
                .font(.title2.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, HomeMetrics.edgePadding)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: HomeMetrics.cardSpacing) {
                    ForEach(section.items, id: \.id) { item in
                        NavigationLink(value: item) {
                            // Apple TV store card: the caption lives inside
                            // the artwork on a bottom scrim — no sibling
                            // text (Continue/Next Up rows keep theirs)
                            PosterOverlayCard(api: api, item: item)
                                #if os(tvOS)
                                .hoverEffect(.highlight)
                                #endif
                        }
                        .disabled(!item.isPlayable && !item.isSeries)
                        #if os(tvOS)
                        .buttonStyle(.borderless)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.vertical, HomeMetrics.rowVerticalPadding)
            }
            // Lets the focus lift scale outside the scroll bounds unclipped
            #if os(tvOS)
            .scrollClipDisabled()
            #endif
        }
    }
}

/**
 * The shelf scaffolding the three new rows share: title, horizontal
 * strip, the same paddings as LibraryRow. Extracted because none of them
 * is a LibrarySection and copying the layout three times would let them
 * drift apart.
 */
private struct Shelf<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title2.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, HomeMetrics.edgePadding)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: HomeMetrics.cardSpacing) {
                    content()
                }
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.vertical, HomeMetrics.rowVerticalPadding)
            }
            // Lets the focus lift scale outside the scroll bounds unclipped
            #if os(tvOS)
            .scrollClipDisabled()
            #endif
        }
    }
}

/**
 * What has been asked for and is still moving.
 *
 * Its own state and its own task: this is the Jellyseerr call, and the
 * home screen's `load()` publishes its sections only at the end, so
 * asking for it there would make the hero wait on the request server —
 * and hang for its whole timeout when the NAS is off.
 */
private struct RequestedRow: View {
    let seerr: JellyseerrApi

    /// The app-wide arrival poll's own answer. Reading it rather than
    /// fetching again is what stops the notice announcing a title above a
    /// row that still says it is downloading — and it keeps the progress
    /// bars moving without a second poll.
    @ObservedObject private var feed = RequestFeed.shared
    @State private var rows: [RequestedTitle] = []

    var body: some View {
        // A real container, not a Group: a Group whose condition is false
        // resolves to EmptyView, which is not in the view tree at all —
        // and a `.task` on nothing never runs, so the row would stay
        // empty for ever because it never got to ask.
        VStack(alignment: .leading, spacing: 0) {
            if !rows.isEmpty {
                Shelf(title: "Requested & on the way") {
                    ForEach(rows, id: \.request.id) { row in
                        RequestedCard(row: row)
                    }
                }
            }
        }
        .padding(.bottom, rows.isEmpty ? HomeMetrics.hiddenRowSpacing : 0)
        .task { await load() }
        .onChange(of: feed.requests) { _, published in
            // Only what can still change: an available request is in the
            // library by now and already has a row of its own
            rows = published.filter { $0.isSettling }
        }
    }

    private func load() async {
        guard seerr.isConfigured else { return }
        // The poll runs every minute and home is usually the first thing
        // seen, so take whatever it already has rather than show an empty
        // row for up to a minute.
        if !feed.requests.isEmpty {
            rows = feed.requests.filter { $0.isSettling }
            return
        }
        // nil means unreachable, which is not the same as "nothing
        // requested" — leaving the row out beats inventing an answer
        guard let all = try? await seerr.myRequestsDetailed(limit: 30) else { return }
        rows = all.filter { $0.isSettling }
    }
}

private struct RequestedCard: View {
    let row: RequestedTitle

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            AsyncImage(
                url: JellyseerrApi.companion.posterUrl(posterPath: row.posterPath, width: 342)
                    .flatMap { URL(string: $0) }
            ) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: PosterOverlayCard.width, height: PosterOverlayCard.height)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(.white.opacity(0.12), lineWidth: 1)
            )

            Text(row.displayTitle)
                .font(.callout)
                .foregroundStyle(.white)
                .lineLimit(1)
            Text(row.state.label)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            // Only while something is moving: a bar on an approved
            // request nobody has started fetching says nothing
            if let progress = row.progress {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.35))
                        Capsule()
                            .fill(progress.isStalled ? .white.opacity(0.45) : .white)
                            .frame(width: geo.size.width * CGFloat(min(max(progress.fraction, 0), 1)))
                    }
                }
                .frame(height: 4)
                Text(progress.summary)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: PosterOverlayCard.width, alignment: .leading)
        // Focusable but not a button: there is nowhere to go from a
        // request, and a shelf with nothing focusable in it cannot be
        // scrolled with a remote
        #if os(tvOS)
        .focusable()
        #endif
    }
}

/**
 * What the profile means to watch — including what the server does not
 * have yet, which is the whole reason this is not Jellyfin's favourite
 * flag.
 */
private struct WatchlistRow: View {
    let api: JellyfinApi
    /// Observed, not just held: adding a title from a detail screen two
    /// pushes deeper has to move this row on the way back.
    @ObservedObject var store: WatchlistStore
    /// Whatever home already loaded, for the reconcile pass.
    let onServer: [BaseItem]

    @State private var cards: [WatchlistCard] = []

    var body: some View {
        // Never a Group: see RequestedRow — an EmptyView carries no task
        VStack(alignment: .leading, spacing: 0) {
            if !cards.isEmpty {
                Shelf(title: "Watchlist") {
                    ForEach(cards) { card in
                        WatchlistCardView(api: api, card: card)
                    }
                }
            }
        }
        .padding(.bottom, cards.isEmpty ? HomeMetrics.hiddenRowSpacing : 0)
        .task(id: store.stamp) { await load() }
    }

    private func load() async {
        // An entry added from search knows only a TMDb id, and nothing
        // here can open one. This is where it picks up an item id, the
        // first time the title turns up on the server.
        store.reconcile(onServer: onServer)

        let known = Dictionary(
            onServer.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var resolved: [WatchlistCard] = []
        // Capped: the row is a shelf, and each miss is a round trip
        for entry in store.watchlist.entries.prefix(20) {
            guard let itemId = entry.itemId else {
                resolved.append(WatchlistCard(entry: entry, item: nil))
                continue
            }
            if let item = known[itemId] {
                resolved.append(WatchlistCard(entry: entry, item: item))
            } else {
                resolved.append(
                    WatchlistCard(entry: entry, item: try? await api.getItem(itemId: itemId))
                )
            }
            guard !Task.isCancelled else { return }
        }
        cards = resolved
    }
}

private struct WatchlistCard: Identifiable {
    let entry: WatchlistEntry
    /// Nil while the title is still only a TMDb id: shown, not playable.
    let item: BaseItem?
    var id: String { entry.rowKey }
}

private struct WatchlistCardView: View {
    let api: JellyfinApi
    let card: WatchlistCard

    var body: some View {
        if let item = card.item {
            NavigationLink(value: item) {
                PosterOverlayCard(api: api, item: item)
                    #if os(tvOS)
                    .hoverEffect(.highlight)
                    #endif
            }
            #if os(tvOS)
            .buttonStyle(.borderless)
            #else
            .buttonStyle(.plain)
            #endif
        } else {
            pending
        }
    }

    /// Not on the server yet. It is still on the list — that is what a
    /// watchlist is for — but there is nothing to open, so it is not a
    /// target either.
    private var pending: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(
                url: JellyseerrApi.companion.posterUrl(posterPath: card.entry.posterPath, width: 342)
                    .flatMap { URL(string: $0) }
            ) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: PosterOverlayCard.width, height: PosterOverlayCard.height)
            .clipped()

            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.75), location: 0.0),
                    .init(color: .black.opacity(0.25), location: 0.55),
                    .init(color: .clear, location: 1.0),
                ],
                startPoint: .bottom,
                endPoint: .top
            )
            .frame(height: PosterOverlayCard.height * 0.3)
            .frame(maxHeight: .infinity, alignment: .bottom)

            Text(card.entry.title)
                .font(.caption.bold())
                .foregroundStyle(.white.opacity(0.95))
                .lineLimit(1)
                #if os(tvOS)
                .padding(14)
                #else
                .padding(8)
                #endif
        }
        .frame(width: PosterOverlayCard.width, height: PosterOverlayCard.height)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(.white.opacity(0.12), lineWidth: 1)
        )
        // Same reason as the requested cards: focusable so the shelf can
        // be panned, never a button, because there is nothing to open
        #if os(tvOS)
        .focusable()
        #endif
    }
}

/** Jellyfin's own favourite flag, shared with every other client. */
private struct FavouritesRow: View {
    let api: JellyfinApi

    @State private var items: [BaseItem] = []

    var body: some View {
        // Never a Group: see RequestedRow — an EmptyView carries no task
        VStack(alignment: .leading, spacing: 0) {
            if !items.isEmpty {
                LibraryRow(
                    api: api,
                    section: LibrarySection(
                        title: "Favourites",
                        key: "favourites",
                        items: items
                    )
                )
            }
        }
        // Kotlin default arguments do not bridge — the limit is spelled out
        .padding(.bottom, items.isEmpty ? HomeMetrics.hiddenRowSpacing : 0)
        .task { items = (try? await api.getFavorites(limit: 24)) ?? [] }
    }
}

/**
 * Apple TV store-style poster: caption inside the card over a bottom
 * scrim, hairline border so dark posters keep an edge on black.
 */
private struct PosterOverlayCard: View {
    let api: JellyfinApi
    let item: BaseItem

    #if os(tvOS)
    static let width: CGFloat = 250
    static let height: CGFloat = 375
    #else
    static let width: CGFloat = 132
    static let height: CGFloat = 198
    #endif

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: api.imageUrl(item: item, maxWidth: 800).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: Self.width, height: Self.height)
            .clipped()

            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.75), location: 0.0),
                    .init(color: .black.opacity(0.25), location: 0.55),
                    .init(color: .clear, location: 1.0),
                ],
                startPoint: .bottom,
                endPoint: .top
            )
            .frame(height: Self.height * 0.3)
            .frame(maxHeight: .infinity, alignment: .bottom)

            Text(item.name ?? "")
                .font(.caption.bold())
                .foregroundStyle(.white.opacity(0.95))
                .lineLimit(1)
                #if os(tvOS)
                .padding(14)
                #else
                .padding(8)
                #endif
        }
        .frame(width: Self.width, height: Self.height)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(.white.opacity(0.12), lineWidth: 1)
        )
    }
}
