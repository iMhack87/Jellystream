import Foundation
import Security
import Shared

/// Keychain-backed storage for the profiles blob (the shared module owns the
/// JSON format; this file only stores/retrieves the string securely).
enum SessionStore {
    private static let service = "dev.jellystream.session"
    // Legacy single-session entry, migrated into the profiles list on load
    private static let legacyAccount = "current"
    private static let legacyDefaultsKey = "dev.jellystream.session"
    private static let profilesAccount = "profiles"
    private static let profilesDefaultsKey = "dev.jellystream.profiles"

    static func loadProfiles() -> PersistedProfiles {
        if let json = keychainLoad(account: profilesAccount)
            ?? UserDefaults.standard.string(forKey: profilesDefaultsKey),
           let profiles = PersistedProfiles.companion.fromJson(json: json) {
            return profiles
        }
        // Pre-profiles installs stored a single session — adopt it as the
        // first profile so nobody has to log in again after updating
        if let json = keychainLoad(account: legacyAccount)
            ?? UserDefaults.standard.string(forKey: legacyDefaultsKey),
           let legacy = PersistedSession.companion.fromJson(json: json) {
            let migrated = PersistedProfiles(profiles: [legacy])
            saveProfiles(migrated)
            clearLegacy()
            return migrated
        }
        return PersistedProfiles(profiles: [])
    }

    static func saveProfiles(_ profiles: PersistedProfiles) {
        let json = profiles.toJson()
        if keychainSave(json, account: profilesAccount) {
            // A stale fallback copy must not outlive a successful Keychain save
            UserDefaults.standard.removeObject(forKey: profilesDefaultsKey)
        } else {
            // Unsigned simulator builds (CODE_SIGNING_ALLOWED=NO) get
            // errSecMissingEntitlement from the Keychain — dev-only fallback.
            // Signed device builds never take this path.
            #if targetEnvironment(simulator)
            UserDefaults.standard.set(json, forKey: profilesDefaultsKey)
            #endif
        }
    }

    private static func clearLegacy() {
        SecItemDelete(baseQuery(account: legacyAccount) as CFDictionary)
        UserDefaults.standard.removeObject(forKey: legacyDefaultsKey)
    }

    private static func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func keychainLoad(account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func keychainSave(_ json: String, account: String) -> Bool {
        guard let data = json.data(using: .utf8) else { return false }
        SecItemDelete(baseQuery(account: account) as CFDictionary)
        var attributes = baseQuery(account: account)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }
}
