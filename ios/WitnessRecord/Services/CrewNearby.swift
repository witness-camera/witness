import Combine
import Foundation
import MultipeerConnectivity
import UIKit

/// Finds the crew's other phones nearby, over Wi-Fi or Bluetooth with no
/// internet needed, and does two things with each one it meets: swaps what
/// both know about the crew, and measures this phone's clock against theirs.
///
/// Clocks are compared the NTP way: send our time, the peer answers with its
/// own, and the peer's clock is taken to have read that at the midpoint of
/// the round trip. Of a burst of exchanges, the quickest wins, because the
/// shorter the trip the less room for the midpoint to be wrong. Offsets chain
/// back to the organizer, whose clock is the crew's reference, so two members
/// who never met the organizer still end up on the same timeline.
///
/// Only phones advertising the same crew are connected, and only while the
/// crew screen is open: nothing is broadcast during a recording.
@MainActor
final class CrewNearby: NSObject, ObservableObject {
    static let shared = CrewNearby()

    @Published private(set) var connectedNames: [String] = []
    @Published private(set) var running = false

    private static let serviceType = "witness-crew"
    private static let pingsPerPeer = 12

    /// Anonymous, and new each time the screen opens. Since iOS 16 every
    /// phone reports its name to apps as "iPhone", so the device name told
    /// peers apart from nobody; it would also have broadcast an owner's name
    /// to anyone nearby looking. Who a peer is comes from its hello instead.
    private var peerID = MCPeerID(displayName: CrewNearby.randomToken())
    /// Decides which of two phones sends the invitation: the lower one.
    /// Comparing anything not identical on both phones (the device names are
    /// all "iPhone"; object hashes differ per process) can leave both waiting
    /// for the other, and a peer is only reported found once.
    private var nonce = CrewNearby.randomToken()
    private var session: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?
    private var crewID: UUID?
    private var memberOfPeer: [MCPeerID: UUID] = [:]
    /// What was last sent, so a change is passed on once and an unchanged
    /// crew isn't echoed back and forth between phones.
    private var lastShared: Crew?
    private var crewChanges: AnyCancellable?

    private static func randomToken() -> String {
        String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))
    }

    private enum Message: Codable {
        case crew(Crew)
        /// Which member a phone is. Ignored by 1.1.11, which can't decode it.
        case hello(memberID: UUID)
        case ping(sent: Double)
        /// The peer's clock reading, plus its own offset to the organizer and
        /// how sure it is of that, so the answer can be chained.
        case pong(sent: Double, peerReceived: Double, peerReplied: Double,
                  peerOffset: Double, peerUncertainty: Double)
    }

    func start(crewID: UUID) {
        guard !running || self.crewID != crewID else { return }
        stop()
        self.crewID = crewID
        peerID = MCPeerID(displayName: Self.randomToken())
        nonce = Self.randomToken()
        let session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self
        self.session = session
        crewChanges = CrewStore.shared.$crews
            .debounce(for: .milliseconds(300), scheduler: RunLoop.main)
            .sink { [weak self] _ in self?.shareIfChanged() }

        let info = ["crew": crewID.uuidString, "n": nonce]
        let advertiser = MCNearbyServiceAdvertiser(peer: peerID, discoveryInfo: info, serviceType: Self.serviceType)
        advertiser.delegate = self
        advertiser.startAdvertisingPeer()
        self.advertiser = advertiser

        let browser = MCNearbyServiceBrowser(peer: peerID, serviceType: Self.serviceType)
        browser.delegate = self
        browser.startBrowsingForPeers()
        self.browser = browser
        running = true
    }

    func stop() {
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()
        advertiser = nil
        browser = nil
        session = nil
        crewID = nil
        crewChanges = nil
        lastShared = nil
        memberOfPeer = [:]
        connectedNames = []
        running = false
    }

    // MARK: - Exchange

    private func greet(_ peer: MCPeerID) {
        guard let crewID, let crew = CrewStore.shared.crew(crewID) else { return }
        if let me = CrewStore.shared.myMemberID(in: crewID) { send(.hello(memberID: me), to: peer) }
        send(.crew(crew), to: peer)
        lastShared = crew
        // The organizer is the reference and never needs measuring.
        guard CrewStore.shared.myMemberID(in: crewID) != crew.organizerID else { return }
        Task {
            for _ in 0..<Self.pingsPerPeer {
                send(.ping(sent: Date().timeIntervalSince1970), to: peer)
                try? await Task.sleep(for: .milliseconds(60))
            }
        }
    }

    private func receiveCrew(_ crew: Crew) {
        guard crew.id == crewID else { return }
        CrewStore.shared.receive(crew)
        refreshNames()
    }

    /// Passes on anything new — someone typing their name, a member learned
    /// from another phone, a recording that just stopped — to every phone
    /// connected now, not only to those that connect later.
    private func shareIfChanged() {
        guard let crewID, let crew = CrewStore.shared.crew(crewID), crew != lastShared,
              let session, !session.connectedPeers.isEmpty,
              let data = try? JSONEncoder().encode(Message.crew(crew)) else { return }
        try? session.send(data, toPeers: session.connectedPeers, with: .reliable)
        lastShared = crew
        refreshNames()
    }

    /// NTP's arithmetic: the time the peer spent before answering is taken
    /// out of the round trip, and the peer's own offset to the organizer is
    /// added on top.
    private func measure(sent: Double, received: Double, peerReceived: Double, peerReplied: Double,
                         peerOffset: Double, peerUncertainty: Double) {
        guard let crewID else { return }
        let delay = (received - sent) - (peerReplied - peerReceived)
        guard delay >= 0, delay < 1 else { return }
        let offset = ((peerReceived - sent) + (peerReplied - received)) / 2
        CrewStore.shared.recordClock(offset: offset + peerOffset,
                                     uncertainty: delay / 2 + peerUncertainty,
                                     for: crewID)
    }

    private func send(_ message: Message, to peer: MCPeerID) {
        guard let session, let data = try? JSONEncoder().encode(message) else { return }
        try? session.send(data, toPeers: [peer], with: .reliable)
    }

    private func refreshNames() {
        let members = crewID.flatMap { CrewStore.shared.crew($0) }?.members ?? []
        connectedNames = (session?.connectedPeers ?? []).map { peer in
            memberOfPeer[peer].flatMap { id in members.first { $0.id == id }?.name }
                ?? String(localized: "Crew phone")
        }.sorted()
    }
}

