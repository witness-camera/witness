import Foundation
import MessageUI

/// Drives the "let my emergency contacts know where I am" flow: builds one
/// message, then presents whichever compose sheet(s) the saved contacts can
/// actually receive it through — SMS, Mail, or SMS followed by Mail if
/// contacts exist for both.
///
/// Deliberately not silent. iOS has no API to send a text or an email
/// without someone tapping Send in the system's own compose screen — the
/// same constraint that shapes the camera/microphone Settings prompt
/// elsewhere in this app — so "ping" means "get the message ready and ask
/// you to send it," not a background action.
@MainActor
final class EmergencyPingCoordinator: ObservableObject {
    static let shared = EmergencyPingCoordinator()
    private init() {}

    enum Step: Identifiable {
        case sms(recipients: [String], body: String)
        case mail(recipients: [String], subject: String, body: String)

        var id: String {
            switch self {
            case .sms: return "sms"
            case .mail: return "mail"
            }
        }
    }

    @Published var activeStep: Step?
    /// Set when there is nobody to send this to, so the caller can explain
    /// that instead of opening a compose sheet with no recipients.
    @Published var noEligibleContacts = false

    /// Set when location access is off and the person needs to choose:
    /// turn it on in Settings, or send the message without it.
    @Published var locationDeniedPromptNeeded = false

    private var pendingMail: (recipients: [String], subject: String, body: String)?
    private var waitingRecipients: (sms: [String], mail: [String])?

    func start() {
        let contacts = EmergencyContact.load()
        let smsRecipients = contacts.filter(\.canReceiveSMS).compactMap(\.phone)
        let mailRecipients = contacts.filter(\.hasValidEmail).compactMap(\.email)

        guard !smsRecipients.isEmpty || !mailRecipients.isEmpty else {
            noEligibleContacts = true
            return
        }

        Task {
            let location = LocationService.shared
            // Never asked yet: ask now, with the system's own prompt, and
            // wait for the answer instead of sending a message that says
            // location isn't available.
            if location.authorizationStatus == .notDetermined {
                location.requestPermissions()
                await Self.wait(timeout: .seconds(30)) {
                    location.authorizationStatus == .notDetermined
                }
            }

            switch location.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways:
                location.requestOneShotLocation()
                // Up to a few seconds for a first fix — worth it for a
                // message that is useless without one, but bounded so a
                // poor signal can't hold the alert up indefinitely.
                await Self.wait(timeout: .seconds(6)) { location.lastLocation == nil }
                present(sms: smsRecipients, mail: mailRecipients)
            default:
                // Declined earlier: iOS won't show its prompt again, so the
                // choice is Settings, or going ahead without a location.
                waitingRecipients = (smsRecipients, mailRecipients)
                locationDeniedPromptNeeded = true
            }
        }
    }

    /// The "Send Without Location" answer to the denied-access prompt.
    func sendWithoutLocation() {
        guard let waiting = waitingRecipients else { return }
        waitingRecipients = nil
        present(sms: waiting.sms, mail: waiting.mail)
    }

    func cancelWaiting() { waitingRecipients = nil }

    private func present(sms smsRecipients: [String], mail mailRecipients: [String]) {
        let body = Self.messageBody()
        if !mailRecipients.isEmpty {
            pendingMail = (mailRecipients, String(localized: "I need help"), body)
        }
        if !smsRecipients.isEmpty, MFMessageComposeViewController.canSendText() {
            activeStep = .sms(recipients: smsRecipients, body: body)
        } else {
            // No SMS capability at all (Simulator, no cellular plan, or
            // every contact only had an email) — go straight to Mail.
            presentPendingMail()
        }
    }

    private static func wait(timeout: Duration, while condition: @escaping () -> Bool) async {
        let deadline = ContinuousClock.now + timeout
        while condition(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(200))
        }
    }

    /// Called once the SMS sheet is gone, however it closed — sent,
    /// cancelled, or failed — so a contact reachable only by email still
    /// gets the message.
    func smsStepFinished() {
        activeStep = nil
        presentPendingMail()
    }

    func mailStepFinished() {
        activeStep = nil
    }

    private func presentPendingMail() {
        guard let pending = pendingMail, MFMailComposeViewController.canSendMail() else {
            pendingMail = nil
            return
        }
        pendingMail = nil
        Task {
            // Lets the previous sheet's dismissal actually finish first —
            // presenting a new one in the same run-loop tick as a dismissal
            // is unreliable.
            try? await Task.sleep(for: .milliseconds(400))
            activeStep = .mail(recipients: pending.recipients, subject: pending.subject, body: pending.body)
        }
    }

    private static func messageBody() -> String {
        var lines = [String(localized: "I need help. This is an alert sent from Witness.")]
        if let location = LocationService.shared.lastLocation {
            let coordinate = location.coordinate
            let link = "https://maps.apple.com/?ll=\(coordinate.latitude),\(coordinate.longitude)"
            lines.append(String(localized: "My last known location:") + " " + link)
        } else {
            lines.append(String(localized: "Location isn't available right now."))
        }
        return lines.joined(separator: "\n\n")
    }
}
