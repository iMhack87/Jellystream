import SwiftUI
import Shared

/// Apple TV-style series page: big title over the series backdrop, season
/// pills, and episodes as a horizontal shelf of landscape cards (runtime
/// badge on the still; episode number, title, synopsis and air date below).
struct SeriesView: View {
    let api: JellyfinApi
    let series: BaseItem

    @State private var seasons: [BaseItem] = []
    @State private var selectedSeason: BaseItem?
    @State private var episodes: [BaseItem] = []
    @State private var error: String?
    @State private var playingItem: BaseItem?

    #if os(tvOS)
    private let headerHeight: CGFloat = 360
    #else
    private let headerHeight: CGFloat = 260
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header

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
            PlayerScreen(api: api, item: playing)
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

            Text(series.name ?? "")
                .font(.system(size: 46, weight: .heavy))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, HomeMetrics.edgePadding)
                .padding(.bottom, 16)
        }
        .frame(height: headerHeight)
    }

    private var seasonPills: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(seasons, id: \.id) { season in
                    // SeasonPillStyle draws the capsule AND the focus
                    // treatment itself — the system tvOS styles either
                    // erase the selected label (tinted platter) or stack
                    // their own platter behind ours (.plain's focus lift:
                    // the pill-inside-a-pill Matthieu spotted)
                    Button {
                        selectedSeason = season
                    } label: {
                        Text(season.name ?? "Season")
                    }
                    .buttonStyle(
                        SeasonPillStyle(selected: season.id == selectedSeason?.id)
                    )
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
 * Season pill drawing its own focus treatment: one single capsule that
 * scales and glows when the Siri Remote focuses it. No system platter, so
 * no stacked pill-in-pill. On touch platforms `isFocused` stays false and
 * this renders as the plain selected/unselected capsule.
 */
private struct SeasonPillStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        PillLabel(configuration: configuration, selected: selected)
    }

    private struct PillLabel: View {
        let configuration: ButtonStyle.Configuration
        let selected: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.headline)
                .padding(.horizontal, 18)
                .padding(.vertical, 9)
                .background(
                    selected
                        ? AnyShapeStyle(.white)
                        : AnyShapeStyle(.white.opacity(focused ? 0.3 : 0.15)),
                    in: Capsule()
                )
                .foregroundStyle(selected ? .black : .white)
                #if os(tvOS)
                .scaleEffect(focused ? 1.12 : 1.0)
                .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: 12, y: 6)
                .animation(.easeOut(duration: 0.15), value: focused)
                #endif
                .opacity(configuration.isPressed ? 0.8 : 1.0)
        }
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
