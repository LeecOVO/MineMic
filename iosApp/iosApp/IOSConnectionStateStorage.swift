import Foundation
import CryptoKit
import Security

/// A local ciphertext file is removed with app data and excluded from backup.
/// Only its encryption key lives in the non-synchronizing device-only keychain.
final class IOSConnectionStateStorage {
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: (Bundle.main.bundleIdentifier ?? "MineMic") + ".connection-recovery.v1",
         kSecAttrAccount as String: "encryption-key",
         kSecAttrSynchronizable as String: false]
    }
    private func location() throws -> URL {
        var folder = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true)
            .appendingPathComponent("MineMicConnection", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
        return folder.appendingPathComponent("recovery-v1")
    }
    private func key(create: Bool) -> SymmetricKey? {
        var request = query; request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data, data.count == 32 { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound, create else { return nil }
        let fresh = SymmetricKey(size: .bits256)
        var entry = query
        entry[kSecValueData as String] = fresh.withUnsafeBytes { Data($0) }
        entry[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(entry as CFDictionary, nil)
        if added == errSecSuccess { return fresh }
        return added == errSecDuplicateItem ? key(create: false) : nil
    }
    func load() -> String? {
        guard let url = try? location(),
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 4096,
              let data = try? Data(contentsOf: url), let secret = key(create: false),
              let box = try? AES.GCM.SealedBox(combined: data),
              let plain = try? AES.GCM.open(box, using: secret), plain.count <= 2048 else { return nil }
        return String(data: plain, encoding: .utf8)
    }
    func save(_ state: String?) {
        guard let url = try? location() else { return }
        guard let state else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        guard let plain = state.data(using: .utf8), plain.count <= 2048, let secret = key(create: true),
              let sealed = try? AES.GCM.seal(plain, using: secret), let data = sealed.combined else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
