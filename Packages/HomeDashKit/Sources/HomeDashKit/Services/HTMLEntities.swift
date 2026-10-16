import Foundation

/// Minimal HTML entity decoder (no WebKit). Decodes in a single pass, so `&amp;quot;` becomes `&quot;`.
public enum HTMLEntities {
    private static let named: [String: String] = [
        "quot": "\"", "amp": "&", "lt": "<", "gt": ">", "apos": "'", "nbsp": "\u{00A0}",
    ]

    /// Decodes named (`&quot;`), decimal (`&#39;`) and hex (`&#x27;`) entities. Unknown entities are left untouched.
    public static func unescape(_ string: String) -> String {
        guard string.contains("&") else { return string }
        var result = ""
        result.reserveCapacity(string.count)
        var index = string.startIndex
        while index < string.endIndex {
            let ch = string[index]
            if ch == "&", let semi = string[index...].prefix(12).firstIndex(of: ";"),
               let decoded = decode(String(string[string.index(after: index)..<semi])) {
                result += decoded
                index = string.index(after: semi)
            } else {
                result.append(ch)
                index = string.index(after: index)
            }
        }
        return result
    }

    private static func decode(_ body: String) -> String? {
        if body.hasPrefix("#") {
            let digits = body.dropFirst()
            let value: UInt32?
            if digits.hasPrefix("x") || digits.hasPrefix("X") {
                value = UInt32(digits.dropFirst(), radix: 16)
            } else {
                value = UInt32(digits, radix: 10)
            }
            guard let value, let scalar = Unicode.Scalar(value) else { return nil }
            return String(Character(scalar))
        }
        return named[body]
    }
}
