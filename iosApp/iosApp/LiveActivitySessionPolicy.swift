import Foundation

/// Presentation ownership only. Dismissal never alters capture or PC ownership.
struct LiveActivitySessionPolicy {
    private(set) var sessionID: String?
    private(set) var startedAt: Date?
    private(set) var dismissed = false

    mutating func update(ownsPC: Bool, at date: Date = Date()) {
        if !ownsPC { sessionID = nil; startedAt = nil; dismissed = false }
        else if sessionID == nil { sessionID = UUID().uuidString; startedAt = date; dismissed = false }
    }
    mutating func dismiss() { dismissed = true }
    mutating func allowPresentation() { dismissed = false }
    func acceptsAction(for id: String) -> Bool { !dismissed && sessionID != nil && sessionID == id }
}
