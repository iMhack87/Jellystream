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
    /// The profile's preferences — the subtitle default and size come from
    /// here, and mpv needs them the moment the file loads.
    let settings: AppSettings
    /// Only for the end-of-episode offer. nil offline, and nil is a
    /// perfectly good answer: no Jellyseerr means no card, ever.
    let seerr: JellyseerrApi?

    /// Set when playing a downloaded file. Offline there is nothing to
    /// negotiate, no plan to fetch and no progress to post — a player that
    /// quietly tries any of those hangs on a train.
    var localFile: URL?
    /// Where offline playback got to, handed back for the server to hear
    /// about whenever the network returns.
    var onOfflinePosition: ((Int64) -> Void)?

    @Published var timePos: Double = 0
    @Published var duration: Double = 0
    @Published var isPaused = false
    @Published var audioTracks: [MediaTrack] = []
    @Published var subtitleTracks: [MediaTrack] = []
    /// The Intro/Outro segment the playhead is inside — drives the skip button.
    @Published var skipSegment: MediaSegment?
    /// Resolved once when the player loads, not when playback ends: the
    /// advisor costs up to four round trips, and a card that turns up three
    /// seconds into the credits has already missed the remote. Same timing
    /// as the Android player.
    @Published var nextSeasonOffer: NextSeasonOffer?
    /// mpv has run out of file. Latched, never cleared: this is what raises
    /// the offer card, and the card is dismissed by the viewer, not by a seek.
    @Published var reachedEnd = false

    private var mpv: OpaquePointer?
    private var timer: Timer?
    private var tickCount = 0
    /// Lets the server terminate the transcode job on Stopped.
    private var playSessionId: String?
    /// Intro/outro markers in media time (empty when the server has none).
    private var segments: [MediaSegment] = []
    /// HLS transcodes start at the resume point, so mpv's clock is
    /// window-relative; media time = timePos + this offset.
    private var positionOffset: Double = 0
    /// Segment just skipped — suppressed until the playhead leaves it, so
    /// the button doesn't flash while mpv processes the seek.
    private var skippedSegment: MediaSegment?
    /// The track the shared picker chose, waiting for mpv's track list.
    private var pendingSubtitleDefault: MediaStream?
    private var subtitleDefaultPending = false

    init(
        api: JellyfinApi,
        item: BaseItem,
        settings: AppSettings,
        seerr: JellyseerrApi?,
        localFile: URL? = nil,
        onOfflinePosition: ((Int64) -> Void)? = nil
    ) {
        self.api = api
        self.item = item
        self.settings = settings
        self.seerr = seerr
        self.localFile = localFile
        self.onOfflinePosition = onOfflinePosition
    }

    // Safety net if onDisappear never fires: free mpv and the timer.
    // (No stop report here — deinit can't guarantee ordering; shutdown() does that.)
    deinit {
        timer?.invalidate()
        if let handle = mpv {
            mpv_terminate_destroy(handle)
        }
    }

    /// [forceTranscode] comes from the profile's settings: the escape hatch
    /// for a source mpv mishandles, at the cost of a server-side re-encode.
    func attach(to layer: CAMetalLayer, forceTranscode: Bool) {
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

        // A downloaded file needs none of the negotiation below
        if let localFile {
            applySubtitleScale(settings.subtitleScale)
            command("loadfile", localFile.path)
            timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
            return
        }

        // Negotiate with the server (Direct Play vs HLS transcode + external
        // subtitles), then hand the result to mpv
        Task { [weak self] in
            guard let self else { return }
            let plan = try? await self.api.getPlaybackPlan(
                item: self.item,
                forceTranscode: forceTranscode
            )
            guard self.mpv != nil else { return } // closed while negotiating
            let url = plan?.url ?? self.api.streamUrl(item: self.item)
            guard let url else {
                self.shutdown()
                return
            }
            self.playSessionId = plan?.playSessionId
            if plan?.isTranscode == true {
                // The server already starts the HLS window at the resume
                // point (StartTimeTicks); seeking again would double-apply
                // it. Same guard as the Android player.
                self.setString("start", "none")
            }
            self.positionOffset = plan?.startOffsetSeconds ?? 0
            self.command("loadfile", url)
            for subtitle in plan?.externalSubtitles ?? [] {
                self.command("sub-add", subtitle.url, "auto")
            }
            self.applySubtitleScale(self.settings.subtitleScale)
            // The track list only exists once demuxing has started, so the
            // default waits for it rather than firing into an empty list
            self.pendingSubtitleDefault = self.settings.chooseSubtitle(
                subtitles: plan?.subtitleStreams ?? [],
                audioLanguage: plan?.audioLanguage
            )
            self.subtitleDefaultPending = true
            try? await self.api.reportPlaybackStart(
                itemId: self.item.id,
                playSessionId: plan?.playSessionId
            )
        }

        // Intro/outro markers — [] when the server has no segment provider,
        // so the skip button simply never shows
        Task { [weak self] in
            guard let self else { return }
            let segments = (try? await self.api.getMediaSegments(itemId: self.item.id)) ?? []
            self.segments = segments
        }

        // Is there a next season worth offering when this runs out? Asked
        // now and only once — off the playback path, so a slow or dead
        // Jellyseerr can never be the reason an episode fails to start.
        // nil for anything that is not the last episode of a season, which
        // is nearly always, and the card then never appears.
        if let seerr {
            Task { [weak self] in
                guard let self else { return }
                self.nextSeasonOffer = try? await NextSeasonAdvisor(
                    jellyfin: self.api,
                    seerr: seerr
                ).offerAfter(episode: self.item)
            }
        }

        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func shutdown() {
        timer?.invalidate()
        timer = nil
        guard let handle = mpv else { return }
        // Offline: the position is kept locally, nothing is posted
        if localFile != nil {
            let ticks = JellyfinApi.companion.secondsToTicks(seconds: timePos)
            mpv = nil
            mpv_terminate_destroy(handle)
            onOfflinePosition?(ticks)
            return
        }
        // Media time, not window time: a resumed transcode's clock starts
        // at the resume point and reporting it raw would rewind the server
        let finalTicks = JellyfinApi.companion.secondsToTicks(seconds: positionOffset + timePos)
        mpv = nil
        mpv_terminate_destroy(handle)
        // Fire-and-forget: the resume point must survive closing the player
        Task { [api, item, playSessionId] in
            try? await api.reportPlaybackStopped(
                itemId: item.id,
                positionTicks: finalTicks,
                playSessionId: playSessionId
            )
        }
    }

    func togglePause() {
        command("cycle", "pause")
    }

    func seek(to seconds: Double) {
        command("seek", String(seconds), "absolute")
    }

    /// Jumps past the segment the skip button is currently showing for.
    func skipCurrentSegment() {
        guard let segment = skipSegment else { return }
        skippedSegment = segment
        skipSegment = nil
        seek(to: segment.endSeconds - positionOffset)
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

    /// Subtitle timing offset in seconds; positive shows them later.
    /// Session-only on purpose — a resync belongs to one bad file, and
    /// carrying it into the next title is a bug, not a feature.
    @Published private(set) var subtitleDelay: Double = 0

    /// One nudge of the resync control.
    static let subtitleDelayStep: Double = 0.25

    /// How close to the end still counts as the end. mpv is polled every
    /// half second, so the last sample can sit a little short of duration.
    static let endOfFileSlackSeconds: Double = 2.0

    func nudgeSubtitleDelay(by seconds: Double) {
        setSubtitleDelay(subtitleDelay + seconds)
    }

    func resetSubtitleDelay() {
        setSubtitleDelay(0)
    }

    private func setSubtitleDelay(_ seconds: Double) {
        // Past a few seconds it is the wrong track, not a drift
        let clamped = min(max(seconds, -30), 30)
        subtitleDelay = clamped
        setDouble("sub-delay", clamped)
    }

    /// Applies the profile's size to whatever mpv renders. 1.0 is mpv's own.
    func applySubtitleScale(_ scale: Double) {
        setDouble("sub-scale", scale)
    }

    /**
     Switches on the track the shared picker chose, or leaves subtitles off.

     mpv numbers its own tracks, so the match is made on what both sides
     carry: the language, and whether the track is forced. Side-loaded
     external subtitles are in the same list by then.
     */
    func applySubtitleDefault(_ desired: MediaStream?) {
        refreshTracks()
        guard let desired else {
            selectSubtitleTrack(id: nil)
            return
        }
        let match = subtitleTracks.first { track in
            LanguageCode.shared.matches(a: track.lang, b: desired.language)
        }
        // No match means the picker saw a stream mpv has not surfaced —
        // leaving mpv's own choice alone beats forcing a wrong track
        if let match {
            selectSubtitleTrack(id: match.id)
        }
    }

    private func refreshTracks() {
        guard let json = getString("track-list"),
              let data = json.data(using: .utf8),
              let tracks = try? JSONDecoder().decode([MediaTrack].self, from: data)
        else { return }
        audioTracks = tracks.filter { $0.type == "audio" }
        subtitleTracks = tracks.filter { $0.type == "sub" }

        // First list mpv produces for this file: apply the profile's
        // default now, then never again — from here the track panel
        // belongs to the viewer.
        if subtitleDefaultPending, !tracks.isEmpty {
            subtitleDefaultPending = false
            applySubtitleDefault(pendingSubtitleDefault)
        }
    }

    private func tick() {
        timePos = getDouble("time-pos")
        duration = getDouble("duration")
        isPaused = getFlag("pause")

        // `keep-open=yes` means mpv parks on the last frame instead of
        // quitting or going idle, so the end of a file is a property to
        // poll, not an event — there is no mpv_wait_event loop here. And
        // `isPaused` is useless for this: a viewer pressing pause looks
        // exactly the same.
        // A stream that dies mid-episode also raises eof-reached, and a
        // "that was the last episode" card over minute twelve is worse
        // than no card at all. Require the position to actually be at the
        // end before believing it.
        // A stream that dies mid-episode also raises eof-reached, and a
        // "that was the last episode" card over minute twelve is worse
        // than no card at all. Require the position to actually be at the
        // end before believing it.
        //
        // Against timePos, NOT positionOffset + timePos: on a resumed
        // transcode `duration` is the length of the window mpv is playing,
        // while positionOffset is the media time that window starts at.
        // Adding them compares media time to a window length and fires at
        // once.
        if !reachedEnd, getFlag("eof-reached"), duration > 0,
           timePos >= duration - Self.endOfFileSlackSeconds {
            reachedEnd = true
        }

        var active = SkipSegments.shared.activeSegment(
            segments: segments,
            positionSeconds: positionOffset + timePos
        )
        if active == nil {
            skippedSegment = nil
        } else if active === skippedSegment {
            active = nil
        }
        if active !== skipSegment {
            skipSegment = active
        }

        tickCount += 1

        // Track list settles once demuxing starts; refresh every 2 s
        if tickCount % 4 == 0 {
            refreshTracks()
        }

        // Every 10 ticks (~5 s), tell the server where we are
        if tickCount % 10 == 0 {
            // Media time (window position + transcode offset), like Android
            let ticks = JellyfinApi.companion.secondsToTicks(seconds: positionOffset + timePos)
            let paused = isPaused
            Task { [api, item, playSessionId] in
                try? await api.reportPlaybackProgress(
                    itemId: item.id,
                    positionTicks: ticks,
                    isPaused: paused,
                    playSessionId: playSessionId
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

    private func setDouble(_ name: String, _ value: Double) {
        guard let handle = mpv else { return }
        var raw = value
        mpv_set_property(handle, name, MPV_FORMAT_DOUBLE, &raw)
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
    /// Read from the environment by PlayerScreen and passed down, so the
    /// value is already resolved when SwiftUI calls makeUIView.
    let forceTranscode: Bool

    func makeUIView(context: Context) -> MetalHostView {
        let view = MetalHostView()
        view.backgroundColor = .black
        model.attach(to: view.metalLayer, forceTranscode: forceTranscode)
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
    @Environment(\.appSettings) private var appSettings
    #if os(tvOS)
    @State private var showTracks = false
    @FocusState private var skipFocused: Bool
    /// The card owns two buttons at most; the Focus Engine needs to be told
    /// which, because the primary one is replaced after a successful request.
    private enum OfferButton: Hashable { case primary, secondary }
    @FocusState private var offerFocus: OfferButton?
    #endif

    /// The end-of-episode offer card is up. Its own state, separate from the
    /// player's: closing the card must never close the player.
    @State private var showOffer = false
    @State private var offerDismissed = false
    /// The request went through — the card turns into an acknowledgement.
    @State private var offerSent = false
    /// What Jellyseerr said when it refused. Shown inside the card and
    /// nowhere else: an alert over a paused episode is not an answer.
    @State private var offerNotice: String?
    @State private var offerBusy = false

    // Settings arrive as a parameter, not from the environment: the model
    // is built in init, before @Environment is readable, and mpv needs the
    // subtitle preference at loadfile time. `seerr` travels the same way,
    // and is nil offline — a downloaded file has no next season to offer.
    init(
        api: JellyfinApi,
        item: BaseItem,
        settings: AppSettings,
        seerr: JellyseerrApi?,
        localFile: URL? = nil,
        onOfflinePosition: ((Int64) -> Void)? = nil
    ) {
        _model = StateObject(
            wrappedValue: PlayerModel(
                api: api,
                item: item,
                settings: settings,
                seerr: seerr,
                localFile: localFile,
                onOfflinePosition: onOfflinePosition
            )
        )
    }

    var body: some View {
        ZStack {
            MPVPlayerView(model: model, forceTranscode: appSettings.alwaysTranscode)
                .ignoresSafeArea()

            #if !os(tvOS)
            // Double-tap left/right halves: ±10 s (under the controls layer)
            HStack(spacing: 0) {
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { model.seek(to: max(0, model.timePos - 10)) }
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { model.seek(to: model.timePos + 10) }
            }
            .ignoresSafeArea()

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

            #if os(tvOS)
            if showTracks {
                TrackPanel(model: model)
            }
            #endif

            // Apple TV+-style timed skip pill: appears when playback enters
            // an Intro/Outro segment, gone when the segment ends. On tvOS it
            // yields to the track panel — one overlay owns the Focus Engine
            // at a time (same discipline as .focusable(!showTracks)).
            #if os(tvOS)
            let skipPillHidden = showTracks || showOffer
            #else
            // No Focus Engine to fight over here, but a Skip Credits pill
            // underneath the offer card is still two answers to one question
            let skipPillHidden = showOffer
            #endif
            if let segment = model.skipSegment, !skipPillHidden {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button {
                            model.skipCurrentSegment()
                        } label: {
                            Text(segment.isOutro ? "Skip Credits" : "Skip Intro")
                                .font(.headline)
                                #if !os(tvOS)
                                .padding(.horizontal, 22)
                                .padding(.vertical, 12)
                                .background(.black.opacity(0.55), in: Capsule())
                                .foregroundStyle(.white)
                                .overlay(Capsule().strokeBorder(.white.opacity(0.3)))
                                #endif
                        }
                        #if os(tvOS)
                        .focused($skipFocused)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                #if os(tvOS)
                .padding(.horizontal, 80)
                .padding(.bottom, 60)
                #else
                .padding(.horizontal, 24)
                .padding(.bottom, 76)
                #endif
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            // The next season isn't on the server, and Jellyseerr can go and
            // get it. Raised only when the file actually runs out — and only
            // when the advisor found something, so the usual episode still
            // ends in silence, exactly as before.
            if showOffer, let offer = model.nextSeasonOffer {
                offerCard(offer)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.skipSegment == nil)
        // A null offer means today's behaviour: the file ends, nothing happens.
        // Both edges matter: the advisor costs four round trips and can
        // easily answer AFTER a short episode has run out, and watching only
        // reachedEnd would drop the card on exactly the slow servers that
        // need it most.
        .onChange(of: model.reachedEnd) { _, ended in
            if ended, model.nextSeasonOffer != nil, !offerDismissed {
                showOffer = true
            }
        }
        .onChange(of: model.nextSeasonOffer == nil) { _, isNil in
            if !isNil, model.reachedEnd, !offerDismissed {
                showOffer = true
            }
        }
        #if os(tvOS)
        // While an overlay is open the Focus Engine must own the arrows:
        // the outer view stops being focusable and stops intercepting moves
        .focusable(!showTracks && !showOffer)
        .onPlayPauseCommand { model.togglePause() }
        .onMoveCommand { direction in
            guard !showTracks, !showOffer else { return }
            switch direction {
            case .left: model.seek(to: max(0, model.timePos - 10))
            case .right: model.seek(to: model.timePos + 10)
            case .down:
                // Only open the panel when it has something to show: an
                // empty panel renders no focusable button, and with the
                // outer view .focusable(false) the Focus Engine would have
                // nowhere to land — the user must never end up stuck
                if model.audioTracks.count > 1 || !model.subtitleTracks.isEmpty {
                    showTracks = true
                }
            default: break
            }
        }
        .onExitCommand {
            // Menu closes whatever sits on top of the video, innermost
            // first. Quitting the player instead would throw the viewer out
            // of the episode for pressing the one obvious "go back" key.
            if showOffer {
                dismissOffer()
            } else if showTracks {
                showTracks = false
            } else {
                dismiss()
            }
        }
        // The pill grabs focus on appear so one center press skips; when it
        // leaves, focus falls back to the player view (arrows seek again).
        // Never steal focus while another overlay is open — the pill is
        // hidden then, and re-grabs when that overlay closes mid-segment.
        .onChange(of: model.skipSegment == nil) { _, isNil in
            skipFocused = !isNil && !showTracks && !showOffer
        }
        .onChange(of: showTracks) { _, open in
            if !open && !showOffer && model.skipSegment != nil {
                skipFocused = true
            }
        }
        .onChange(of: showOffer) { _, up in
            if up {
                offerFocus = .primary
            } else if model.skipSegment != nil {
                skipFocused = true
            }
        }
        #endif
        .onDisappear { model.shutdown() }
    }

    // MARK: - End-of-episode offer

    /**
     The card the player shows when an episode runs out and the next season
     is only a request away.

     Every button here dismisses the card and nothing else: the player stays
     exactly where it is, on the last frame, and the viewer leaves the way
     they always do. Same contract as the Android card.
     */
    private func offerCard(_ offer: NextSeasonOffer) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(offerSent ? "Requested — it'll appear once it downloads." : offer.title)
                .font(.headline)
                .foregroundStyle(.white)

            if !offerSent {
                Text(offer.body)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.75))
            }

            // Jellyseerr refusing is not an alert: the message lands in the
            // card and the buttons stay, so a retry is one press away
            if let offerNotice {
                // Red, like every other failure in this app. In body
                // colour it reads as more explanation and the viewer
                // walks away thinking the request went through.
                Text(offerNotice)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            HStack(spacing: 16) {
                if offerSent {
                    offerPrimaryButton("Close") { dismissOffer() }
                } else if offer.alreadyRequested {
                    offerPrimaryButton("OK") { dismissOffer() }
                } else {
                    offerPrimaryButton("Request season \(offer.seasonNumber)") {
                        requestNextSeason(offer)
                    }
                    .disabled(offerBusy)
                    offerSecondaryButton("Not now") { dismissOffer() }
                }
            }
            .padding(.top, 4)
        }
        #if os(tvOS)
        .padding(40)
        .frame(maxWidth: 900, alignment: .leading)
        #else
        .padding(22)
        .frame(maxWidth: 460, alignment: .leading)
        #endif
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        .padding(24)
    }

    // tvOS buttons stay unstyled so the system focus ring is the affordance;
    // anything drawn here would fight it (same call as the season pills).
    private func offerPrimaryButton(
        _ title: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                #if !os(tvOS)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(.white, in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(.black)
                #endif
        }
        #if os(tvOS)
        .focused($offerFocus, equals: .primary)
        #else
        .buttonStyle(.plain)
        #endif
    }

    private func offerSecondaryButton(
        _ title: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                #if !os(tvOS)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .foregroundStyle(.white)
                #endif
        }
        #if os(tvOS)
        .focused($offerFocus, equals: .secondary)
        #else
        .buttonStyle(.plain)
        #endif
    }

    private func dismissOffer() {
        showOffer = false
        offerNotice = nil
        // Sticky, like the Android twin: dismissed once means dismissed for
        // this playback. A card that comes back is the thing people learn
        // to hate.
        offerDismissed = true
    }

    private func requestNextSeason(_ offer: NextSeasonOffer) {
        guard let seerr = model.seerr else { return }
        offerBusy = true
        Task {
            // Kotlin List<Int> crosses as [KotlinInt]; one season, not the show
            let outcome = try? await seerr.requestSeasons(
                tmdbId: offer.seriesTmdbId,
                seasons: [KotlinInt(int: offer.seasonNumber)]
            )
            offerBusy = false
            switch outcome {
            case is RequestOutcome.Sent, is RequestOutcome.AlreadyRequested:
                // Already asked for is a success from here: the season is
                // coming either way, and saying so twice helps nobody
                offerNotice = nil
                offerSent = true
                #if os(tvOS)
                // The primary button was just replaced by "Close", and the
                // Focus Engine has to be handed the new one or the remote
                // goes dead. Assigning .primary here would do nothing: it
                // IS .primary already, so SwiftUI sees no change — and it
                // nils the binding itself when the old button disappears,
                // which happens after this line. Clear it, then re-assert
                // on the next tick, once the new button exists.
                offerFocus = nil
                Task { @MainActor in offerFocus = .primary }
                #endif
            case is RequestOutcome.NotSignedIn:
                offerNotice = "Sign in to Jellyseerr again in Settings"
            case let failure as RequestOutcome.Failed:
                offerNotice = failure.message
            default:
                offerNotice = "Could not reach Jellyseerr"
            }
        }
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

                    // Timing lives with the track it applies to, and only
                    // once one is actually on
                    if model.subtitleTracks.contains(where: \.selected) {
                        Section("Sync \(Self.delayLabel(model.subtitleDelay))") {
                            Button {
                                model.nudgeSubtitleDelay(by: -PlayerModel.subtitleDelayStep)
                            } label: {
                                Label("Earlier", systemImage: "gobackward")
                            }
                            Button {
                                model.nudgeSubtitleDelay(by: PlayerModel.subtitleDelayStep)
                            } label: {
                                Label("Later", systemImage: "goforward")
                            }
                            if model.subtitleDelay != 0 {
                                Button {
                                    model.resetSubtitleDelay()
                                } label: {
                                    Label("Reset", systemImage: "arrow.counterclockwise")
                                }
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

    /// Says which way it moved, not just the number.
    private static func delayLabel(_ delay: Double) -> String {
        delay == 0
            ? "(in sync)"
            : String(format: "(%+.2fs %@)", delay, delay > 0 ? "later" : "earlier")
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

#if os(tvOS)
/** Focusable audio/subtitle picker — swipe down on the remote to open. */
private struct TrackPanel: View {
    @ObservedObject var model: PlayerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            if model.audioTracks.count > 1 {
                Text("Audio").font(.headline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(model.audioTracks) { track in
                            Button(track.label) {
                                model.selectAudioTrack(id: track.id)
                            }
                            .foregroundStyle(track.selected ? .primary : .secondary)
                        }
                    }
                }
            }
            if !model.subtitleTracks.isEmpty {
                Text("Subtitles").font(.headline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        Button("Off") {
                            model.selectSubtitleTrack(id: nil)
                        }
                        .foregroundStyle(
                            model.subtitleTracks.contains(where: \.selected) ? .secondary : .primary
                        )
                        ForEach(model.subtitleTracks) { track in
                            Button(track.label) {
                                model.selectSubtitleTrack(id: track.id)
                            }
                            .foregroundStyle(track.selected ? .primary : .secondary)
                        }
                    }
                }

                // Only worth showing once a track is actually on
                if model.subtitleTracks.contains(where: \.selected) {
                    SubtitleDelayRow(model: model)
                }
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .padding(60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
    }
}

/**
 Nudges subtitle timing when a file's subtitles drift against its audio.

 Session-only: a resync belongs to one bad file, and carrying it into the
 next title would be a bug rather than a preference.
 */
private struct SubtitleDelayRow: View {
    @ObservedObject var model: PlayerModel

    var body: some View {
        HStack(spacing: 16) {
            Text("Sync").font(.headline)
            Button("−\(Self.stepLabel)") {
                model.nudgeSubtitleDelay(by: -PlayerModel.subtitleDelayStep)
            }
            Text(label)
                .font(.headline.monospacedDigit())
                .frame(minWidth: 120)
            Button("+\(Self.stepLabel)") {
                model.nudgeSubtitleDelay(by: PlayerModel.subtitleDelayStep)
            }
            if model.subtitleDelay != 0 {
                Button("Reset") { model.resetSubtitleDelay() }
            }
        }
    }

    private static let stepLabel = String(format: "%.2fs", PlayerModel.subtitleDelayStep)

    /// Says which way it moved, not just the number.
    private var label: String {
        let delay = model.subtitleDelay
        if delay == 0 { return "In sync" }
        return String(format: "%+.2fs %@", delay, delay > 0 ? "later" : "earlier")
    }
}
#endif
