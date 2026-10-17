import Foundation
import Security

/// Where the kiosk link and the SolarEdge/PVOutput API key are kept (they give access to the plant's data).
public protocol SecretStore: Sendable {
    /// Nil when nothing is stored under `name`.
    func secret(_ name: String) -> String?
    /// Stores `value` (empty removes it). False when the store cannot be used (e.g. a build without the keychain
    /// group): the caller then keeps the value where it was.
    @discardableResult
    func setSecret(_ value: String, for name: String) -> Bool
}

/// Generic passwords in the keychain, shared with the widget extension through the App Group access group.
/// Readable after the first unlock (widgets refresh in the background); never synced to iCloud.
public struct KeychainSecretStore: SecretStore {
    public let service: String
    public let accessGroup: String?

    public init(service: String = "com.ideatic.sunwatt", accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ name: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
            kSecAttrSynchronizable as String: false,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }

    public func secret(_ name: String) -> String? {
        var query = query(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func setSecret(_ value: String, for name: String) -> Bool {
        let query = query(name)
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            let status = SecItemDelete(query as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let data = Data(value.utf8)
        let update: [String: Any] = [kSecValueData as String: data]
        var status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            status = SecItemAdd(add as CFDictionary, nil)
        }
        return status == errSecSuccess
    }
}
