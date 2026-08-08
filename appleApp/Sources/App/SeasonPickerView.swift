import SwiftUI
import Shared

/**
 Which season of a show to ask for.

 A series row used to request the whole show, which is almost never what
 anyone means: the common case is one season missing from an otherwise
 complete library, and asking for all of it makes the server re-check
 every episode it already has. "All seasons" is still the first row, so
 the old behaviour costs one extra press and no explanation.
 */
struct SeasonPickerView: View {
    let seerr: JellyseerrApi
    let tmdbId: Int32
    /// What the search result called it — the header until (and if) the
    /// detail endpoint answers with something better, and the name every
    /// notice uses so the wording cannot drift with TMDb's localisation.
    let showTitle: String
    let showYear: String?
    /// The requests screen's own notice, not a copy: a message raised here
    /// has to survive going back, which is where its result shows up.
    @Binding var notice: String?
    /// "All seasons" goes back through the results screen's request path so
    /// the row behind this one flips too. A single season is this screen's
    /// business alone.
    let onRequestAll: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var details: JellyseerrTvDetails?
    @State private var loading = true
    /// Optimistic state keyed by SEASON NUMBER, never by show: asking for
    /// season 2 must leave season 3 exactly as it was.
    @State private var justRequested: [Int: RequestState] = [:]

    private var showName: String {
        if let name = details?.name, !name.isEmpty { return name }
        return showTitle
    }

    var body: some View {
        List {
            if loading {
                Section { ProgressView() }
            } else if let details {
                Section {
                    // Under the year and above the rows, where Android puts
                    // it: a banner floating above the section header reads
                    // as belonging to the screen, not to what was just asked
                    if let notice {
                        Text(notice).font(.callout)
                    }

                    // Everything, in the order Jellyseerr itself would take
                    // it — season 0 excluded on both sides, so the button
                    // and the rows below promise the same thing
                    Button(action: onRequestAll) {
                        Text("All seasons").font(.headline)
                    }

                    ForEach(details.requestableSeasons, id: \.seasonNumber) { season in
                        SeasonRow(
                            season: season,
                            state: justRequested[Int(season.seasonNumber)]
                                ?? details.stateOf(seasonNumber: season.seasonNumber),
                            onRequest: { request(season) }
                        )
                    }
                } header: {
                    // The name is already the navigation title right above
                    // this; repeating it reads as a bug. Only the year adds
                    // anything, which is what the Android header shows too.
                    Text(details.year ?? showYear ?? showName)
                }
            } else {
                // An unreachable Jellyseerr degrades to a screen that says
                // so and lets you leave — never an alert over a dead list
                Section {
                    Text("Couldn't load seasons for \(showTitle).")
                        .foregroundStyle(.secondary)
                    Button("Back") { dismiss() }
                }
            }
        }
        .navigationTitle(showName)
        .task {
            details = try? await seerr.tvDetails(tmdbId: tmdbId)
            loading = false
        }
    }

    private func request(_ season: JellyseerrSeason) {
        let number = season.seasonNumber
        Task {
            // Kotlin List<Int> crosses the bridge as [KotlinInt]; one entry,
            // because this row speaks for one season and no other
            let outcome = try? await seerr.requestSeasons(
                tmdbId: tmdbId,
                seasons: [KotlinInt(int: number)]
            )
            switch outcome {
            case is RequestOutcome.Sent:
                justRequested[Int(number)] = .pending
                notice = "Requested \(showTitle) season \(number)"
            case is RequestOutcome.AlreadyRequested:
                justRequested[Int(number)] = .pending
                notice = "Season \(number) was already requested"
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

private struct SeasonRow: View {
    let season: JellyseerrSeason
    let state: RequestState
    let onRequest: () -> Void

    var body: some View {
        // Same rule as the results list: a season already downloading or
        // already here is not a target, and must not look like one
        if state.canRequest {
            Button(action: onRequest) { content }
        } else {
            content
        }
    }

    private var content: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(season.displayName).font(.headline)
                // Kotlin Int? bridges as KotlinInt — interpolating the box
                // itself would print an object, not a number
                if let count = season.episodeCount {
                    Text("\(count.intValue) episodes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            StateChip(state: state)
        }
    }
}
