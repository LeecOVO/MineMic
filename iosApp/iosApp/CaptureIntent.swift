/// User intent is independent of temporary audio hardware availability.
struct CaptureIntent {
    private(set) var requested = false
    private(set) var interrupted = false
    private(set) var requiresUserAction = false
    mutating func start() { requested = true; interrupted = false; requiresUserAction = false }
    mutating func stop() { requested = false; interrupted = false; requiresUserAction = false }
    mutating func interruptionBegan() { if requested { interrupted = true } }
    mutating func interruptionEnded(mayResume: Bool) -> Bool {
        interrupted = false
        if !mayResume && requested { requiresUserAction = true }
        return requested && !requiresUserAction
    }
    mutating func foreground() -> Bool {
        // iOS does not guarantee an ended notification. Try once on foreground;
        // setActive/start still validates actual availability with the OS.
        guard requested && !requiresUserAction else { return false }
        interrupted = false
        return true
    }
    mutating func mediaServicesReset() { if requested { requiresUserAction = true }; interrupted = false }
}
