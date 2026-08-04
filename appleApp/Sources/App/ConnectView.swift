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

    let api = JellyfinApi(
        clientName: "Jellystream",
        clientVersion: "0.1.0",
        deviceName: UIDevice.current.name,
        deviceId: UUID().uuidString
    )

    var isLoading: Bool {
        if case .loading = status { return true }
        return false
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
            HomeView(api: model.api, session: session)
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
