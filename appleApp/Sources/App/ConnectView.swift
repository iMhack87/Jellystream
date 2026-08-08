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

    /** A connection resolved to plain http, awaiting the user's go-ahead. */
    struct PendingInsecureConnection {
        let baseUrl: String
        let username: String
        let password: String
        let quickConnect: Bool
    }

    @Published var status: Status = .idle
    @Published var session: UserSession?
    @Published var quickConnectCode: String?
    /** Non-nil shows the "unencrypted connection" confirmation alert. */
    @Published var pendingInsecure: PendingInsecureConnection?
    /// Every account this install knows (shared module owns the format).
    @Published var profiles: [PersistedSession] = []
    /// True while the user is logging into an additional account from the
    /// profile picker — shows ConnectView with a way back.
    @Published var addingProfile = false

    /// The active profile's preferences. Settings are per account, so this
    /// is reloaded on every profile switch alongside the api.
    @Published private(set) var settings: AppSettings = AppSettings.companion.defaults()

    /// Rebuilt on profile switch: Jellyfin ties sessions to DeviceId and
    /// every profile carries its own.
    @Published private(set) var api: JellyfinApi

    /// The active profile as stored, which is where the Jellyseerr link
    /// lives — a session cookie belongs with the credentials, not with the
    /// settings blob.
    @Published private(set) var activeProfile: PersistedSession?

    /// Shared across screens so signing in once is enough.
    let seerr = JellyseerrApi()

    /// Rebuilt per profile: downloads are per account, like settings.
    @Published private(set) var downloader: Downloader?

    /// Rebuilt per profile too: two accounts sharing an iPad do not share
    /// a watchlist, and one signing out must not take the other's.
    @Published private(set) var watchlist: WatchlistStore?
    private var deviceId: String
    /// Every profile's settings, including the inactive ones.
    private var storedSettings: PersistedSettings = SettingsStore.load()

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
            settings = storedSettings.forProfile(profileKey: only.profileKey)
            activeProfile = only
            downloader = Downloader(api: api, profileKey: only.profileKey)
            watchlist = WatchlistStore(profileKey: only.profileKey)
            seerr.configure(
                serverUrl: only.jellyseerr?.baseUrl,
                sessionCookie: only.jellyseerr?.sessionCookie
            )
        } else {
            deviceId = UUID().uuidString
            api = Self.makeApi(deviceId: deviceId)
        }
        wireApi()
    }

    /// The server rejected our token: the session is dead and every call
    /// will 401. Drop the profile and land back on sign-in. The identity
    /// check ignores late 401s from a previous profile's api instance
    /// (fire-and-forget playback reports can outlive a profile switch).
    private func wireApi() {
        let wired = api
        wired.onUnauthorized = { [weak self, weak wired] in
            DispatchQueue.main.async {
                guard let self, let wired, self.api === wired else { return }
                self.handleSessionExpired()
            }
        }
    }

    private func handleSessionExpired() {
        guard let current = session else { return }
        if let profile = profiles.first(where: { $0.profileKey == current.profileKey }) {
            let updated = PersistedProfiles(profiles: profiles)
                .withoutProfile(profile: profile)
            profiles = updated.profiles
            SessionStore.saveProfiles(updated)
            dropProfileData(profileKey: profile.profileKey)
        }
        session = nil
        addingProfile = false
        status = .failure("Session expired — please sign in again")
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
        api.onUnauthorized = nil
        deviceId = profile.deviceId
        api = Self.makeApi(deviceId: profile.deviceId)
        api.restoreSession(restored: profile.session)
        wireApi()
        session = profile.session
        settings = storedSettings.forProfile(profileKey: profile.profileKey)
        activeProfile = profile
        downloader = Downloader(api: api, profileKey: profile.profileKey)
        watchlist = WatchlistStore(profileKey: profile.profileKey)
        seerr.configure(
            serverUrl: profile.jellyseerr?.baseUrl,
            sessionCookie: profile.jellyseerr?.sessionCookie
        )
    }

    /// Persists a change to the stored profile — today, its Jellyseerr link.
    func update(profile updated: PersistedSession) {
        activeProfile = updated
        let merged = PersistedProfiles(profiles: profiles).withProfile(profile: updated)
        profiles = merged.profiles
        SessionStore.saveProfiles(merged)
        seerr.configure(
            serverUrl: updated.jellyseerr?.baseUrl,
            sessionCookie: updated.jellyseerr?.sessionCookie
        )
    }

    /// Persists a settings change for the active profile.
    func update(settings newSettings: AppSettings) {
        settings = newSettings
        guard let key = session?.profileKey else { return }
        storedSettings = storedSettings.withSettings(profileKey: key, settings: newSettings)
        SettingsStore.save(storedSettings)
    }

    /// Forgets a signed-out account's preferences — nothing should survive
    /// an account the install no longer knows.
    private func dropSettings(profileKey: String) {
        storedSettings = storedSettings.withoutProfile(profileKey: profileKey)
        SettingsStore.save(storedSettings)
        settings = AppSettings.companion.defaults()
    }

    /// Everything this install keeps for one account. Both ways out — the
    /// deliberate log out and the 401 that kills the session — go through
    /// here, or one of them leaves a watchlist behind for whoever signs in
    /// with that account next.
    private func dropProfileData(profileKey: String) {
        dropSettings(profileKey: profileKey)
        WatchlistStore.drop(profileKey: profileKey)
        ArrivalStore.drop(profileKey: profileKey)
        watchlist = nil
    }

    /// New accounts get a fresh DeviceId — two users sharing one would
    /// revoke each other's tokens server-side.
    func startAddProfile() {
        api.onUnauthorized = nil
        deviceId = UUID().uuidString
        api = Self.makeApi(deviceId: deviceId)
        wireApi()
        status = .idle
        quickConnectCode = nil
        addingProfile = true
        // The new account starts from defaults, not the last profile's
        settings = AppSettings.companion.defaults()
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
        // Kotlin default arguments do not cross the bridge, so a new
        // profile has to say out loud that it has no Jellyseerr yet
        let profile = PersistedSession(deviceId: deviceId, session: session, jellyseerr: nil)
        let updated = PersistedProfiles(profiles: profiles).withProfile(profile: profile)
        profiles = updated.profiles
        SessionStore.saveProfiles(updated)
        activeProfile = profile
        self.session = session
        // Signing back into a known account restores its preferences
        settings = storedSettings.forProfile(profileKey: profile.profileKey)
        watchlist = WatchlistStore(profileKey: profile.profileKey)
        // And its Jellyseerr — which is a credential, not a preference.
        // Without this the new account inherits the previous one's server
        // AND its session cookie, so the arrival poll and every request
        // made from search go out as somebody else.
        seerr.configure(
            serverUrl: profile.jellyseerr?.baseUrl,
            sessionCookie: profile.jellyseerr?.sessionCookie
        )
        addingProfile = false
    }

    /// Quick Connect: show a code, poll until the user approves it elsewhere.
    func startQuickConnect(serverUrl: String) {
        status = .loading
        quickConnectCode = nil
        Task {
            do {
                // Same unencrypted-connection gate as password login: the
                // token would come back (and streams flow) in clear text
                let server = try await api.resolveServer(rawUrl: serverUrl)
                if JellyfinApi.companion.isInsecureDowngrade(
                    rawInput: serverUrl,
                    resolvedBaseUrl: server.baseUrl
                ) {
                    pendingInsecure = PendingInsecureConnection(
                        baseUrl: server.baseUrl,
                        username: "",
                        password: "",
                        quickConnect: true
                    )
                    status = .idle
                    return
                }
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
           let profile = profiles.first(where: { $0.profileKey == current.profileKey }) {
            let updated = PersistedProfiles(profiles: profiles).withoutProfile(profile: profile)
            profiles = updated.profiles
            SessionStore.saveProfiles(updated)
            dropProfileData(profileKey: profile.profileKey)
        }
        session = nil
        addingProfile = false
    }

    func login(serverUrl: String, username: String, password: String) {
        status = .loading
        Task {
            do {
                // Resolve BEFORE sending credentials: a scheme-less input
                // that only answers over plain http needs the user's
                // explicit go-ahead first
                let server = try await api.resolveServer(rawUrl: serverUrl)
                if JellyfinApi.companion.isInsecureDowngrade(
                    rawInput: serverUrl,
                    resolvedBaseUrl: server.baseUrl
                ) {
                    pendingInsecure = PendingInsecureConnection(
                        baseUrl: server.baseUrl,
                        username: username,
                        password: password,
                        quickConnect: false
                    )
                    status = .idle
                    return
                }
                try await performLogin(
                    baseUrl: server.baseUrl,
                    username: username,
                    password: password
                )
            } catch {
                status = .failure(error.localizedDescription)
            }
        }
    }

    private func performLogin(baseUrl: String, username: String, password: String) async throws {
        let session = try await api.login(
            rawUrl: baseUrl,
            username: username,
            password: password
        )
        adopt(session: session)
        status = .idle
    }

    /// The user accepted the unencrypted connection — resume with the
    /// resolved http URL (explicit scheme, no second downgrade).
    func confirmInsecureConnection() {
        guard let pending = pendingInsecure else { return }
        pendingInsecure = nil
        if pending.quickConnect {
            startQuickConnect(serverUrl: pending.baseUrl)
        } else {
            status = .loading
            Task {
                do {
                    try await performLogin(
                        baseUrl: pending.baseUrl,
                        username: pending.username,
                        password: pending.password
                    )
                } catch {
                    status = .failure(error.localizedDescription)
                }
            }
        }
    }

    func cancelInsecureConnection() {
        pendingInsecure = nil
        status = .idle
    }
}

struct RootView: View {
    @StateObject private var model = AppModel()

    var body: some View {
        Group {
            if let session = model.session {
                HomeView(
                    api: model.api,
                    session: session,
                    settings: model.settings,
                    seerr: model.seerr,
                    downloader: model.downloader,
                    watchlist: model.watchlist,
                    profile: model.activeProfile,
                    onSettingsChange: { model.update(settings: $0) },
                    onProfileChange: { model.update(profile: $0) },
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
        // The notice has to show during playback, and the player is a
        // fullScreenCover — a UIKit modal above this whole hierarchy. Its
        // own window is the only placement that survives that.
        .onAppear { ArrivalToastWindow.shared.install() }
        .task(id: model.session?.profileKey) { await announceArrivals() }
    }

    /**
     The app-wide poll behind the arrival notice.

     Here rather than on the home screen because the home screen is not
     on screen during playback, which is exactly when something landing
     is worth saying. One minute is slow enough to be free and fast
     enough that "it arrived" still feels like news.
     */
    private func announceArrivals() async {
        guard let profileKey = model.session?.profileKey else { return }
        let seerr = model.seerr
        // Both of these outlive the signed-in screen so a notice can paint
        // over the player. Emptying them here is what stops one account's
        // titles being announced to the next, and its requests turning up
        // on their home screen.
        RequestFeed.shared.reset()
        ArrivalToastWindow.shared.reset()
        while !Task.isCancelled {
            if seerr.isConfigured {
                // nil is UNREACHABLE, not "no requests": treating it as an
                // empty list would forget everything already announced and
                // then announce it all again on the next successful tick
                if let requests = try? await seerr.myRequestsDetailed(limit: 30) {
                    let stored = ArrivalStore.load(profileKey: profileKey)
                    let announced = stored ?? AnnouncedArrivals(requestIds: [])
                    // No stored blob = this profile's first look: whatever
                    // is already available did not arrive while anyone was
                    // watching, so it is recorded silently
                    let landed = Arrivals.shared.landed(
                        requests: requests,
                        announced: announced,
                        firstLook: stored == nil
                    )
                    ArrivalStore.save(
                        Arrivals.shared.seen(requests: requests, announced: announced),
                        profileKey: profileKey
                    )
                    for arrival in landed {
                        ArrivalToastWindow.shared.show(message: arrival.message)
                    }
                    // The home row reads this instead of fetching its own
                    // copy: one poll, one truth
                    RequestFeed.shared.publish(requests)
                }
            }
            try? await Task.sleep(nanoseconds: 60_000_000_000)
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
            // Shown BEFORE any credential leaves the device: the server
            // only answered over plain http for a scheme-less input
            .alert(
                "Unencrypted connection",
                isPresented: Binding(
                    get: { model.pendingInsecure != nil },
                    set: { if !$0 { model.cancelInsecureConnection() } }
                )
            ) {
                Button("Connect Anyway", role: .destructive) {
                    model.confirmInsecureConnection()
                }
                Button("Cancel", role: .cancel) {
                    model.cancelInsecureConnection()
                }
            } message: {
                Text("This server is only reachable over plain HTTP. Your password and streams would travel unencrypted on the network.")
            }
            #if os(tvOS)
            .onExitCommand {
                if model.addingProfile { model.cancelAddProfile() }
            }
            #endif
        }
    }
}
