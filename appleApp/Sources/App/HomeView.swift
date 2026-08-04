import SwiftUI
import Shared

struct LibrarySection: Identifiable {
    let view: BaseItem
    let latest: [BaseItem]
    var id: String { view.id }
}

struct HomeView: View {
    let api: JellyfinApi
    let session: UserSession

    @State private var sections: [LibrarySection]?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if let error {
                    Text(error).foregroundStyle(.red).padding()
                } else if let sections {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 32) {
                            ForEach(sections) { section in
                                LibraryRow(api: api, section: section)
                            }
                        }
                        .padding(.vertical)
                    }
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(session.serverName ?? "Jellyfin")
            .task { await load() }
        }
    }

    private func load() async {
        do {
            let views = try await api.getUserViews()
            var result: [LibrarySection] = []
            for view in views {
                let latest = try await api.getLatestItems(viewId: view.id, limit: 12)
                result.append(LibrarySection(view: view, latest: latest))
            }
            sections = result
        } catch {
            self.error = error.localizedDescription
        }
    }
}

private struct LibraryRow: View {
    let api: JellyfinApi
    let section: LibrarySection

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(section.view.name ?? "Library")
                .font(.title2.bold())
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(section.latest, id: \.id) { item in
                        PosterCard(api: api, item: item)
                    }
                }
                .padding(.horizontal)
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
    private let posterWidth: CGFloat = 120
    private let posterHeight: CGFloat = 180
    #endif

    var body: some View {
        VStack(alignment: .leading) {
            AsyncImage(url: api.imageUrl(item: item, maxWidth: 800).flatMap { URL(string: $0) }) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(.quaternary)
            }
            .frame(width: posterWidth, height: posterHeight)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Text(item.name ?? "")
                .font(.caption)
                .lineLimit(2)
                .frame(width: posterWidth, alignment: .leading)
        }
    }
}
