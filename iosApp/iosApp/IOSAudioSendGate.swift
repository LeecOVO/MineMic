import os

/// Bounded submissions belong to one UDP connection. Cancelled sends cannot
/// occupy slots or release reservations belonging to a later connection.
final class IOSAudioSendGate {
    private struct State {
        var generation: UInt64 = 0
        var inFlight = 0
    }
    private let state = OSAllocatedUnfairLock(initialState: State())

    func reserve() -> UInt64? {
        state.withLock { value in
            guard value.inFlight < 16 else { return nil }
            value.inFlight += 1
            return value.generation
        }
    }
    func isCurrent(_ generation: UInt64) -> Bool {
        state.withLock { $0.generation == generation }
    }
    func release(_ generation: UInt64) {
        state.withLock { value in
            if value.generation == generation && value.inFlight > 0 { value.inFlight -= 1 }
        }
    }
    func reset() {
        state.withLock { value in value.generation &+= 1; value.inFlight = 0 }
    }
}
