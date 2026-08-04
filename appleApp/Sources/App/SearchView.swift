import SwiftUI
import Shared

struct SearchView: View {
    let api: JellyfinApi

    @State private var query = ""
    @State private var results: [BaseItem] = []

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 12)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(results, id: \.id) { item in
                    NavigationLink(value: item) {
                        VStack(alignment: .leading) {
                            AsyncImage(url: api.imageUrl(item: item, maxWidth: 300).flatMap { URL(string: $0) }) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                Rectangle().fill(.quaternary)
                            }
                            .frame(height: 160)
                            .clipShape(RoundedRectangle(cornerRadius: 8))

                            Text(item.name ?? "")
                                .font(.caption)
                                .lineLimit(2)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .navigationTitle("Search")
        .searchable(text: $query)
        .task(id: query) {
            guard query.count >= 2 else {
                results = []
                return
            }
            try? await Task.sleep(nanoseconds: 400_000_000) // debounce
            guard !Task.isCancelled else { return }
            let found = (try? await api.search(query: query, limit: 24)) ?? []
            // Re-check: a cancelled stale task must not overwrite newer results
            guard !Task.isCancelled else { return }
            results = found
        }
    }
}
