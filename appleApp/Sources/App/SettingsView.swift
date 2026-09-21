import SwiftUI
import Shared

/// The active profile's settings, readable anywhere below the home screen.
/// The player needs them at `makeUIView` time, which rules out the usual
/// `.onAppear` assignment — an environment value is populated before the
/// view tree is built, so the ordering is never in question.
private struct AppSettingsKey: EnvironmentKey {
    // Kotlin default arguments don't bridge, hence the explicit factory
    static let defaultValue = AppSettings.companion.defaults()
}

extension EnvironmentValues {
    var appSettings: AppSettings {
        get { self[AppSettingsKey.self] }
        set { self[AppSettingsKey.self] = newValue }
    }
}

/// Account, playback and About — reached from the profile button (top-right
/// tab on TV, toolbar avatar on iPhone/iPad).
struct SettingsView: View {
    let api: JellyfinApi
    let session: UserSession
    let settings: AppSettings
    let seerr: JellyseerrApi
    let downloader: Downloader?
    let profile: PersistedSession?
    let onChange: (AppSettings) -> Void
    let onProfileChange: (PersistedSession) -> Void
    var onPlayOffline: (DownloadedItem) -> Void = { _ in }
    let onSwitchProfile: () -> Void
    let onLogout: () -> Void

    @State private var editingServer = false
    @State private var signingIn = false
    @State private var serverDraft = ""
    @State private var password = ""
    @State private var signInFailed = false
    @State private var signingInBusy = false

    /// Filled in from the server's public info — a ping, no auth needed.
    @State private var serverVersion: String?

    /// Every library the server offers, switched on or off for the home
    /// screen. Empty when the call fails: no section beats a broken one.
    @State private var libraries: [BaseItem] = []

    private var subtitleMode: Binding<SubtitleMode> {
        Binding(
            get: { settings.subtitleMode },
            set: { onChange(settings.withSubtitleMode(mode: $0)) }
        )
    }

    private var subtitleLanguage: Binding<String?> {
        Binding(
            get: {
                // Snap to the entry the picker actually holds: "fr" from an
                // older blob must still select the "fre" row
                SubtitleLanguages.shared.CHOICES
                    .first { LanguageCode.shared.matches(a: $0.code, b: settings.subtitleLanguage) }?
                    .code
            },
            set: { onChange(settings.withSubtitleLanguage(code: $0)) }
        )
    }

    private var subtitleScale: Binding<Double> {
        Binding(
            get: { Self.scales.min { abs($0 - settings.subtitleScale) < abs($1 - settings.subtitleScale) } ?? 1.0 },
            set: { onChange(settings.withSubtitleScale(value: $0)) }
        )
    }

    /// The sizes worth offering; anything finer is fiddling, not a setting.
    private static let scales: [Double] = [0.75, 1.0, 1.25, 1.5, 2.0]

    private static func scaleLabel(_ scale: Double) -> String {
        abs(scale - 1.0) < 0.01 ? Copy.shared.normal : "\(Int(scale * 100))%"
    }

    /// The system language, named as the picker would name it.
    private static var deviceLanguageLabel: String {
        let code = Locale.current.language.languageCode?.identifier(.alpha3)
        let label = SubtitleLanguages.shared.labelFor(code: code)
        return label == SubtitleLanguages.shared.CHOICES.first?.label
            ? (code?.uppercased() ?? "unknown")
            : label
    }

    private func signIn() async {
        guard let profile, let link = profile.jellyseerr else { return }
        signingInBusy = true
        seerr.configure(serverUrl: link.baseUrl, sessionCookie: nil)
        let cookie = try? await seerr.signIn(username: session.displayName, password: password)
        signingInBusy = false
        password = ""
        if let cookie {
            signInFailed = false
            onProfileChange(profile.withJellyseerrSession(cookie: cookie))
        } else {
            signInFailed = true
            signingIn = true
        }
    }

    private func showsLibrary(_ view: BaseItem) -> Binding<Bool> {
        Binding(
            get: { settings.showsLibrary(view: view) },
            set: { onChange(settings.withLibraryShown(view: view, shown: $0)) }
        )
    }

    private var alwaysTranscode: Binding<Bool> {
        Binding(
            get: { settings.alwaysTranscode },
            set: { onChange(settings.withAlwaysTranscode(value: $0)) }
        )
    }

