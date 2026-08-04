import SwiftUI
import Combine
import Shared
import Libmpv

// Minimal libmpv player: renders into a CAMetalLayer via gpu-next/Vulkan
// (MoltenVK, shipped by MPVKit). mpv+FFmpeg is what gives Jellystream
// Direct Play of virtually every format on Apple platforms.

/// One entry of mpv's `track-list` property (JSON).
struct MediaTrack: Decodable, Identifiable {
    let id: Int
    let type: String   // "video" | "audio" | "sub"
    let title: String?
    let lang: String?
    let selected: Bool

    var label: String {
        let parts = [title, lang].compactMap { $0 }
        return parts.isEmpty ? "Track \(id)" : parts.joined(separator: " · ")
    }
}

@MainActor
final class PlayerModel: ObservableObject {
    let api: JellyfinApi
    let item: BaseItem

    @Published var timePos: Double = 0
    @Published var duration: Double = 0
    @Published var isPaused = false
    @Published var audioTracks: [MediaTrack] = []
    @Published var subtitleTracks: [MediaTrack] = []

    private var mpv: OpaquePointer?
    private var timer: Timer?
    private var tickCount = 0

    init(api: JellyfinApi, item: BaseItem) {
        self.api = api
        self.item = item
    }

    // Safety net if onDisappear never fires: free mpv and the timer.
    // (No stop report here — deinit can't guarantee ordering; shutdown() does that.)
    deinit {
        timer?.invalidate()
        if let handle = mpv {
            mpv_terminate_destroy(handle)
        }
    }

    func attach(to layer: CAMetalLayer) {
        guard mpv == nil, let handle = mpv_create() else { return }
        mpv = handle

        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
        mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid)
        mpv_set_option_string(handle, "vo", "gpu-next")
        mpv_set_option_string(handle, "gpu-api", "vulkan")
        mpv_set_option_string(handle, "hwdec", "videotoolbox")
        mpv_set_option_string(handle, "keep-open", "yes")
        // Token travels as a header, never in the URL (proxy/player logs)
        if let auth = api.streamAuthorizationHeader() {
            mpv_set_option_string(handle, "http-header-fields", "Authorization: \(auth)")
        }

        let resume = item.resumePositionSeconds
        if resume > 1 {
            mpv_set_option_string(handle, "start", String(resume))
        }

        guard mpv_initialize(handle) >= 0 else {
            shutdown()
            return
        }

        guard let url = api.streamUrl(item: item) else {
            shutdown()
            return
        }
        command("loadfile", url)

        Task { try? await api.reportPlaybackStart(itemId: item.id) }

        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func shutdown() {
        timer?.invalidate()
        timer = nil
        guard let handle = mpv else { return }
        let finalTicks = JellyfinApi.companion.secondsToTicks(seconds: timePos)
        mpv = nil
        mpv_terminate_destroy(handle)
        // Fire-and-forget: the resume point must survive closing the player
        Task { [api, item] in
            try? await api.reportPlaybackStopped(itemId: item.id, positionTicks: finalTicks)
        }
    }

    func togglePause() {
        command("cycle", "pause")
    }

    func seek(to seconds: Double) {
        command("seek", String(seconds), "absolute")
    }

    func selectAudioTrack(id: Int) {
        setString("aid", String(id))
        refreshTracks()
    }

    /** nil disables subtitles. */
    func selectSubtitleTrack(id: Int?) {
        setString("sid", id.map(String.init) ?? "no")
        refreshTracks()
    }

    private func refreshTracks() {
        guard let json = getString("track-list"),
              let data = json.data(using: .utf8),
              let tracks = try? JSONDecoder().decode([MediaTrack].self, from: data)
        else { return }
        audioTracks = tracks.filter { $0.type == "audio" }
        subtitleTracks = tracks.filter { $0.type == "sub" }
    }

    private func tick() {
        timePos = getDouble("time-pos")
        duration = getDouble("duration")
        isPaused = getFlag("pause")

        tickCount += 1

        // Track list settles once demuxing starts; refresh every 2 s
        if tickCount % 4 == 0 {
            refreshTracks()
        }

        // Every 10 ticks (~5 s), tell the server where we are
        if tickCount % 10 == 0 {
            let ticks = JellyfinApi.companion.secondsToTicks(seconds: timePos)
            let paused = isPaused
            Task { [api, item] in
                try? await api.reportPlaybackProgress(
                    itemId: item.id,
                    positionTicks: ticks,
                    isPaused: paused
                )
            }
        }
    }

