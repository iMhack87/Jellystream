import SwiftUI
import Shared

/// A collection, a genre or a person: a title and a grid of posters.
struct CatalogView: View {
    let api: JellyfinApi
    let item: BaseItem

    @State private var items: [BaseItem] = []
    @State private var error: String?
    @State private var loaded = false

    #if os(tvOS)
    private let columns = [
        GridItem(.adaptive(minimum: 220), spacing: HomeMetrics.cardSpacing),
    ]
    #else
    private let columns = [
        GridItem(.adaptive(minimum: 140), spacing: 16),
    ]
    #endif

    var body: some View {
        Group {
            if let error {
                Text(error).foregroundStyle(.secondary).padding(24)
            } else if !loaded {
                ProgressView()
            } else if items.isEmpty {
                Text("Nothing in here yet").foregroundStyle(.secondary).padding(24)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(items, id: \.id) { child in
                            NavigationLink(value: child) {
                                VStack(alignment: .leading, spacing: 6) {
                                    AsyncImage(url: api.imageUrl(item: child, maxWidth: 400).flatMap(URL.init(string:))) { image in
                                        image.resizable().scaledToFill()
                                    } placeholder: {
                                        Rectangle().fill(Color(white: 0.12))
                                    }
                                    .aspectRatio(2 / 3, contentMode: .fit)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                    Text(child.name ?? "")
                                        .font(.caption)
                                        .foregroundStyle(.white)
                                        .lineLimit(2)
                                }
                            }
                            .disabled(!child.isBrowsable)
                            #if os(tvOS)
                            .buttonStyle(.borderless)
                            #else
                            .buttonStyle(.plain)
                            #endif
                        }
                    }
                    .padding(HomeMetrics.edgePadding)
                }
            }
        }
        .background(Color.black)
        .navigationTitle(item.name ?? "")
        #if !os(tvOS)
        .inlineNavigationTitle()
        #endif
        .task {
            do {
                items = try await CatalogQuery.shared.load(api: api, item: item)
                loaded = true
            } catch {
                self.error = error.localizedDescription
                loaded = true
            }
        }
    }
}

struct PersonRow: View {
    let api: JellyfinApi
    let people: [PersonCredit]

    var body: some View {
        let shown = CatalogQuery.shared.actors(people: people)
        if !shown.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Cast").font(.headline).foregroundStyle(.white)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(shown, id: \.id) { person in
                            if let id = person.id {
                                NavigationLink(value: BaseItem(
                                    id: id,
                                    name: person.name,
                                    type: "Person",
                                    collectionType: nil,
                                    productionYear: nil,
                                    imageTags: nil,
                                    seriesName: nil,
                                    seriesId: nil,
                                    userData: nil,
                                    overview: nil,
                                    runTimeTicks: nil,
                                    genres: nil,
                                    communityRating: nil,
                                    criticRating: nil,
                                    officialRating: nil,
                                    indexNumber: nil,
                                    parentIndexNumber: nil,
                                    backdropImageTags: nil,
                                    parentBackdropItemId: nil,
                                    parentBackdropImageTags: nil,
                                    premiereDate: nil,
                                    providerIds: nil,
                                    people: nil,
                                    genreItems: nil,
                                    chapters: nil,
                                    trickplay: nil,
                                    primaryImageTag: person.primaryImageTag
                                )) {
                                    VStack(spacing: 6) {
                                        AsyncImage(url: api.personImageUrl(person: person, maxWidth: 200).flatMap(URL.init(string:))) { image in
                                            image.resizable().scaledToFill()
                                        } placeholder: {
                                            Circle().fill(Color(white: 0.18))
                                        }
                                        #if os(tvOS)
                                        .frame(width: 110, height: 110)
                                        #else
                                        .frame(width: 72, height: 72)
                                        #endif
                                        .clipShape(Circle())
                                        Text(person.name ?? "")
                                            .font(.caption)
                                            .foregroundStyle(.white)
                                            .lineLimit(1)
                                        if let role = person.role {
                                            Text(role)
                                                .font(.caption2)
                                                .foregroundStyle(.secondary)
                                                .lineLimit(2)
                                                .multilineTextAlignment(.center)
                                        }
                                    }
                                    .frame(width: 96)
                                }
                                #if os(tvOS)
                                .buttonStyle(.borderless)
                                #else
                                .buttonStyle(.plain)
                                #endif
                            }
                        }
                    }
                }
            }
        }
    }
}
