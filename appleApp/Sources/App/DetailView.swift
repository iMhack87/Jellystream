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
            VStack(alignment: .leading, spacing: 12) {
                // Full-bleed backdrop melting into black
                ZStack(alignment: .bottom) {
                    AsyncImage(url: api.backdropUrl(item: item, maxWidth: 1920).flatMap { URL(string: $0) }) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Rectangle().fill(Color(white: 0.1))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 320)
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
                    .frame(height: 320)
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text(item.name ?? "")
                        .font(.title.bold())
                        .foregroundStyle(.white)

                    if let episodeLabel = item.episodeLabel {
                        Text("\(item.seriesName ?? "") · \(episodeLabel)")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                    }

                    if !metaLine.isEmpty {
                        Text(metaLine)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    if let genres = item.genres, !genres.isEmpty {
                        Text(genres.joined(separator: " · "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // tvOS: system style draws a real pill (visible unfocused,
                    // focus lift handled) — .plain would be bare text
                    Button {
                        playingItem = item
                    } label: {
                        let resume = item.resumePositionSeconds
                        Label(
                            resume > 60 ? "Resume (\(Int(resume / 60)) min)" : "Play",
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

                    if let overview = item.overview {
                        Text(overview)
                            .font(.body)
                            .foregroundStyle(.white.opacity(0.9))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            }
        }
        .background(Color.black)
        .ignoresSafeArea(edges: .top)
        .preferredColorScheme(.dark)
        // tvOS draws the navigation title over the full-bleed backdrop —
        // ghost text on the image; the content already shows the title
        #if !os(tvOS)
        .navigationTitle(item.name ?? "")
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
