import AppKit
import ApplicationServices

/// Watches whether macOS lets this app post input events (the Accessibility switch) and
/// reports changes as they happen.
///
/// macOS posts the distributed notification `com.apple.accessibility.api` whenever any
/// app's Accessibility switch changes, so the menu-bar icon can update the moment the
/// user flips it. A slow timer is the fallback in case a notification is missed.
///
/// `AXIsProcessTrusted()` follows the switch live. `CGPreflightPostEventAccess()` is
/// cached for the life of the process: after the switch is turned on it can keep saying
/// "no" (and after it's turned off, "yes"). So access counts as granted only when both
/// agree, and when the switch is on but the cached answer is still "no", [onStaleGrant]
/// is called so the app can restart and pick it up.
final class AccessWatcher {
    private(set) var granted = AXIsProcessTrusted() && CGPreflightPostEventAccess()
    var onChange: ((Bool) -> Void)?
    var onStaleGrant: (() -> Void)?
    private var observer: NSObjectProtocol?
    private var timer: Timer?

    func start() {
        observer = DistributedNotificationCenter.default().addObserver(
            forName: Notification.Name("com.apple.accessibility.api"), object: nil, queue: .main
        ) { [weak self] _ in
            // The notification can arrive just before the permission database is updated.
            for delay in [0.25, 1.0] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { self?.refresh(fromNotice: true) }
            }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.refresh(fromNotice: false)
        }
    }

    private func refresh(fromNotice: Bool) {
        let post = CGPreflightPostEventAccess()
        let ax = AXIsProcessTrusted()
        if fromNotice { Log.info("Accessibility changed: post events \(post), accessibility \(ax)") }
        let now = ax && post
        if now != granted {
            granted = now
            onChange?(now)
        }
        if ax && !post { onStaleGrant?() }
    }

    deinit {
        if let observer { DistributedNotificationCenter.default().removeObserver(observer) }
        timer?.invalidate()
    }
}
