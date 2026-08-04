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
    let onLogout: () -> Void

    @State private var sections: [LibrarySection]?
    @State private var error: String?
    @State private var showSearch = false
    @State private var playingItem: BaseItem?

    var body: some View {
        NavigationStack {
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
            .navigationDestination(for: BaseItem.self) { item in
                if item.isSeries {
                    SeriesView(api: api, series: item)
                } else {
                    DetailView(api: api, item: item)
                }
            }
            .navigationDestination(isPresented: $showSearch) {
                SearchView(api: api)
            }
            #if !os(tvOS)
            .toolbar {
                Button {
                    showSearch = true
                } label: {
                    Image(systemName: "magnifyingglass")
                }
                Button {
                    onLogout()
                } label: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
            }
            .toolbarColorScheme(.dark, for: .navigationBar)
            #endif
            .task { await load() }
            .fullScreenCover(item: $playingItem) { item in
                PlayerScreen(api: api, item: item)
            }
        }
        .preferredColorScheme(.dark)
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

/** Platform metrics: TV needs generous margins and focus breathing room. */
enum HomeMetrics {
    #if os(tvOS)
    static let edgePadding: CGFloat = 64
    static let rowVerticalPadding: CGFloat = 30
    #else
    static let edgePadding: CGFloat = 24
    static let rowVerticalPadding: CGFloat = 12
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
                LazyHStack(alignment: .top, spacing: 16) {
                    ForEach(section.items, id: \.id) { item in
                        NavigationLink(value: item) {
                            VStack(alignment: .leading, spacing: 6) {
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

                                Text(item.name ?? "")
                                    .font(.callout)
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                if let label = item.episodeLabel {
                                    Text("\(item.seriesName ?? "") \(label)".trimmingCharacters(in: .whitespaces))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .frame(width: cardWidth)
                        }
                        .disabled(!item.isPlayable && !item.isSeries)
                        #if os(tvOS)
                        .buttonStyle(.card)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.vertical, HomeMetrics.rowVerticalPadding)
            }
        }
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
                LazyHStack(alignment: .top, spacing: 16) {
                    ForEach(section.items, id: \.id) { item in
                        NavigationLink(value: item) {
                            PosterCard(api: api, item: item)
                        }
                        .disabled(!item.isPlayable && !item.isSeries)
                        #if os(tvOS)
                        .buttonStyle(.card)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.vertical, HomeMetrics.rowVerticalPadding)
            }
        }
    }
}

private struct PosterCard: View {
    let api: JellyfinApi
    let item: BaseItem

    #if os(tvOS)
    private let posterWidth: CGFloat = 250
    private let posterHeight: CGFloat = 375
    #else
    private let posterWidth: CGFloat = 132
    private let posterHeight: CGFloat = 198
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            AsyncImage(url: api.imageUrl(item: item, maxWidth: 800).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: posterWidth, height: posterHeight)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 10))

            Text(item.name ?? "")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .frame(width: posterWidth, alignment: .leading)
        }
    }
}
