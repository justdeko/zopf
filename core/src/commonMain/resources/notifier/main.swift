import AppKit
import Foundation
import UserNotifications

// zopf posts through this bundle because UNUserNotificationCenter raises
// NSInternalInconsistencyException in a process with no bundle identifier.
//
// With --respond the process stays alive until the notification is answered and prints the action id
// that was clicked, or "default" for the body itself; a dismissal or the timeout prints nothing.

func die(_ message: String, _ code: Int32) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

struct Options {
    var identifier = UUID().uuidString
    var thread = ""
    var actions: [(id: String, label: String)] = []
    var respond = false
    var timeout: Double = 600
    var withdraw: [String] = []
    var positional: [String] = []
}

func parse(_ argv: [String]) -> Options {
    var options = Options()
    var index = 0

    func value(_ flag: String) -> String {
        index += 1
        guard index < argv.count else { die("\(flag) needs a value", 2) }
        return argv[index]
    }

    while index < argv.count {
        switch argv[index] {
        case "--id":
            options.identifier = value("--id")
        case "--thread":
            options.thread = value("--thread")
        case "--respond":
            options.respond = true
        case "--timeout":
            let raw = value("--timeout")
            guard let seconds = Double(raw), seconds > 0 else { die("--timeout wants seconds, not \(raw)", 2) }
            options.timeout = seconds
        case "--withdraw":
            options.withdraw.append(value("--withdraw"))
        case "--action":
            let raw = value("--action")
            guard let split = raw.firstIndex(of: "=") else { die("--action wants id=Label, not \(raw)", 2) }
            let id = String(raw[raw.startIndex..<split])
            let label = String(raw[raw.index(after: split)...])
            guard !id.isEmpty, !label.isEmpty else { die("--action wants id=Label, not \(raw)", 2) }
            options.actions.append((id: id, label: label))
        default:
            options.positional.append(argv[index])
        }
        index += 1
    }
    return options
}

// .accessory keeps this one out of the Dock for as long as it lives
NSApplication.shared.setActivationPolicy(.accessory)

let options = parse(Array(CommandLine.arguments.dropFirst()))
let center = UNUserNotificationCenter.current()

if !options.withdraw.isEmpty {
    center.removeDeliveredNotifications(withIdentifiers: options.withdraw)
    // removeDelivered is asynchronous with no completion handler; exiting immediately can outrun it
    Thread.sleep(forTimeInterval: 0.2)
    exit(0)
}

guard options.positional.count >= 2 else {
    die("usage: zopf-notify [options] <body> <title> [subtitle] [sound]", 2)
}
let body = options.positional[0]
let title = options.positional[1]
let subtitle = options.positional.count > 2 ? options.positional[2] : ""
let sound = options.positional.count > 3 ? options.positional[3] : ""

let category = "zopf.\(options.identifier)"

final class Responder: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let chosen = response.actionIdentifier
        var answer: String?
        switch chosen {
        case UNNotificationDefaultActionIdentifier:
            answer = "default"
        case UNNotificationDismissActionIdentifier:
            answer = nil
        default:
            answer = chosen
        }
        if let answer = answer {
            print(answer)
        }
        completionHandler()
        exit(0)
    }
}

let responder = Responder()
center.delegate = responder

// Every step happens in a callback off the main run loop. Blocking it instead makes center.add
// report success for a notification the daemon never receives.
var delivered = false

center.requestAuthorization(options: [.alert, .sound]) { granted, error in
    if let error = error {
        die("authorization failed: \(error.localizedDescription)", 1)
    }
    guard granted else {
        die("not allowed to send notifications — System Settings > Notifications > zopf", 1)
    }

    if !options.actions.isEmpty {
        let actions = options.actions.map {
            UNNotificationAction(identifier: $0.id, title: $0.label, options: [.foreground])
        }
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: category, actions: actions, intentIdentifiers: [],
                options: [.customDismissAction])
        ])
    }

    let content = UNMutableNotificationContent()
    content.title = title
    content.body = body
    if !subtitle.isEmpty {
        content.subtitle = subtitle
    }
    if !options.thread.isEmpty {
        content.threadIdentifier = options.thread
    }
    if !options.actions.isEmpty {
        content.categoryIdentifier = category
    }
    if !sound.isEmpty {
        // osascript accepts Glass, this API requires Glass.aiff
        let named = sound.contains(".") ? sound : sound + ".aiff"
        content.sound = UNNotificationSound(named: UNNotificationSoundName(named))
    }

    let request = UNNotificationRequest(
        identifier: options.identifier, content: content, trigger: nil)
    center.add(request) { error in
        if let error = error {
            die("delivery failed: \(error.localizedDescription)", 1)
        }
        delivered = true
        guard options.respond else {
            // add calls back when the request is accepted, which is before the daemon has it
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { exit(0) }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + options.timeout) {
            center.removeDeliveredNotifications(withIdentifiers: [options.identifier])
            exit(0)
        }
    }
}

DispatchQueue.global().asyncAfter(deadline: .now() + 30) {
    if !delivered {
        die("timed out waiting for Notification Center", 1)
    }
}

NSApplication.shared.run()
