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

    let api: JellyfinApi
    private let deviceId: String

    init() {
        let persisted = SessionStore.load()
        // Jellyfin ties sessions to DeviceId — reuse the stored one so the
        // server sees the same device across launches
        let deviceId = persisted?.deviceId ?? UUID().uuidString
        self.deviceId = deviceId
        api = JellyfinApi(
            clientName: "Jellystream",
            clientVersion: "0.1.0",
            deviceName: UIDevice.current.name,
            deviceId: deviceId
        )
        if let persisted {
            api.restoreSession(restored: persisted.session)
            session = persisted.session
        }
    }

    var isLoading: Bool {
        if case .loading = status { return true }
        return false
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
                while !authenticated {
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
                SessionStore.save(PersistedSession(deviceId: deviceId, session: session))
                self.session = session
                quickConnectCode = nil
                status = .idle
            } catch {
                quickConnectCode = nil
                status = .failure(error.localizedDescription)
            }
        }
    }

    func logout() {
        // Best-effort server revocation; local state clears now
        Task { [api] in try? await api.logout() }
        SessionStore.clear()
        session = nil
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
                SessionStore.save(PersistedSession(deviceId: deviceId, session: session))
                self.session = session
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
            HomeView(api: model.api, session: session, onLogout: { model.logout() })
        } else {
            ConnectView(model: model)
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
            .navigationTitle("Jellystream")
        }
    }
}
