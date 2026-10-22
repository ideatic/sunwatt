import Foundation

/// Localization helpers. User-facing strings in the package live in string catalogs under `Resources/`
/// (source language English; every key translated to es, pt, fr, de, it, nl):
/// - `Core.xcstrings`: models, services, errors, regions (`table: "Core"`, the default of `string(_:table:)`).
///   Not named `Localizable`: Xcode puts every string literal it cannot map to a table in `Localizable`, without
///   translations. Keys are `"extractionState" : "manual"`, so Xcode does not mark them stale or rewrite them.
/// - `Widgets.xcstrings`: widget and shared chart views (`table: "Widgets"`).
/// In views use `Text("Key", tableName: "Widgets", bundle: .module)`; for `String` values use `L10n.string("Key", table: "Widgets")`.
public enum L10n {
    /// Languages the app ships. Keep in sync with `CFBundleLocalizations` in project.yml.
    public static let languages = ["en", "es", "pt", "fr", "de", "it", "nl"]

    public static func string(_ key: String.LocalizationValue, table: String = "Core") -> String {
        String(localized: key, table: table, bundle: .module)
    }

    /// The package resource bundle, for `Text(_:bundle:)` from other modules.
    public static var bundle: Bundle { .module }
}
