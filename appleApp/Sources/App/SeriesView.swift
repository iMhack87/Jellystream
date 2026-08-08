import SwiftUI
import Shared

/// Apple TV-style series page: big title over the series backdrop, season
/// pills, and episodes as a horizontal shelf of landscape cards (runtime
/// badge on the still; episode number, title, synopsis and air date below).
struct SeriesView: View {
    let api: JellyfinApi
    /// Only ever handed on to the player, for the end-of-episode offer —
    /// this is the screen its episodes are launched from.
    let seerr: JellyseerrApi
    /// State, not a `let`: the heart flips this copy before the server
    /// has agreed, the same way the detail screen does.
    @State private var series: BaseItem

    @State private var seasons: [BaseItem] = []
    @State private var selectedSeason: BaseItem?
    @State private var episodes: [BaseItem] = []
    @State private var error: String?
    @State private var playingItem: BaseItem?
    /// A refused flag says so in place, never as an alert.
    @State private var notice: String?
    @Environment(\.appSettings) private var appSettings

    init(api: JellyfinApi, seerr: JellyseerrApi, series: BaseItem) {
        self.api = api
        self.seerr = seerr
        _series = State(initialValue: series)
    }

    #if os(tvOS)
    private let headerHeight: CGFloat = 360
    #else
    private let headerHeight: CGFloat = 260
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header

                seriesActions

                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                        .padding(.horizontal, HomeMetrics.edgePadding)
                }

                if !seasons.isEmpty {
                    seasonPills
                }

                episodeShelf
            }
            .padding(.bottom, 40)
        }
        .background(Color.black)
        .ignoresSafeArea(edges: .top)
        .preferredColorScheme(.dark)
        #if !os(tvOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            // Refetch the show itself, as the Android twin does. The item
            // this screen was handed came off a shelf, and a list DTO
            // carries neither ProviderIds nor a current favourite flag:
            // without this the heart starts on the wrong side, and a
            // watchlist entry made here has no TMDb id to recognise the
            // same show by later.
            if let full = try? await api.getItem(itemId: series.id) {
                series = full
            }
            do {
                seasons = try await api.getSeasons(seriesId: series.id)
                selectedSeason = seasons.first
            } catch {
                self.error = error.localizedDescription
            }
        }
        .task(id: selectedSeason?.id) {
            guard let season = selectedSeason else { return }
            episodes = (try? await api.getEpisodes(seriesId: series.id, seasonId: season.id)) ?? []
        }
        .fullScreenCover(item: $playingItem) { playing in
            PlayerScreen(api: api, item: playing, settings: appSettings, seerr: seerr)
        }
    }

    /// Series backdrop melting into black, big centered title — the ATV
    /// series header (we have no logo art, the styled name stands in).
    private var header: some View {
        ZStack(alignment: .bottom) {
            AsyncImage(url: api.backdropUrl(item: series, maxWidth: 1920).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.1))
            }
            .frame(maxWidth: .infinity)
            .frame(height: headerHeight)
            .clipped()

            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.35), location: 0.0),
                    .init(color: .clear, location: 0.35),
                    .init(color: .black, location: 1.0),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: headerHeight)

            VStack(spacing: 8) {
                Text(series.name ?? "")
                    .font(.system(size: 46, weight: .heavy))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)

                RatingsRow(ratings: series.ratings)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, HomeMetrics.edgePadding)
            .padding(.bottom, 16)
        }
        .frame(height: headerHeight)
    }

    /// Below the header rather than inside it: buttons over the backdrop
    /// would sit between the title and the season pills in the tvOS focus
    /// order, and the header is centred while everything else is not.
    private var seriesActions: some View {
        HStack(spacing: 20) {
            Button {
                toggleFavorite()
            } label: {
                Image(systemName: series.isFavorite ? "heart.fill" : "heart")
                    .font(.title3)
            }
            #if !os(tvOS)
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            #endif
            .accessibilityLabel(series.isFavorite ? "Remove favourite" : "Add favourite")

            WatchlistButton(entry: WatchlistEntry.companion.of(item: series))

            if let notice {
                Text(notice)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, HomeMetrics.edgePadding)
    }

    /// Optimistic, then put back if the server says no — the detail
    /// screen's rule, and the Android twin's.
    private func toggleFavorite() {
        let wanted = !series.isFavorite
        let before = series
        series = series.withFavorite(favorite: wanted)
        notice = nil
        Task {
            // The bridge boxes a Kotlin Boolean; false is also what a thrown
            // error means here — either way the server did not take it
            let ok = (try? await api.setFavorite(itemId: before.id, favorite: wanted))?.boolValue ?? false
            if !ok {
                series = before
                notice = "Couldn't reach the server"
            }
        }
    }

    /// The long press on an episode card. A short press still plays.
    private func toggleWatched(_ episode: BaseItem) {
        let wanted = !episode.isWatched
        let before = episode
        replace(before.withWatched(watched: wanted))
        notice = nil
        Task {
            let ok = (try? await api.setWatched(itemId: before.id, watched: wanted))?.boolValue ?? false
            if !ok {
                replace(before)
                notice = "Couldn't reach the server"
            }
        }
    }

    private func replace(_ updated: BaseItem) {
        guard let index = episodes.firstIndex(where: { $0.id == updated.id }) else { return }
        episodes[index] = updated
    }

    private var seasonPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(seasons, id: \.id) { season in
                    #if os(tvOS)
                    // Untouched system buttons so tvOS 26's Liquid Glass
                    // (and the Focus Engine's color management) stay
                    // intact — any custom background or label color
                    // fights them. The shelf below already says which
                    // season is open; no extra selection marker needed.
                    Button {
                        selectedSeason = season
                    } label: {
                        Text(season.name ?? "Season")
                            .font(.headline)
                    }
                    #else
                    Button {
                        selectedSeason = season
                    } label: {
                        Text(season.name ?? "Season")
                    }
                    .buttonStyle(
                        SeasonPillStyle(selected: season.id == selectedSeason?.id)
                    )
                    #endif
                }
            }
            .padding(.horizontal, HomeMetrics.edgePadding)
            .padding(.vertical, 4)
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
    }

    private var episodeShelf: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: HomeMetrics.cardSpacing) {
                ForEach(episodes, id: \.id) { episode in
                    Button {
                        playingItem = episode
                    } label: {
                        #if os(tvOS)
                        EpisodeStill(api: api, episode: episode)
                            .hoverEffect(.highlight)
                        EpisodeMeta(episode: episode)
                        #else
                        VStack(alignment: .leading, spacing: 0) {
                            EpisodeStill(api: api, episode: episode)
                            EpisodeMeta(episode: episode)
                        }
                        .frame(width: EpisodeStill.width)
                        #endif
                    }
                    #if os(tvOS)
                    .buttonStyle(.borderless)
                    #else
                    .buttonStyle(.plain)
                    #endif
                    // Long press, which is what .contextMenu is on a
                    // touch screen AND on the Siri Remote — the Android
                    // twin uses combinedClickable(onLongClick=) for it
                    .contextMenu {
                        Button(episode.isWatched ? "Mark as unwatched" : "Mark as watched") {
                            toggleWatched(episode)
                        }
                    }
                }
            }
            .padding(.horizontal, HomeMetrics.edgePadding)
            .padding(.vertical, HomeMetrics.rowVerticalPadding)
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
    }
}

