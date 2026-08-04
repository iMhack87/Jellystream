import SwiftUI
import Shared

struct DetailView: View {
    let api: JellyfinApi
    @State private var item: BaseItem
    @State private var playingItem: BaseItem?

    init(api: JellyfinApi, item: BaseItem) {
        self.api = api
        _item = State(initialValue: item)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                AsyncImage(url: api.imageUrl(item: item, maxWidth: 600).flatMap { URL(string: $0) }) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Rectangle().fill(.quaternary)
                }
                .frame(width: 200, height: 300)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text(item.name ?? "")
                    .font(.title.bold())
                    .multilineTextAlignment(.center)

                if let episodeLabel = item.episodeLabel {
                    Text("\(item.seriesName ?? "") · \(episodeLabel)")
                        .font(.headline)
                }

                if !metaLine.isEmpty {
                    Text(metaLine).font(.subheadline)
                }

                if let genres = item.genres, !genres.isEmpty {
                    Text(genres.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button {
                    playingItem = item
                } label: {
                    let resume = item.resumePositionSeconds
                    Text(resume > 60 ? "Resume (\(Int(resume / 60)) min)" : "Play")
                        .frame(maxWidth: 240)
                }
                .buttonStyle(.borderedProminent)

                if let overview = item.overview {
                    Text(overview)
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(24)
        }
        .navigationTitle(item.name ?? "")
        #if !os(tvOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            if let full = try? await api.getItem(itemId: item.id) {
                item = full
            }
        }
        .fullScreenCover(item: $playingItem) { playing in
            PlayerScreen(api: api, item: playing)
        }
    }

    private var metaLine: String {
        var parts: [String] = []
        if let year = item.productionYear { parts.append("\(year)") }
        if let minutes = item.runtimeMinutes { parts.append("\(minutes) min") }
        if let rating = item.communityRating {
            parts.append(String(format: "★ %.1f", rating.doubleValue))
        }
        return parts.joined(separator: "  ·  ")
    }
}
