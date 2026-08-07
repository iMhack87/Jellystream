import SwiftUI
import Shared

/**
 Ask for what the library does not have, and see what you asked for.

 One screen, two modes: an empty search box shows the requests already
 made, and typing turns it into a catalogue. Splitting them would mean
 navigating away to check whether you had already asked for something.
 */
struct RequestsView: View {
    let seerr: JellyseerrApi

    @State private var query = ""
    @State private var results: [JellyseerrResult] = []
    @State private var mine: [JellyseerrRequest] = []
    @State private var searching = false
    @State private var notice: String?
    /// Optimistic per-title state: the list has to react before the server
    /// agrees, or the row looks broken.
    @State private var justRequested: [Int: RequestState] = [:]

    var body: some View {
        List {
            if let notice {
                Section { Text(notice).font(.callout) }
            }

            if query.isEmpty {
                Section("Your requests") {
                    if mine.isEmpty {
                        Text("Nothing requested yet. Search above to ask for a film or a series.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(mine, id: \.id) { request in
                        RequestRow(request: request)
                    }
                }
            } else if searching {
                Section { ProgressView() }
            } else if results.isEmpty {
                Section { Text("Nothing found for \"\(query)\".").foregroundStyle(.secondary) }
            } else {
                Section("Results") {
                    ForEach(results, id: \.id) { result in
                        ResultRow(
                            result: result,
                            state: justRequested[Int(result.id)] ?? result.state,
                            onRequest: { request(result) }
                        )
                    }
                }
            }
        }
        .navigationTitle("Requests")
        #if !os(tvOS)
        .searchable(text: $query, prompt: "Search for something to request")
        #endif
        #if os(tvOS)
        .safeAreaInset(edge: .top) {
            // tvOS has no .searchable on a plain List, so the field is part
            // of the screen rather than chrome
            TextField("Search for something to request", text: $query)
                .textFieldStyle(.plain)
                .padding(.horizontal, 40)
                .padding(.vertical, 12)
        }
        #endif
        .task(id: query) { await runSearch() }
        .task { mine = await loadRequests() }
    }

    private func runSearch() async {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else {
            results = []
            searching = false
            return
        }
        searching = true
        // Debounced: a search per keystroke would hammer the server, and on
        // a remote every letter is several presses anyway
        try? await Task.sleep(nanoseconds: 350_000_000)
        guard !Task.isCancelled else { return }
        results = (try? await seerr.search(query: query)) ?? []
        searching = false
    }

    private func loadRequests() async -> [JellyseerrRequest] {
        (try? await seerr.myRequests(limit: 30)) ?? []
    }

    private func request(_ result: JellyseerrResult) {
        Task {
            let outcome = try? await seerr.request(
                tmdbId: result.id,
                isSeries: result.isSeries
            )
            switch outcome {
            case is RequestOutcome.Sent:
                justRequested[Int(result.id)] = .pending
                notice = "Requested \(result.displayTitle)"
                mine = await loadRequests()
            case is RequestOutcome.AlreadyRequested:
                justRequested[Int(result.id)] = .pending
                notice = "\(result.displayTitle) was already requested"
            case is RequestOutcome.NotSignedIn:
                notice = "Sign in to Jellyseerr again in Settings"
            case let failure as RequestOutcome.Failed:
                notice = failure.message
            default:
                notice = "Could not reach Jellyseerr"
            }
        }
    }
}

private struct ResultRow: View {
    let result: JellyseerrResult
    let state: RequestState
    let onRequest: () -> Void

    var body: some View {
        // Only a title you can actually ask for is a target; a Request
        // control next to something already downloading is a lie
        if state.canRequest {
            Button(action: onRequest) { content }
        } else {
            content
        }
    }

    private var content: some View {
        HStack(spacing: 14) {
            AsyncImage(
                url: JellyseerrApi.companion.posterUrl(posterPath: result.posterPath, width: 185)
                    .flatMap { URL(string: $0) }
            ) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(Color(white: 0.15))
            }
            .frame(width: 48, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 3) {
                Text(result.displayTitle).font(.headline)
                Text(
                    [result.year, result.isSeries ? "Series" : "Film"]
                        .compactMap { $0 }
                        .joined(separator: " · ")
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer()
            StateChip(state: state)
        }
    }
}

private struct RequestRow: View {
    let request: JellyseerrRequest

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                // The requests endpoint carries no title, only a TMDb id —
                // saying what kind of thing it is beats showing a number
                Text(request.isSeries ? "Series request" : "Film request")
                    .font(.headline)
                if let created = request.createdAt?.prefix(10) {
                    Text(String(created)).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            StateChip(state: request.state)
        }
    }
}

/// The one place a request state turns into something on screen.
private struct StateChip: View {
    let state: RequestState

    var body: some View {
        Text(state.label)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(.white.opacity(0.12), in: Capsule())
    }

    private var tint: Color {
        switch state {
        case .available: return Color(red: 0.33, green: 0.82, blue: 0.42)
        case .declined: return .red
        case .requestable: return .primary
        default: return .secondary
        }
    }
}
