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
    /// Titles, posters and download progress already resolved by the shared
    /// module — the raw requests endpoint carries a TMDb id and nothing else.
    @State private var mine: [RequestedTitle] = []
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
                    ForEach(mine, id: \.request.id) { row in
                        RequestRow(row: row)
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
                            seerr: seerr,
                            result: result,
                            state: justRequested[Int(result.id)] ?? result.state,
                            notice: $notice,
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
        // A progress bar nobody refreshes is a screenshot. Keyed on whether
        // anything is actually moving, so the loop stops dead once the last
        // download lands — and starts again on its own when a new one
        // begins. Leaving this screen open must not keep a NAS awake all
        // night; `.task` also ends it the moment the screen goes away.
        .task(id: isDownloading) { await pollRequests() }
    }

    private var isDownloading: Bool {
        mine.contains { $0.progress != nil }
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

    /// Re-asks every 5 s for as long as at least one row is downloading.
    private func pollRequests() async {
        while isDownloading {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            mine = await loadRequests()
        }
    }

    private func loadRequests() async -> [RequestedTitle] {
        // Kotlin default arguments do not bridge — the limit is spelled out
        (try? await seerr.myRequestsDetailed(limit: 30)) ?? []
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
    let seerr: JellyseerrApi
    let result: JellyseerrResult
    let state: RequestState
    @Binding var notice: String?
    let onRequest: () -> Void

    var body: some View {
        // Only a title you can actually ask for is a target; a Request
        // control next to something already downloading is a lie
        if state.canRequest && result.isSeries {
            // A show is rarely wanted whole — one missing season is the
            // common case. The picker leads with "All seasons", so asking
            // for everything is still a single extra press, and it is this
            // row's own request path, so the chip here flips too. A film
            // has nothing to pick and keeps requesting on the spot.
            NavigationLink {
                SeasonPickerView(
                    seerr: seerr,
                    tmdbId: result.id,
                    showTitle: result.displayTitle,
                    showYear: result.year,
                    notice: $notice,
                    onRequestAll: onRequest
                )
            } label: {
                content
            }
        } else if state.canRequest {
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
    let row: RequestedTitle

    var body: some View {
        HStack(spacing: 14) {
            AsyncImage(
                url: JellyseerrApi.companion.posterUrl(posterPath: row.posterPath, width: 185)
                    .flatMap { URL(string: $0) }
            ) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                // A row whose detail lookup failed still has no poster path
                // and still deserves a row — a plain rectangle, never the
                // system's broken-image glyph
                Rectangle().fill(Color(white: 0.15))
            }
            .frame(width: 48, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 3) {
                Text(row.displayTitle).font(.headline)
                Text(row.subtitle).font(.caption).foregroundStyle(.secondary)

                // Only while something is moving: a bar on an approved
                // request nobody has started fetching says nothing
                if let progress = row.progress {
                    RequestProgressBar(progress: progress)
                        .padding(.top, 5)
                    Text(progress.summary)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            StateChip(state: row.state)
        }
    }
}

/**
 The thin determinate bar under a downloading request.

 Muted rather than accented when the grab has stalled: the percentage is
 still true, but the bar must not pretend it is moving.
 */
private struct RequestProgressBar: View {
    let progress: RequestProgress

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                // White on black, like the episode progress bars — not
                // Color.accentColor, which is the system blue this app
                // never uses and which the Android twin cannot match.
                Capsule().fill(.white.opacity(0.35))
                Capsule()
                    .fill(progress.isStalled ? .white.opacity(0.45) : .white)
                    .frame(width: geo.size.width * CGFloat(min(max(progress.fraction, 0), 1)))
            }
        }
        .frame(height: 4)
    }
}

/// The one place a request state turns into something on screen.
/// Shared with the season picker — one look for "where this stands".
struct StateChip: View {
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