    private func getDouble(_ name: String) -> Double {
        guard let handle = mpv else { return 0 }
        var value = 0.0
        mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &value)
        return value
    }

    private func getFlag(_ name: String) -> Bool {
        guard let handle = mpv else { return false }
        var value: Int32 = 0
        mpv_get_property(handle, name, MPV_FORMAT_FLAG, &value)
        return value != 0
    }

    private func getString(_ name: String) -> String? {
        guard let handle = mpv,
              let cString = mpv_get_property_string(handle, name)
        else { return nil }
        defer { mpv_free(cString) }
        return String(cString: cString)
    }

    private func setString(_ name: String, _ value: String) {
        guard let handle = mpv else { return }
        mpv_set_property_string(handle, name, value)
    }

    private func command(_ args: String...) {
        guard let handle = mpv else { return }
        var cStrings = args.map { UnsafePointer<CChar>(strdup($0)) }
        cStrings.append(nil)
        defer { cStrings.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        mpv_command(handle, &cStrings)
    }
}

struct MPVPlayerView: UIViewRepresentable {
    @ObservedObject var model: PlayerModel

    func makeUIView(context: Context) -> MetalHostView {
        let view = MetalHostView()
        view.backgroundColor = .black
        model.attach(to: view.metalLayer)
        return view
    }

    func updateUIView(_ uiView: MetalHostView, context: Context) {}

    final class MetalHostView: UIView {
        override class var layerClass: AnyClass { CAMetalLayer.self }
        var metalLayer: CAMetalLayer { layer as! CAMetalLayer }
    }
}

struct PlayerScreen: View {
    @StateObject private var model: PlayerModel
    @Environment(\.dismiss) private var dismiss

    init(api: JellyfinApi, item: BaseItem) {
        _model = StateObject(wrappedValue: PlayerModel(api: api, item: item))
    }

    var body: some View {
        ZStack {
            MPVPlayerView(model: model)
                .ignoresSafeArea()

            #if !os(tvOS)
            VStack {
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.largeTitle)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    Spacer()
                }
                Spacer()
                controls
            }
            .padding()
            #endif
        }
        #if os(tvOS)
        .onPlayPauseCommand { model.togglePause() }
        .onExitCommand { dismiss() }
        #endif
        .onDisappear { model.shutdown() }
    }

    #if !os(tvOS)
    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                model.togglePause()
            } label: {
                Image(systemName: model.isPaused ? "play.fill" : "pause.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
            }

            Text(Self.timeString(model.timePos))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white)

            Slider(
                value: Binding(
                    get: { model.timePos },
                    set: { model.seek(to: $0) }
                ),
                in: 0...max(model.duration, 1)
            )

            Text(Self.timeString(model.duration))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white)

            if model.audioTracks.count > 1 {
                Menu {
                    ForEach(model.audioTracks) { track in
                        Button {
                            model.selectAudioTrack(id: track.id)
                        } label: {
                            if track.selected {
                                Label(track.label, systemImage: "checkmark")
                            } else {
                                Text(track.label)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "waveform")
                        .foregroundStyle(.white)
                }
            }

            if !model.subtitleTracks.isEmpty {
                Menu {
                    Button {
                        model.selectSubtitleTrack(id: nil)
                    } label: {
                        if !model.subtitleTracks.contains(where: \.selected) {
                            Label("Off", systemImage: "checkmark")
                        } else {
                            Text("Off")
                        }
                    }
                    ForEach(model.subtitleTracks) { track in
                        Button {
                            model.selectSubtitleTrack(id: track.id)
                        } label: {
                            if track.selected {
                                Label(track.label, systemImage: "checkmark")
                            } else {
                                Text(track.label)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "captions.bubble")
                        .foregroundStyle(.white)
                }
            }
        }
        .padding(12)
        .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
    }

    private static func timeString(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }
    #endif
}