extension CrewNearby: MCSessionDelegate {
    nonisolated func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        Task { @MainActor in
            refreshNames()
            if state == .connected { greet(peerID) }
        }
    }

    nonisolated func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        // Clock readings are taken here, on arrival, not after the hop to the
        // main actor, whose queueing delay would land in the measurement.
        let now = Date().timeIntervalSince1970
        guard let message = try? JSONDecoder().decode(Message.self, from: data) else { return }
        Task { @MainActor in
            switch message {
            case .crew(let crew):
                receiveCrew(crew)
            case .hello(let memberID):
                memberOfPeer[peerID] = memberID
                refreshNames()
            case .ping(let sent):
                // Only a phone that knows where it stands can be measured against.
                guard let crewID, let clock = CrewStore.shared.clock(for: crewID),
                      let u = clock.uncertainty else { return }
                send(.pong(sent: sent, peerReceived: now, peerReplied: Date().timeIntervalSince1970,
                           peerOffset: clock.offset, peerUncertainty: u), to: peerID)
            case .pong(let sent, let peerReceived, let peerReplied, let peerOffset, let peerUncertainty):
                measure(sent: sent, received: now, peerReceived: peerReceived, peerReplied: peerReplied,
                        peerOffset: peerOffset, peerUncertainty: peerUncertainty)
            }
        }
    }

    nonisolated func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    nonisolated func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    nonisolated func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension CrewNearby: MCNearbyServiceAdvertiserDelegate {
    nonisolated func advertiser(_ advertiser: MCNearbyServiceAdvertiser,
                                didReceiveInvitationFromPeer peerID: MCPeerID,
                                withContext context: Data?,
                                invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        Task { @MainActor in
            // The inviter must name our crew; anyone else is turned away.
            let named = context.flatMap { String(data: $0, encoding: .utf8) }
            guard let crewID, named == crewID.uuidString, let session else {
                invitationHandler(false, nil)
                return
            }
            invitationHandler(true, session)
        }
    }
}

extension CrewNearby: MCNearbyServiceBrowserDelegate {
    nonisolated func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID,
                             withDiscoveryInfo info: [String: String]?) {
        Task { @MainActor in
            guard let crewID, info?["crew"] == crewID.uuidString, let session,
                  !session.connectedPeers.contains(peerID) else { return }
            // One side invites, so two phones don't invite each other at once
            // and end up with two half-open sessions. Both phones see both
            // tokens, so exactly one of them goes. A 1.1.11 phone sends no
            // token and only ever invites a name that sorts after its own
            // "iPhone", which a token never does, so this side goes.
            let invites = info?["n"].map { nonce < $0 } ?? (self.peerID.displayName < peerID.displayName)
            guard invites else { return }
            browser.invitePeer(peerID, to: session, withContext: Data(crewID.uuidString.utf8), timeout: 15)
        }
    }

    nonisolated func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        Task { @MainActor in
            memberOfPeer[peerID] = nil
            refreshNames()
        }
    }
}
