import Foundation

/// Localization helpers. User-facing strings in the package live in string catalogs under `Resources/`
/// (source language English; every key translated to es, pt, fr, de, it, nl):
/// - `Localizable.xcstrings`: models, services, errors, regions.
/// - `Widgets.xcstrings`: widget and shared chart views (`table: "Widgets"`).
/// In views use `Text("Key", tableName: "Widgets", bundle: .module)`; for `String` values use `L10n.string("Key", table: "Widgets")`.
public enum L10n {
    /// Languages the app ships. Keep in sync with `CFBundleLocalizations` in project.yml.
    public static let languages = ["en", "es", "pt", "fr", "de", "it", "nl"]

    public static func string(_ key: String.LocalizationValue, table: String? = nil) -> String {
        String(localized: key, table: table, bundle: .module)
    }

    /// The package resource bundle, for `Text(_:bundle:)` from other modules.
    public static var bundle: Bundle { .module }
}
