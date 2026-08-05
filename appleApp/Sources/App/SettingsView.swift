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
    let onChange: (AppSettings) -> Void
    let onSwitchProfile: () -> Void
    let onLogout: () -> Void

    /// Filled in from the server's public info — a ping, no auth needed.
    @State private var serverVersion: String?

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
        .task {
            // Best effort: an unreachable server just leaves the row out
            serverVersion = try? await api
                .getPublicSystemInfo(serverUrl: session.baseUrl).version
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
