import SwiftUI
import Shared

struct SeriesView: View {
    let api: JellyfinApi
    let series: BaseItem

    @State private var seasons: [BaseItem] = []
    @State private var selectedSeason: BaseItem?
    @State private var episodes: [BaseItem] = []
    @State private var error: String?
    @State private var playingItem: BaseItem?

    var body: some View {
        List {
            if let error {
                Text(error).foregroundStyle(.red)
            }

            if !seasons.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(seasons, id: \.id) { season in
                            Button {
                                selectedSeason = season
                            } label: {
                                Text(season.name ?? "Season")
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(
                                        season.id == selectedSeason?.id
                                            ? AnyShapeStyle(.tint)
                                            : AnyShapeStyle(.quaternary),
                                        in: Capsule()
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                #if !os(tvOS)
                .listRowSeparator(.hidden)
                #endif
            }

            ForEach(episodes, id: \.id) { episode in
                Button {
                    playingItem = episode
                } label: {
                    EpisodeRow(api: api, episode: episode)
                }
                .buttonStyle(.plain)
            }
        }
        .listStyle(.plain)
        .navigationTitle(series.name ?? "")
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
}

private struct EpisodeRow: View {
    let api: JellyfinApi
    let episode: BaseItem

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: api.imageUrl(item: episode, maxWidth: 300).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(.quaternary)
            }
            .frame(width: 120, height: 68)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 2) {
                if let label = episode.episodeLabel {
                    Text(label).font(.caption.bold())
                }
                Text(episode.name ?? "")
                    .font(.body)
                    .lineLimit(1)
                if let minutes = episode.runtimeMinutes {
                    Text("\(minutes) min")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
