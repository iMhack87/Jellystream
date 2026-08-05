import SwiftUI
import Shared

struct LibrarySection: Identifiable {
    let title: String
    let key: String
    let items: [BaseItem]
    var id: String { key }
}

struct HomeView: View {
    let api: JellyfinApi
    let session: UserSession
    let settings: AppSettings
    let onSettingsChange: (AppSettings) -> Void
    let onLogout: () -> Void
    let onSwitchProfile: () -> Void

    @State private var sections: [LibrarySection]?
    @State private var error: String?
    @State private var playingItem: BaseItem?
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
            .preferredColorScheme(.dark)
            .fullScreenCover(item: $playingItem) { item in
                PlayerScreen(api: api, item: item)
            }
    }

    #if os(tvOS)
    /// tvOS has no toolbar, so the top tab bar is where navigation lives —
    /// the Apple TV+ shape, with the account at the trailing end.
    @ViewBuilder
    private var content: some View {
        TabView(selection: $tab) {
            NavigationStack {
                homeScroll
                    .itemDestination(api: api)
            }
            .tabItem { Label("Home", systemImage: "house") }
            .tag(Tab.home)

            NavigationStack {
                SearchView(api: api)
                    .itemDestination(api: api)
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
                .itemDestination(api: api)
                .navigationDestination(isPresented: $showSearch) {
                    SearchView(api: api)
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
            onChange: onSettingsChange,
            onSwitchProfile: onSwitchProfile,
            onLogout: onLogout
        )
    }

    @ViewBuilder
    private var homeScroll: some View {
        Group {
            if let error {
                Text(error).foregroundStyle(.red).padding()
            } else if let sections {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 36) {
                        // Hero must be openable: first playable item or series
                        if let hero = sections.flatMap(\.items)
                            .first(where: { $0.isPlayable || $0.isSeries }) {
                            HeroSection(api: api, item: hero) {
                                playingItem = hero
                            }
                        }
                        ForEach(sections) { section in
                            if section.key == "resume" || section.key == "nextup" {
                                ContinueRow(api: api, section: section)
                            } else {
                                LibraryRow(api: api, section: section)
                            }
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
        .task { await load() }
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
            let views = try await api.getUserViews()
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
    /// separate tabs, so separate stacks.
    func itemDestination(api: JellyfinApi) -> some View {
        navigationDestination(for: BaseItem.self) { item in
            if item.isSeries {
                SeriesView(api: api, series: item)
            } else {
                DetailView(api: api, item: item)
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
