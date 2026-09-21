import SwiftUI
import Shared

/**
 What this profile has taken offline.

 Everything here comes from the stored blob, never from the server — this
 is the one screen that has to work with the network off.
 */
struct DownloadsView: View {
    @ObservedObject var downloader: Downloader
    let profileKey: String
    let onPlay: (DownloadedItem) -> Void

    var body: some View {
        List {
            if downloader.downloads.items.isEmpty {
                Text(Copy.shared.nothingDownloaded)
                .foregroundStyle(.secondary)
            }
            ForEach(downloader.downloads.items, id: \.itemId) { item in
                // The whole file is compiled for tvOS even though the
                // screen is never shown there, and swipe actions do not
                // exist on a remote
                #if os(tvOS)
                DownloadRow(item: item, onPlay: { onPlay(item) })
                #else
                DownloadRow(item: item, onPlay: { onPlay(item) })
                    .swipeActions {
                        Button(Copy.shared.remove, role: .destructive) {
                            downloader.remove(itemId: item.itemId)
                        }
                    }
                #endif
            }
        }
        .navigationTitle(Copy.shared.downloads)
        .cinemaChrome()
    }
}

private struct DownloadRow: View {
    let item: DownloadedItem
    let onPlay: () -> Void

    var body: some View {
        // Only a finished file is a target; a partial one plays as a broken
        // film, which is worse than no button
        if item.isPlayable {
            Button(action: onPlay) { content }
        } else {
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(.headline).lineLimit(1)
                    if let subtitle = item.subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(statusLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(statusTint)
            }
            if item.state == .downloading, let fraction = item.progress {
                ProgressView(value: fraction.doubleValue)
            }
        }
        .padding(.vertical, 2)
    }

    private var statusLabel: String {
        switch item.state {
        case .queued: return Copy.shared.queued
        case .downloading:
            return item.progress.map { "\(Int($0.doubleValue * 100))%" } ?? Copy.shared.downloading
        case .complete: return Copy.shared.onDevice
        case .failed: return Copy.shared.failed
        default: return ""
        }
    }

    private var statusTint: Color {
        switch item.state {
        case .complete: return Color(red: 0.33, green: 0.82, blue: 0.42)
        case .failed: return .red
        default: return .secondary
        }
    }
}
