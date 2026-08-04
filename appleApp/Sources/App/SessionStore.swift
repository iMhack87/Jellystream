import Foundation
import Security
import Shared

/// Keychain-backed storage for the session blob (the shared module owns the
/// JSON format; this file only stores/retrieves the string securely).
enum SessionStore {
    private static let service = "dev.jellystream.session"
    private static let account = "current"
    private static let defaultsKey = "dev.jellystream.session"

    static func load() -> PersistedSession? {
        if let json = keychainLoad() ?? UserDefaults.standard.string(forKey: defaultsKey) {
            return PersistedSession.companion.fromJson(json: json)
        }
        return nil
    }

    static func save(_ persisted: PersistedSession) {
        let json = persisted.toJson()
        // Keychain first; unsigned dev builds (simulator, CODE_SIGNING_ALLOWED=NO)
        // get errSecMissingEntitlement — fall back to UserDefaults there
        if !keychainSave(json) {
            UserDefaults.standard.set(json, forKey: defaultsKey)
        }
    }

    static func clear() {
        SecItemDelete(baseQuery() as CFDictionary)
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func keychainLoad() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func keychainSave(_ json: String) -> Bool {
        guard let data = json.data(using: .utf8) else { return false }
        SecItemDelete(baseQuery() as CFDictionary)
        var attributes = baseQuery()
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }
}
