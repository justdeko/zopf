import AppKit
import Foundation
import UserNotifications

func die(_ message: String, _ code: Int32) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

let args = Array(CommandLine.arguments.dropFirst())
guard args.count >= 2 else {
    die("usage: zopf-notify <body> <title> [subtitle] [sound]", 2)
}
let body = args[0]
let title = args[1]
let subtitle = args.count > 2 ? args[2] : ""
let sound = args.count > 3 ? args[3] : ""

// UNUserNotificationCenter.current() refuses a process that is not a registered application
// .accessory keeps this one out of the Dock for the half-second it lives
NSApplication.shared.setActivationPolicy(.accessory)

let center = UNUserNotificationCenter.current()
let done = DispatchSemaphore(value: 0)
var failure: String?

func finish(_ message: String?) {
    failure = message
    done.signal()
}

center.requestAuthorization(options: [.alert, .sound]) { granted, error in
    if let error = error {
        return finish("authorization failed: \(error.localizedDescription)")
    }
    guard granted else {
        return finish("not allowed to send notifications — System Settings > Notifications > zopf")
    }

    let content = UNMutableNotificationContent()
    content.title = title
    content.body = body
    if !subtitle.isEmpty {
        content.subtitle = subtitle
    }
    if !sound.isEmpty {
        // osascript accepts Glass, this API requires Glass.aiff
        let named = sound.contains(".") ? sound : sound + ".aiff"
        content.sound = UNNotificationSound(named: UNNotificationSoundName(named))
    }

    let request = UNNotificationRequest(
        identifier: UUID().uuidString, content: content, trigger: nil)
    center.add(request) { error in
        finish(error.map { "delivery failed: \($0.localizedDescription)" })
    }
}

if done.wait(timeout: .now() + 30) == .timedOut {
    die("timed out waiting for Notification Center", 1)
}
if let failure = failure {
    die(failure, 1)
}
exit(0)
