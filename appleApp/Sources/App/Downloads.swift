import Foundation
import SwiftUI
import Shared

/**
 App-private storage for the downloads blob (the shared module owns the
 JSON). One entry per profile: two accounts sharing an iPad do not share a
 watchlist, and one signing out must not take the other's files.
 */
enum DownloadStore {
    private static func key(_ profileKey: String) -> String {
        "dev.jellystream.downloads.\(profileKey)"
    }

    static func load(profileKey: String) -> PersistedDownloads {
        UserDefaults.standard.string(forKey: key(profileKey))
            .flatMap { PersistedDownloads.companion.fromJson(json: $0) }
        // A corrupt blob costs the list, not the launch — the files are
        // still on disk and can be fetched again
            ?? PersistedDownloads.companion.empty()
    }

    static func save(_ downloads: PersistedDownloads, profileKey: String) {
        UserDefaults.standard.set(downloads.toJson(), forKey: key(profileKey))
    }
}

/// Where a profile's files live. Application Support, not Documents: these
/// are re-downloadable, so they stay out of the user's file browser.
func downloadsDirectory(profileKey: String) -> URL {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    let dir = base.appendingPathComponent("Downloads/\(abs(profileKey.hashValue))", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
}

func downloadedFileURL(profileKey: String, item: DownloadedItem) -> URL {
    downloadsDirectory(profileKey: profileKey).appendingPathComponent(item.fileName)
}

/**
 Fetches original files and reports progress as they arrive.

 The token travels in a header, the same way playback does — never in the
 URL, which proxies and logs keep.
 */
@MainActor
final class Downloader: NSObject, ObservableObject {
    @Published private(set) var downloads: PersistedDownloads

    private let api: JellyfinApi
    private let profileKey: String
    private var session: URLSession!
    /// Which item each task is fetching, so delegate callbacks can find it.
    private var inFlight: [Int: String] = [:]

    init(api: JellyfinApi, profileKey: String) {
        self.api = api
        self.profileKey = profileKey
        self.downloads = DownloadStore.load(profileKey: profileKey)
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    func start(item: BaseItem, container: String?) {
        guard let urlString = api.downloadUrl(itemId: item.id),
              let url = URL(string: urlString) else { return }
        let queued = DownloadedItem.companion.queued(item: item, container: container)
        publish(downloads.with(item: queued))

        var request = URLRequest(url: url)
        if let auth = api.streamAuthorizationHeader() {
            request.setValue(auth, forHTTPHeaderField: "Authorization")
        }
        let task = session.downloadTask(with: request)
        inFlight[task.taskIdentifier] = item.id
        task.resume()
    }

    /// Drops the record and the bytes; a half file is worth nothing.
    func remove(itemId: String) {
        if let item = downloads.get(itemId: itemId) {
            try? FileManager.default.removeItem(
                at: downloadedFileURL(profileKey: profileKey, item: item)
            )
        }
        publish(downloads.without(itemId: itemId))
    }

    /// Records where offline playback got to, for the server to hear later.
    func recordPosition(itemId: String, ticks: Int64) {
        publish(downloads.markPosition(itemId: itemId, ticks: ticks))
        syncPositions()
    }

    /**
     Tells the server about positions watched with the network off.

     Failure is silent and leaves the entry unsynced: the next launch tries
     again, and losing a resume point is worse than a retry.
     */
    func syncPositions() {
        let pending = downloads.unsyncedPositions
        guard !pending.isEmpty else { return }
        Task {
            for item in pending {
                let sent: Bool = await {
                    do {
                        try await api.reportPlaybackStopped(
                            itemId: item.itemId,
                            positionTicks: item.positionTicks,
                            playSessionId: nil
                        )
                        return true
                    } catch {
                        return false
                    }
                }()
                if sent { publish(downloads.markSynced(itemId: item.itemId)) }
            }
        }
    }

    fileprivate func publish(_ updated: PersistedDownloads) {
        downloads = updated
        DownloadStore.save(updated, profileKey: profileKey)
    }

    fileprivate func item(for task: URLSessionTask) -> DownloadedItem? {
        inFlight[task.taskIdentifier].flatMap { downloads.get(itemId: $0) }
    }

    fileprivate func forget(_ task: URLSessionTask) {
        inFlight.removeValue(forKey: task.taskIdentifier)
    }

    fileprivate var storageKey: String { profileKey }
}

extension Downloader: URLSessionDownloadDelegate {
    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        Task { @MainActor in
            guard let item = self.item(for: downloadTask) else { return }
            publish(
                downloads.with(
                    item: item
                        .doCopy(
                            itemId: item.itemId,
                            fileName: item.fileName,
                            name: item.name,
                            state: .downloading,
                            totalBytes: max(totalBytesExpectedToWrite, 0),
                            downloadedBytes: totalBytesWritten,
                            seriesName: item.seriesName,
                            episodeLabel: item.episodeLabel,
                            runTimeTicks: item.runTimeTicks,
                            productionYear: item.productionYear,
                            positionTicks: item.positionTicks,
                            positionSynced: item.positionSynced
                        )
                )
            )
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // The temporary file is gone the moment this returns, so it has to
        // be moved here, synchronously, off the main actor
        let response = downloadTask.response as? HTTPURLResponse
        let ok = (200...299).contains(response?.statusCode ?? 0)
        let moved: URL? = {
            guard ok else { return nil }
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
            try? FileManager.default.moveItem(at: location, to: destination)
            return destination
        }()

        Task { @MainActor in
            defer { self.forget(downloadTask) }
            guard let item = self.item(for: downloadTask) else { return }
            guard let moved else {
                publish(downloads.with(item: item.doCopy(
                    itemId: item.itemId, fileName: item.fileName, name: item.name,
                    state: .failed, totalBytes: item.totalBytes,
                    downloadedBytes: item.downloadedBytes, seriesName: item.seriesName,
                    episodeLabel: item.episodeLabel, runTimeTicks: item.runTimeTicks,
                    productionYear: item.productionYear, positionTicks: item.positionTicks,
                    positionSynced: item.positionSynced
                )))
                return
            }
            let target = downloadedFileURL(profileKey: self.storageKey, item: item)
            try? FileManager.default.removeItem(at: target)
            let placed = (try? FileManager.default.moveItem(at: moved, to: target)) != nil
            let attributes = try? FileManager.default.attributesOfItem(atPath: target.path)
            let size = (attributes?[.size] as? NSNumber)?.int64Value ?? item.totalBytes
            publish(downloads.with(item: item.doCopy(
                itemId: item.itemId, fileName: item.fileName, name: item.name,
                state: placed ? .complete : .failed,
                totalBytes: size,
                downloadedBytes: size,
                seriesName: item.seriesName, episodeLabel: item.episodeLabel,
                runTimeTicks: item.runTimeTicks, productionYear: item.productionYear,
                positionTicks: item.positionTicks, positionSynced: item.positionSynced
            )))
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard error != nil else { return }
        Task { @MainActor in
            defer { self.forget(task) }
            guard let item = self.item(for: task) else { return }
            // A partial file must not look playable
            try? FileManager.default.removeItem(
                at: downloadedFileURL(profileKey: self.storageKey, item: item)
            )
            publish(downloads.with(item: item.doCopy(
                itemId: item.itemId, fileName: item.fileName, name: item.name,
                state: .failed, totalBytes: item.totalBytes,
                downloadedBytes: item.downloadedBytes, seriesName: item.seriesName,
                episodeLabel: item.episodeLabel, runTimeTicks: item.runTimeTicks,
                productionYear: item.productionYear, positionTicks: item.positionTicks,
                positionSynced: item.positionSynced
            )))
        }
    }
}

/// The active profile's downloader, reachable from a detail page without
/// threading it through every navigation destination.
private struct DownloaderKey: EnvironmentKey {
    static let defaultValue: Downloader? = nil
}

/// Whether this account may download at all — Jellyfin decides per user,
/// and demo.jellyfin.org says no.
private struct DownloadingAllowedKey: EnvironmentKey {
    static let defaultValue: Bool? = nil
}

extension EnvironmentValues {
    var downloader: Downloader? {
        get { self[DownloaderKey.self] }
        set { self[DownloaderKey.self] = newValue }
    }

    var downloadingAllowed: Bool? {
        get { self[DownloadingAllowedKey.self] }
        set { self[DownloadingAllowedKey.self] = newValue }
    }
}

/// `fullScreenCover(item:)` needs an identity; the item id is one.
extension DownloadedItem: @retroactive Identifiable {
    public var id: String { itemId }
}
