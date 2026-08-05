import Foundation
import Shared

/// App-private storage for the settings blob (the shared module owns the JSON
/// format; this file only reads and writes the string).
///
/// UserDefaults, not the Keychain: preferences are not secrets, and unlike
/// the session blob they must survive on unsigned simulator builds without a
/// fallback dance.
enum SettingsStore {
    private static let key = "dev.jellystream.settings"

    static func load() -> PersistedSettings {
        guard let json = UserDefaults.standard.string(forKey: key),
              let settings = PersistedSettings.companion.fromJson(json: json)
        else {
            // No settings yet, or a blob we can't read — defaults, never a
            // failure the user has to recover from
            return PersistedSettings.companion.empty()
        }
        return settings
    }

    static func save(_ settings: PersistedSettings) {
        UserDefaults.standard.set(settings.toJson(), forKey: key)
    }
}
