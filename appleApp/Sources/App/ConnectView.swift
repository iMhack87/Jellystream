import SwiftUI
import Shared

/// Owns the JellyfinApi instance: a @StateObject is created once per view
/// lifetime, unlike stored properties on the View struct which are
/// re-initialized on every state change.
@MainActor
final class AppModel: ObservableObject {
    enum Status {
        case idle
        case loading
        case failure(String)
    }

    @Published var status: Status = .idle
    @Published var session: UserSession?
    @Published var quickConnectCode: String?
    /// Every account this install knows (shared module owns the format).
    @Published var profiles: [PersistedSession] = []
    /// True while the user is logging into an additional account from the
    /// profile picker — shows ConnectView with a way back.
    @Published var addingProfile = false

    /// Rebuilt on profile switch: Jellyfin ties sessions to DeviceId and
    /// every profile carries its own.
    @Published private(set) var api: JellyfinApi
    private var deviceId: String

    init() {
        let stored = SessionStore.loadProfiles()
        profiles = stored.profiles
        // A single profile enters directly (pre-profiles behavior); with
        // several, RootView shows the picker and `session` stays nil
        if stored.profiles.count == 1, let only = stored.profiles.first {
            deviceId = only.deviceId
            api = Self.makeApi(deviceId: only.deviceId)
            api.restoreSession(restored: only.session)
            session = only.session
        } else {
            deviceId = UUID().uuidString
            api = Self.makeApi(deviceId: deviceId)
        }
    }

    private static func makeApi(deviceId: String) -> JellyfinApi {
        JellyfinApi(
            clientName: "Jellystream",
            clientVersion: "0.1.0",
            deviceName: UIDevice.current.name,
            deviceId: deviceId
        )
    }

    var isLoading: Bool {
        if case .loading = status { return true }
        return false
    }

    func select(profile: PersistedSession) {
        deviceId = profile.deviceId
        api = Self.makeApi(deviceId: profile.deviceId)
        api.restoreSession(restored: profile.session)
        session = profile.session
    }

    /// New accounts get a fresh DeviceId — two users sharing one would
    /// revoke each other's tokens server-side.
    func startAddProfile() {
        deviceId = UUID().uuidString
        api = Self.makeApi(deviceId: deviceId)
        status = .idle
        quickConnectCode = nil
        addingProfile = true
    }

    func cancelAddProfile() {
        status = .idle
        quickConnectCode = nil
        addingProfile = false
    }

    /// Back to the picker, keeping the account — the only path from a
    /// single-profile install to a second account.
    func switchProfile() {
        session = nil
        addingProfile = false
    }

    private func adopt(session: UserSession) {
        let profile = PersistedSession(deviceId: deviceId, session: session)
        let updated = PersistedProfiles(profiles: profiles).withProfile(profile: profile)
        profiles = updated.profiles
        SessionStore.saveProfiles(updated)
        self.session = session
        addingProfile = false
    }

    /// Quick Connect: show a code, poll until the user approves it elsewhere.
    func startQuickConnect(serverUrl: String) {
        status = .loading
        quickConnectCode = nil
        Task {
            do {
                // KotlinPair bridges both components as optionals
                let started = try await api.initiateQuickConnect(rawUrl: serverUrl)
                guard let baseUrl = started.first as? String,
                      let initial = started.second as? QuickConnectState
                else {
                    status = .failure("Quick Connect is unavailable on this server")
                    return
                }
                quickConnectCode = initial.code
                var authenticated = initial.authenticated
                // Jellyfin codes expire (~5 min); stop polling rather than
                // hanging forever on a dead code
                let deadline = Date().addingTimeInterval(5 * 60)
                while !authenticated {
                    if Date() > deadline {
                        quickConnectCode = nil
                        status = .failure("Quick Connect code expired — try again")
                        return
                    }
                    try await Task.sleep(nanoseconds: 3_000_000_000)
                    if Task.isCancelled { return }
                    let state = try await api.getQuickConnectState(
                        baseUrl: baseUrl,
                        secret: initial.secret
                    )
                    authenticated = state.authenticated
                }
                let session = try await api.authenticateWithQuickConnect(
                    baseUrl: baseUrl,
                    secret: initial.secret
                )
                adopt(session: session)
                quickConnectCode = nil
                status = .idle
            } catch {
                quickConnectCode = nil
                status = .failure(error.localizedDescription)
            }
        }
    }

    /// Removes the active profile only; other accounts stay available.
    func logout() {
        // Best-effort server revocation; local state clears now
        Task { [api] in try? await api.logout() }
        if let current = session,
           let profile = profiles.first(where: {
               $0.session.baseUrl == current.baseUrl && $0.session.userId == current.userId
           }) {
            let updated = PersistedProfiles(profiles: profiles).withoutProfile(profile: profile)
            profiles = updated.profiles
            SessionStore.saveProfiles(updated)
        }
        session = nil
        addingProfile = false
    }

    func login(serverUrl: String, username: String, password: String) {
        status = .loading
        Task {
            do {
                let session = try await api.login(
                    rawUrl: serverUrl,
                    username: username,
                    password: password
                )
                adopt(session: session)
                status = .idle
            } catch {
                status = .failure(error.localizedDescription)
            }
        }
    }
}

struct RootView: View {
    @StateObject private var model = AppModel()

    var body: some View {
        if let session = model.session {
            HomeView(
                api: model.api,
                session: session,
                onLogout: { model.logout() },
                onSwitchProfile: { model.switchProfile() }
            )
        } else if model.profiles.isEmpty || model.addingProfile {
            ConnectView(model: model)
        } else {
            // Several known accounts and none active — ask who's watching
            ProfilePickerView(model: model)
        }
    }
}

struct ConnectView: View {
    @ObservedObject var model: AppModel

    @State private var serverUrl = ""
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Server URL", text: $serverUrl)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Username", text: $username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    SecureField("Password", text: $password)
                }

                Section {
                    Button("Connect") {
                        model.login(
                            serverUrl: serverUrl,
                            username: username,
                            password: password
                        )
                    }
                    .disabled(model.isLoading || serverUrl.isEmpty)

                    // No on-screen keyboard needed — ideal on Apple TV
                    Button("Use Quick Connect") {
                        model.startQuickConnect(serverUrl: serverUrl)
                    }
                    .disabled(model.isLoading || serverUrl.isEmpty)

                    // Adding from the picker must always offer a way back
                    if model.addingProfile {
                        Button("Back to profiles", role: .cancel) {
                            model.cancelAddProfile()
                        }
                    }
                }

                if let code = model.quickConnectCode {
                    Section("Quick Connect") {
                        Text(code)
                            .font(.system(.largeTitle, design: .rounded).bold())
                        Text("Enter this code in Jellyfin on your phone or browser")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                switch model.status {
                case .idle:
                    EmptyView()
                case .loading:
                    ProgressView()
                case .failure(let message):
                    Text(message).foregroundStyle(.red)
                }
            }
            .navigationTitle(model.addingProfile ? "Add Profile" : "Jellystream")
            #if os(tvOS)
            .onExitCommand {
                if model.addingProfile { model.cancelAddProfile() }
            }
            #endif
        }
    }
}