    private var autoPlayNextEpisode: Binding<Bool> {
        Binding(
            get: { settings.autoPlayNextEpisode },
            set: { onChange(settings.withAutoPlayNextEpisode(value: $0)) }
        )
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 16) {
                    AvatarCircle(initial: session.initial, size: avatarSize)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.displayName)
                            .font(.headline)
                        Text(session.serverLabel)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)

                // "Who's watching?" is also the only path from a
                // single-profile install to adding a second account
                Button(Copy.shared.switchProfile, action: onSwitchProfile)
                Button(Copy.shared.logOut, role: .destructive, action: onLogout)
            } header: {
                Text(Copy.shared.account)
            }

            #if !os(tvOS)
            // Offline is a phone and tablet feature: a television sits on
            // the same network as the server and has nowhere to put 40 GB
            if let downloader, let profile {
                Section(Copy.shared.offline) {
                    NavigationLink(Copy.shared.downloads) {
                        DownloadsView(
                            downloader: downloader,
                            profileKey: profile.profileKey,
                            onPlay: onPlayOffline
                        )
                    }
                }
            }
            #endif

            Section {
                Button {
                    serverDraft = profile?.jellyseerr?.baseUrl ?? ""
                    editingServer = true
                } label: {
                    LabeledContent(
                        Copy.shared.jellyseerrServer,
                        value: profile?.jellyseerr?.baseUrl
                            .replacingOccurrences(of: "https://", with: "")
                            .replacingOccurrences(of: "http://", with: "") ?? Copy.shared.notSet
                    )
                }
                if let link = profile?.jellyseerr {
                    Button {
                        password = ""
                        signInFailed = false
                        signingIn = true
                    } label: {
                        LabeledContent(Copy.shared.account, value: link.isSignedIn ? Copy.shared.signedIn : Copy.shared.signIn)
                    }
                    NavigationLink(Copy.shared.browseAndRequest) {
                        RequestsView(seerr: seerr)
                    }
                }
            } header: {
                Text(Copy.shared.requests)
            } footer: {
                Text(Copy.shared.requestsFooter)
            }

            if !libraries.isEmpty {
                Section {
                    // Every library the server offers is listed, including
                    // the music and photo ones this player starts with
                    // switched off — hidden is a choice here, never a
                    // library the user can no longer find.
                    ForEach(libraries, id: \.id) { view in
                        Toggle(view.name ?? Copy.shared.library, isOn: showsLibrary(view))
                    }
                } header: {
                    Text(Copy.shared.homeScreen)
                }
            }

            Section {
                Picker(Copy.shared.whenToShow, selection: subtitleMode) {
                    ForEach(SubtitleMode.entries, id: \.self) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                Picker(Copy.shared.language, selection: subtitleLanguage) {
                    ForEach(SubtitleLanguages.shared.CHOICES, id: \.label) { choice in
                        Text(choice.label).tag(choice.code)
                    }
                }
                Picker(Copy.shared.size, selection: subtitleScale) {
                    ForEach(Self.scales, id: \.self) { scale in
                        Text(Self.scaleLabel(scale)).tag(scale)
                    }
                }
            } header: {
                Text(Copy.shared.subtitles)
            } footer: {
                Text(Copy.shared.subtitleHelpNamed(device: Self.deviceLanguageLabel))
            }

            Section {
                Toggle(Copy.shared.playNextAuto, isOn: autoPlayNextEpisode)
                Toggle(Copy.shared.alwaysTranscode, isOn: alwaysTranscode)
            } header: {
                Text(Copy.shared.playback)
            } footer: {
                Text(Copy.shared.playNextHelp + "\n\n" + Copy.shared.transcodeHelp)
            }

            Section {
                LabeledContent("Jellystream", value: JellyfinApi.companion.CLIENT_VERSION)
                LabeledContent(Copy.shared.server, value: session.serverLabel)
                if let serverVersion {
                    LabeledContent("Jellyfin", value: serverVersion)
                }
            } header: {
                Text(Copy.shared.about)
            }
        }
        .navigationTitle(Copy.shared.settings)
        .cinemaChrome()
        .alert(Copy.shared.jellyseerrServer, isPresented: $editingServer) {
            TextField("seerr.example.com", text: $serverDraft)
            Button(Copy.shared.save) {
                if let profile { onProfileChange(profile.withJellyseerrServer(url: serverDraft)) }
            }
            // Clearing the field is how a profile stops using Jellyseerr
            if profile?.jellyseerr != nil {
                Button(Copy.shared.remove, role: .destructive) {
                    if let profile { onProfileChange(profile.withJellyseerrServer(url: nil)) }
                }
            }
            Button(Copy.shared.cancel, role: .cancel) { }
        }
        .alert(Copy.shared.signInToJellyseerr, isPresented: $signingIn) {
            // Only the password is asked for: the username is the profile's
            // own, and the password goes to the network and nowhere else
            SecureField(Copy.shared.jellyfinPassword, text: $password)
            Button(Copy.shared.signIn) { Task { await signIn() } }
            Button(Copy.shared.cancel, role: .cancel) { }
        } message: {
            Text(
                signInFailed
                    ? Copy.shared.seerrRefused
                    : "\(session.displayName) on \(profile?.jellyseerr?.baseUrl ?? "")"
            )
        }
        .task {
            // Best effort: an unreachable server just leaves the row out
            serverVersion = try? await api
                .getPublicSystemInfo(serverUrl: session.baseUrl).version
            libraries = (try? await api.getUserViews()) ?? []
        }
    }

    #if os(tvOS)
    private var avatarSize: CGFloat { 80 }
    #else
    private var avatarSize: CGFloat { 52 }
    #endif
}

/// The gradient initial circle used by the profile picker, settings header
/// and the toolbar button — one look for "this account", everywhere.
struct AvatarCircle: View {
    let initial: String
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(
                    LinearGradient(
                        colors: [Color(white: 0.25), Color(white: 0.12)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Text(initial)
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        // Hairline edge: the gradient alone disappears into a dark
        // toolbar or backdrop
        .overlay(Circle().strokeBorder(.white.opacity(0.45), lineWidth: 1))
    }
}
