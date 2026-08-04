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
            #endif
            .task { await load() }
        }
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

private struct LibraryRow: View {
    let api: JellyfinApi
    let section: LibrarySection

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(section.title)
                .font(.title2.bold())
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(section.items, id: \.id) { item in
                        NavigationLink(value: item) {
                            PosterCard(api: api, item: item)
                        }
                        .buttonStyle(.plain)
                        .disabled(!item.isPlayable && !item.isSeries)
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
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
