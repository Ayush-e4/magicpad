import CoreGraphics
import Foundation

let usage = """
    magicpad — use an Android phone as a Mac trackpad (USB, no root)

    usage:
      magicpad [run] [--debug] [--no-grab]   start (waits for the phone)
      magicpad app [--debug]                 Wi-Fi menu-bar app (what MagicPad.app runs)
      magicpad install                       run in the background at login
      magicpad uninstall                     remove the background agent
      magicpad config                        create/show the config file path
      magicpad probe <gesture>               fire one system gesture to test it:
                                             spaces-left, spaces-right, mission,
                                             expose, apps, desktop, smartzoom

    --debug    print every touch frame
    --no-grab  share the touchscreen with Android (phone UI stays usable)
    """

var args = Array(CommandLine.arguments.dropFirst())

// Launched as MagicPad.app (Finder, login item): run the Wi-Fi menu-bar app.
if Bundle.main.bundleURL.pathExtension == "app" && args.allSatisfy({ $0.hasPrefix("-psn") }) {
    MenuBarApp.run()
}
let command = args.first.flatMap { $0.hasPrefix("-") ? nil : $0 } ?? "run"
if args.first == command { args.removeFirst() }

switch command {
case "run":
    Log.debug = args.contains("--debug")
    let runner = Runner(cfg: Config.load(), grab: !args.contains("--no-grab"))
    runner.start()
    dispatchMain()

case "app":
    Log.debug = args.contains("--debug")
    MenuBarApp.run()

case "install":
    LaunchAgent.install()

case "uninstall":
    LaunchAgent.uninstall()

case "config":
    print(Config.writeDefaultIfMissing())

case "probe":
    // Plays a full dock gesture (began → changed… → ended) without the phone.
    let sys = SystemGestures()
    let at = CGEvent(source: nil)?.location ?? .zero
    let name = args.first ?? ""
    func play(_ kind: DockSwipe, _ target: Double) {
        sys.dockSwipe(kind, progress: 0, phase: .began)
        for i in 1...20 {
            sys.dockSwipe(kind, progress: target * Double(i) / 20, phase: .changed)
            usleep(8000)
        }
        sys.dockSwipe(kind, progress: target, phase: .ended, velocity: target * 4)
    }
    switch name {
    case "spaces-left": play(.horizontal, -1)
    case "spaces-right": play(.horizontal, 1)
    case "mission": Dock.perform(.missionControl)
    case "expose": Dock.perform(.appExpose)
    case "apps": Dock.perform(.apps)
    case "desktop": Dock.perform(.showDesktop)
    case "smartzoom": sys.smartZoom(at: at)
    default: print(usage); exit(1)
    }
    usleep(100_000)

default:
    print(usage)
    exit(command == "help" || command == "--help" ? 0 : 1)
}
