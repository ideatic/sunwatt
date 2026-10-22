#if os(iOS) || os(macOS)
import Foundation
import Observation
import SwiftUI

/// The text of the kiosk link field, kept apart from the saved settings.
/// Saving writes the App Group, pushes to the watch and reloads the widgets, so it must not run per keystroke.
/// The text is committed (once, only when it differs from what is already committed):
/// - when the user submits, leaves the field or the view goes away (`commitNow`),
/// - after the user pastes (`paste`),
/// - `debounce` after the last keystroke, when the text is a valid kiosk link.
/// Invalid text and the empty field wait for one of the first two events. The validation message reads `text`.
@MainActor @Observable
final class KioskURLDraft {
    static let defaultDebounce: Duration = .milliseconds(800)

    /// What the field shows.
    private(set) var text: String
    /// Last value passed to `commit` (or the initial saved value).
    @ObservationIgnored private(set) var committed: String
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private let commit: @MainActor (String) -> Void
    /// The debounced commit waiting to run; nil when none is scheduled.
    @ObservationIgnored private(set) var pending: Task<Void, Never>?

    /// `sleep` is injectable for tests.
    init(
        saved: String,
        debounce: Duration = KioskURLDraft.defaultDebounce,
        sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
        commit: @escaping @MainActor (String) -> Void
    ) {
        self.text = saved
        self.committed = Self.normalized(saved)
        self.debounce = debounce
        self.sleep = sleep
        self.commit = commit
    }

    var inputState: KioskInputState { KioskInputState(text) }

    var binding: Binding<String> {
        Binding(get: { self.text }, set: { self.update($0) })
    }

    /// The user typed or edited. Restarts the debounce when the text is a valid link.
    func update(_ newText: String) {
        guard newText != text else { return }
        text = newText
        pending?.cancel()
        pending = nil
        guard KioskInputState(newText).isValid, Self.normalized(newText) != committed else { return }
        let sleep = sleep, delay = debounce
        pending = Task { [weak self] in
            do { try await sleep(delay) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.pending = nil
            self.commitNow()
        }
    }

    /// The user pasted a link: it replaces the text and is saved at once.
    func paste(_ pasted: String) {
        pending?.cancel()
        pending = nil
        text = Self.normalized(pasted)
        commitNow()
    }

    /// Saves the text now (submit, focus loss, view disappearing). No-op when it is already saved.
    func commitNow() {
        pending?.cancel()
        pending = nil
        let value = Self.normalized(text)
        guard value != committed else { return }
        committed = value
        commit(value)
    }

    private static func normalized(_ string: String) -> String {
        string.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

extension KioskInputState {
    var isValid: Bool {
        if case .valid = self { return true }
        return false
    }
}
#endif