/**
 * Touch-platform season pill: selected = solid white capsule, black text.
 * tvOS uses untouched system buttons instead (Liquid Glass + Focus
 * Engine own the visuals there; selection is a checkmark in the label).
 *
 * Not private: the unified search screen's two filter rows are the same
 * control in the same place, and two pill styles would drift apart.
 */
struct SeasonPillStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.horizontal, 18)
            .padding(.vertical, 9)
            .background(
                selected
                    ? AnyShapeStyle(.white)
                    : AnyShapeStyle(.white.opacity(0.15)),
                in: Capsule()
            )
            .foregroundStyle(selected ? .black : .white)
            .opacity(configuration.isPressed ? 0.8 : 1.0)
    }
}

/** Landscape episode still with a runtime badge and watched progress. */
private struct EpisodeStill: View {
    let api: JellyfinApi
    let episode: BaseItem

    #if os(tvOS)
    static let width: CGFloat = 420
    static let height: CGFloat = 236
    #else
    static let width: CGFloat = 290
    static let height: CGFloat = 163
    #endif

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: api.imageUrl(item: episode, maxWidth: 800).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
                    // Anti-spoiler: the still stays blurred until the
                    // episode has been started or watched
                    .blur(radius: episode.shouldBlurPreview ? 14 : 0)
            } placeholder: {
                Rectangle().fill(Color(white: 0.12))
            }
            .frame(width: Self.width, height: Self.height)
            .clipped()

            if let fraction = episode.playedFraction {
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

            if let minutes = episode.runtimeMinutes {
                Text("\(minutes) min")
                    .font(.caption2.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(10)
            }
        }
        .overlay(alignment: .topTrailing) {
            if episode.isWatched {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(.white, .black.opacity(0.6))
                    .padding(10)
            }
        }
        .frame(width: Self.width, height: Self.height)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

/** Episode number eyebrow, title, synopsis and air date — the ATV lockup. */
private struct EpisodeMeta: View {
    let episode: BaseItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let index = episode.indexNumber {
                Text("EPISODE \(index)")
                    .font(.caption2.bold())
                    .foregroundStyle(.secondary)
            }
            Text(episode.name ?? "")
                .font(.headline)
                .lineLimit(1)
            if let overview = episode.overview, !overview.isEmpty {
                Text(overview)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
            if let date = episode.premiereDateIso {
                Text(date)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.top, 10)
        .frame(width: EpisodeStill.width, alignment: .leading)
    }
}
