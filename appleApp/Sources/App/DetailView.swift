import SwiftUI
import Shared

struct DetailView: View {
    let api: JellyfinApi
    /// Only ever handed on to the player, for the end-of-episode offer.
    let seerr: JellyseerrApi
    @State private var item: BaseItem
    @State private var playingItem: BaseItem?
    /// A refused flag says so under the buttons and nowhere else — an
    /// alert over a screen that still works is the wrong trade.
    @State private var notice: String?
    @Environment(\.appSettings) private var appSettings
    @Environment(\.downloader) private var downloader
    @Environment(\.downloadingAllowed) private var downloadingAllowed

    init(api: JellyfinApi, seerr: JellyseerrApi, item: BaseItem) {
        self.api = api
        self.seerr = seerr
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

                    RatingsRow(ratings: item.ratings)

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

                    itemActions

                    if let notice {
                        Text(notice)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    // Offline is a phone and tablet feature: a television
                    // sits on the same network as the server
                    #if !os(tvOS)
                    if let downloader {
                        // Its own view so the label follows the download:
                        // @Environment hands over the object but does not
                        // observe it, so the row stayed on "Download"
                        DownloadControl(
                            downloader: downloader,
                            api: api,
                            item: item,
                            allowed: downloadingAllowed
                        )
                    }
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
            PlayerScreen(api: api, item: playing, settings: appSettings, seerr: seerr)
        }
    }

    /// Favourite, watched, watchlist. The first two are Jellyfin's own
    /// flags — every other client sees them — and the third is this
    /// install's list, which is why it can hold what the server lacks.
    private var itemActions: some View {
        HStack(spacing: 20) {
            Button {
                toggleFavorite()
            } label: {
                Image(systemName: item.isFavorite ? "heart.fill" : "heart")
                    .font(.title3)
            }
            #if !os(tvOS)
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            #endif
            .accessibilityLabel(item.isFavorite ? "Remove favourite" : "Add favourite")

            Button {
                toggleWatched()
            } label: {
                Label(
                    item.isWatched ? "Mark as unwatched" : "Mark as watched",
                    systemImage: item.isWatched ? "checkmark.circle.fill" : "checkmark.circle"
                )
                .font(.subheadline)
            }
            #if !os(tvOS)
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            #endif

            WatchlistButton(entry: WatchlistEntry.companion.of(item: item))
        }
    }

    /// Flip the icon first and put it back if the server says no: waiting
    /// on a round trip makes the button feel broken, and the shared
    /// module hands us the copy to flip to.
    private func toggleFavorite() {
        let wanted = !item.isFavorite
        let before = item
        item = item.withFavorite(favorite: wanted)
        notice = nil
        Task {
            // The bridge boxes a Kotlin Boolean; false is also what a thrown
            // error means here — either way the server did not take it
            let ok = (try? await api.setFavorite(itemId: before.id, favorite: wanted))?.boolValue ?? false
            if !ok {
                item = before
                notice = "Couldn't reach the server"
            }
        }
    }

    private func toggleWatched() {
        let wanted = !item.isWatched
        let before = item
        // withWatched also clears the resume position, so the Play button
        // stops offering to resume something just marked as seen
        item = item.withWatched(watched: wanted)
        notice = nil
        Task {
            let ok = (try? await api.setWatched(itemId: before.id, watched: wanted))?.boolValue ?? false
            if !ok {
                item = before
                notice = "Couldn't reach the server"
            }
        }
    }

    private var metaLine: String {
        var parts: [String] = []
        if let year = item.productionYear { parts.append("\(year)") }
        if let minutes = item.runtimeMinutes { parts.append("\(minutes) min") }
        // Ratings moved out to RatingsRow — a star, a tomatometer and a
        // certificate crammed into one grey line read as trivia
        return parts.joined(separator: "  ·  ")
    }
}

#if !os(tvOS)
/// The Download button, and what it becomes once a download exists.
private struct DownloadControl: View {
    @ObservedObject var downloader: Downloader
    let api: JellyfinApi
    let item: BaseItem
    let allowed: Bool?

    var body: some View {
        if let existing = downloader.downloads.get(itemId: item.id) {
            Text(Self.label(existing.state))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        } else {
            let availability = DownloadAvailability.companion.of(
                item: item,
                downloadingEnabled: allowed.map { KotlinBoolean(bool: $0) }
            )
            if availability.canDownload {
                Button("Download") {
                    Task {
                        let container = try? await api.containerOf(item: item)
                        downloader.start(item: item, container: container)
                    }
                }
                .font(.subheadline)
            } else if let explanation = availability.explanation {
                // Say why rather than show a button that would 401
                Text(explanation).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private static func label(_ state: DownloadState) -> String {
        switch state {
        case .queued: return "Queued for download"
        case .downloading: return "Downloading…"
        case .complete: return "Available offline"
        case .failed: return "Download failed"
        default: return ""
        }
    }
}
#endif
