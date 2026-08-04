import SwiftUI
import Shared

struct ConnectView: View {
    private enum Status {
        case idle
        case loading
        case success(String)
        case failure(String)
    }

    @State private var serverUrl = ""
    @State private var username = ""
    @State private var password = ""
    @State private var status: Status = .idle

    private let api = JellyfinApi(
        clientName: "Jellystream",
        clientVersion: "0.1.0",
        deviceName: UIDevice.current.name,
        deviceId: UUID().uuidString
    )

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
                    Button("Connect", action: connect)
                        .disabled(isLoading || serverUrl.isEmpty)
                }

                switch status {
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

    private var isLoading: Bool {
        if case .loading = status { return true }
        return false
    }

    private func connect() {
        status = .loading
        Task {
            do {
                let info = try await api.getPublicSystemInfo(serverUrl: serverUrl)
                let auth = try await api.authenticateByName(
                    serverUrl: serverUrl,
                    username: username,
                    password: password
                )
                let server = info.serverName ?? "Jellyfin"
                let version = info.version ?? "?"
                let user = auth.user?.name ?? username
                status = .success("Connected to \(server) (v\(version)) as \(user)")
            } catch {
                status = .failure(error.localizedDescription)
            }
        }
    }
}
