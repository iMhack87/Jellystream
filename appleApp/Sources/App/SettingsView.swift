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
    let profile: PersistedSession?
    let onChange: (AppSettings) -> Void
    let onProfileChange: (PersistedSession) -> Void
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
        abs(scale - 1.0) < 0.01 ? "Normal" : "\(Int(scale * 100))%"
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
                Button("Switch Profile", action: onSwitchProfile)
                Button("Log Out", role: .destructive, action: onLogout)
            } header: {
                Text("Account")
            }

            Section {
                Button {
                    serverDraft = profile?.jellyseerr?.baseUrl ?? ""
                    editingServer = true
                } label: {
                    LabeledContent(
                        "Jellyseerr server",
                        value: profile?.jellyseerr?.baseUrl
                            .replacingOccurrences(of: "https://", with: "")
                            .replacingOccurrences(of: "http://", with: "") ?? "Not set"
                    )
                }
                if let link = profile?.jellyseerr {
                    Button {
                        password = ""
                        signInFailed = false
                        signingIn = true
                    } label: {
                        LabeledContent("Account", value: link.isSignedIn ? "Signed in" : "Sign in")
                    }
                    NavigationLink("Browse and request") {
                        RequestsView(seerr: seerr)
                    }
                }
            } header: {
                Text("Requests")
            } footer: {
                Text(
                    "Requests are made with this profile's own Jellyfin account, so "
                    + "quotas and history stay yours. Only the session is kept — never "
                    + "the password."
                )
            }

            if !libraries.isEmpty {
                Section {
                    // Every library the server offers is listed, including
                    // the music and photo ones this player starts with
                    // switched off — hidden is a choice here, never a
                    // library the user can no longer find.
                    ForEach(libraries, id: \.id) { view in
                        Toggle(view.name ?? "Library", isOn: showsLibrary(view))
                    }
                } header: {
                    Text("Home Screen")
                }
            }

            Section {
                Picker("When to show", selection: subtitleMode) {
                    ForEach(SubtitleMode.entries, id: \.self) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                Picker("Language", selection: subtitleLanguage) {
                    ForEach(SubtitleLanguages.shared.CHOICES, id: \.label) { choice in
                        Text(choice.label).tag(choice.code)
                    }
                }
                Picker("Size", selection: subtitleScale) {
                    ForEach(Self.scales, id: \.self) { scale in
                        Text(Self.scaleLabel(scale)).tag(scale)
                    }
                }
            } header: {
                Text("Subtitles")
            } footer: {
                Text(
                    "Smart turns on full subtitles when the audio is not in your "
                    + "language, and only forced ones when it is. Device language "
                    + "follows the system: \(Self.deviceLanguageLabel)."
                )
            }

            Section {
                Toggle("Always Transcode", isOn: alwaysTranscode)
            } header: {
                Text("Playback")
            } footer: {
                Text(
                    "Direct Play sends the original file untouched — leave this off. "
                    + "Turn it on only if a title stutters or won't decode: the server "
                    + "will re-encode it, at the cost of CPU and quality."
                )
            }

            Section {
                LabeledContent("Jellystream", value: JellyfinApi.companion.CLIENT_VERSION)
                LabeledContent("Server", value: session.serverLabel)
                if let serverVersion {
                    LabeledContent("Jellyfin", value: serverVersion)
                }
            } header: {
                Text("About")
            }
        }
        .navigationTitle("Settings")
        .alert("Jellyseerr server", isPresented: $editingServer) {
            TextField("seerr.example.com", text: $serverDraft)
            Button("Save") {
                if let profile { onProfileChange(profile.withJellyseerrServer(url: serverDraft)) }
            }
            // Clearing the field is how a profile stops using Jellyseerr
            if profile?.jellyseerr != nil {
                Button("Remove", role: .destructive) {
                    if let profile { onProfileChange(profile.withJellyseerrServer(url: nil)) }
                }
            }
            Button("Cancel", role: .cancel) { }
        }
        .alert("Sign in to Jellyseerr", isPresented: $signingIn) {
            // Only the password is asked for: the username is the profile's
            // own, and the password goes to the network and nowhere else
            SecureField("Jellyfin password", text: $password)
            Button("Sign in") { Task { await signIn() } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text(
                signInFailed
                    ? "Jellyseerr refused those credentials."
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
