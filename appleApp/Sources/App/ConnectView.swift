import SwiftUI
import Shared

/// Owns the JellyfinApi instance: a @StateObject is created once per view
/// lifetime, unlike stored properties on the View struct which are
/// re-initialized on every state change.
@MainActor
final class ConnectModel: ObservableObject {
    enum Status {
        case idle
        case loading
        case success(String)
        case failure(String)
    }

    @Published var status: Status = .idle

    private let api = JellyfinApi(
        clientName: "Jellystream",
        clientVersion: "0.1.0",
        deviceName: UIDevice.current.name,
        deviceId: UUID().uuidString
    )

    var isLoading: Bool {
        if case .loading = status { return true }
        return false
    }

    func connect(serverUrl: String, username: String, password: String) {
        status = .loading
        Task {
            do {
                let server = try await api.resolveServer(rawUrl: serverUrl)
                let auth = try await api.authenticateByName(
                    serverUrl: server.baseUrl,
                    username: username,
                    password: password
                )
                let name = server.info.serverName ?? "Jellyfin"
                let version = server.info.version ?? "?"
                let user = auth.user?.name ?? username
                status = .success("Connected to \(name) (v\(version)) as \(user)")
            } catch {
                status = .failure(error.localizedDescription)
            }
        }
    }
}

struct ConnectView: View {
    @StateObject private var model = ConnectModel()

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
                    TextField("Username", text: $username)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $password)
                }

                Section {
                    Button("Connect") {
                        model.connect(
                            serverUrl: serverUrl,
                            username: username,
                            password: password
                        )
                    }
                    .disabled(model.isLoading || serverUrl.isEmpty)
                }

                switch model.status {
                case .idle:
                    EmptyView()
                case .loading:
                    ProgressView()
                case .success(let message):
                    Text(message).foregroundStyle(.green)
                case .failure(let message):
                    Text(message).foregroundStyle(.red)
                }
            }
            .navigationTitle("Jellystream")
        }
    }
}
