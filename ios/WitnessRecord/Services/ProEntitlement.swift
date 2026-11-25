import Foundation
import StoreKit

/// Owns the Witness Pro subscription: what the store offers, whether this
/// account currently has it, and the purchase/restore flows.
///
/// Entitlement is read from StoreKit's `currentEntitlements` rather than
/// cached locally, so a lapsed or refunded subscription stops unlocking the
/// Pro capture settings on its own without the app having to track expiry.
@MainActor
final class ProEntitlement: ObservableObject {
    static let shared = ProEntitlement()

    /// Must match the auto-renewable subscription created in App Store
    /// Connect. Nothing unlocks until that product exists and is approved.
    static let monthlyProductID = "com.witnessrecord.app.pro.monthly"
    /// A one-time, non-consumable purchase — a different StoreKit product
    /// type from the subscription above, not another tier within it. It has
    /// no expiration date and nothing to renew or cancel.
    static let lifetimeProductID = "com.witnessrecord.app.pro.onetime"

    /// The first identifier used for the one-time purchase. Its App Store
    /// Connect record became stuck in a state where its promotional image
    /// could not be removed, and Apple will not re-issue a product ID, so the
    /// product had to be recreated under a new one.
    ///
    /// It is never offered for sale and is deliberately absent from the
    /// product fetch. It stays here only so that a transaction against it
    /// still grants Pro. Nobody can have bought it, since it was never
    /// approved, but honouring it costs one comparison and the alternative is
    /// silently locking out anyone who somehow holds one.
    static let legacyLifetimeProductID = "com.witnessrecord.app.pro.lifetime"

    /// Every identifier that grants the one-time entitlement, current or past.
    static let lifetimeProductIDs = [lifetimeProductID, legacyLifetimeProductID]

    @Published private(set) var isPro = false
    /// True when the current entitlement came from the one-time purchase
    /// rather than the subscription — so the UI can stop offering to
    /// "manage" or "restore" a renewal that was never going to happen.
    @Published private(set) var isLifetime = false
    @Published private(set) var monthly: Product?
    @Published private(set) var lifetime: Product?
    @Published private(set) var isWorking = false
    /// Set when neither product could be reached, so the paywall can say so
    /// rather than showing an empty price.
    @Published private(set) var storeUnavailable = false

    private var updatesTask: Task<Void, Never>?

    private init() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "debugForcePro") { isPro = true }
        #endif
        // Catches renewals, refunds, and purchases made on another device.
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                if let transaction = Self.entitlingTransaction(from: update) {
                    await transaction.finish()
                }
                await self?.refreshEntitlement()
            }
        }
        Task {
            await refreshEntitlement()
            await loadProducts()
        }
    }

    deinit { updatesTask?.cancel() }

    /// What the last `loadProducts` actually got back, for the Diagnostics
    /// section on the Pro page.
    ///
    /// A product that App Store Connect has not approved, has not attached to
    /// the submitted version, or has left in "Missing Metadata" is simply
    /// absent from StoreKit's reply — it is not an error, and the paywall's
    /// only symptom is a button that isn't there. That is indistinguishable
    /// from a layout bug by looking, which is how an App Review rejection for
    /// "we cannot locate the In-App Purchases" ends up with nothing to go on.
    /// So the raw answer is recorded and shown.
    @Published private(set) var productDiagnostics = "Store: not checked yet"
    /// What StoreKit says the account is entitled to right now, and what the
    /// last purchase returned, so "I bought it and nothing unlocked" can be
    /// told apart: refused as unverified, already expired, revoked, or
    /// simply not listed.
    @Published private(set) var entitlementDiagnostics = "Entitlements: not checked yet"
    private var lastPurchaseNote = ""

    private static func describe(_ result: VerificationResult<Transaction>) -> String {
        let verified: String
        let transaction: Transaction
        switch result {
        case .verified(let t): verified = "verified"; transaction = t
        case .unverified(let t, _): verified = "UNVERIFIED"; transaction = t
        }
        let expiry = transaction.expirationDate.map { $0.formatted(date: .abbreviated, time: .standard) } ?? "none"
        let revoked = transaction.revocationDate != nil ? "REVOKED" : "not revoked"
        return "\(transaction.productID)\n  \(verified), expires: \(expiry), \(revoked)"
    }

    func loadProducts() async {
        do {
            let products = try await Product.products(
                for: [Self.monthlyProductID, Self.lifetimeProductID])
            monthly = products.first { $0.id == Self.monthlyProductID }
            lifetime = products.first { $0.id == Self.lifetimeProductID }
            // Either one missing is a real configuration problem worth
            // knowing about, but the paywall can still sell whichever one
            // did load — only silent when both are unreachable.
            storeUnavailable = products.isEmpty
            productDiagnostics = """
            Store: \(products.count) of 2 products returned
            \(Self.monthlyProductID): \(monthly.map { "OK \($0.displayPrice)" } ?? "NOT RETURNED")
            \(Self.lifetimeProductID): \(lifetime.map { "OK \($0.displayPrice)" } ?? "NOT RETURNED")
            """
            await EventLogger.shared.log(
                "storekit.products_loaded",
                detail: "monthly=\(monthly != nil) lifetime=\(lifetime != nil)")
        } catch {
            storeUnavailable = true
            productDiagnostics = "Store: request failed — \(error.localizedDescription)"
            await EventLogger.shared.log(
                "storekit.products_failed", detail: error.localizedDescription)
        }
    }

    /// Unwraps a StoreKit verification result.
    ///
    /// Release builds accept only Apple-signed transactions — anything else
    /// is exactly what receipt forgery looks like. Debug builds also accept
    /// unverified ones, because transactions minted from a local `.storekit`
    /// configuration are signed by Xcode's own test certificate and can come
    /// back unverified; without this, a test purchase visibly succeeds while
    /// the app refuses to unlock, which is indistinguishable from a bug.
    private static func entitlingTransaction(
        from result: VerificationResult<Transaction>
    ) -> Transaction? {
        switch result {
        case .verified(let transaction):
            return transaction
        case .unverified(let transaction, let error):
            #if DEBUG
            print("[ProEntitlement] accepting unverified transaction in DEBUG: \(error)")
            return transaction
            #else
            _ = error
            return nil
            #endif
        }
    }

    /// Whether a transaction currently entitles this account to Pro, from
    /// either product. The lifetime purchase has no `expirationDate` at all,
    /// so that check simply never applies to it.
    private static func entitles(_ transaction: Transaction) -> Bool {
        guard transaction.productID == Self.monthlyProductID
                || Self.lifetimeProductIDs.contains(transaction.productID),
              transaction.revocationDate == nil else { return false }
        if let expiry = transaction.expirationDate, expiry <= Date() { return false }
        return true
    }

    /// Re-reads the current entitlement from StoreKit.
    func refreshEntitlement() async {
        var entitled = false
        var lifetime = false
        var lines: [String] = []
        for await result in Transaction.currentEntitlements {
            lines.append(Self.describe(result))
            guard let transaction = Self.entitlingTransaction(from: result) else { continue }
            guard Self.entitles(transaction) else { continue }
            entitled = true
            if Self.lifetimeProductIDs.contains(transaction.productID) { lifetime = true }
        }
        // `currentEntitlements` has been seen coming back empty for a
        // purchase that StoreKit itself reports as valid (the Xcode test
        // store on a device does this). The latest transaction per product
        // is a second, equally Apple-signed source: same verification, same
        // expiry and revocation checks, so it can only ever agree with a
        // real entitlement, never invent one.
        if !entitled {
            let ids = [Self.monthlyProductID] + Self.lifetimeProductIDs
            for id in ids {
                guard let result = await Transaction.latest(for: id) else { continue }
                lines.append("latest: " + Self.describe(result))
                guard let transaction = Self.entitlingTransaction(from: result),
                      Self.entitles(transaction) else { continue }
                entitled = true
                if Self.lifetimeProductIDs.contains(transaction.productID) { lifetime = true }
            }
        }
        apply(entitled: entitled, lifetime: lifetime)
        let listing = lines.isEmpty ? "No current entitlements listed." : lines.joined(separator: "\n")
        entitlementDiagnostics = """
        Entitlements (now \(Date().formatted(date: .omitted, time: .standard))):
        \(listing)
        Result: \(entitled ? "entitled" : "NOT entitled")
        \(lastPurchaseNote)
        """
    }

    /// Single place that writes the gate, so the published flags and the
    /// mirror the capture pipeline reads can never disagree.
    private func apply(entitled: Bool, lifetime: Bool) {
        var entitled = entitled
        #if DEBUG
        // Simulator testing of Pro screens without a sandbox purchase.
        if UserDefaults.standard.bool(forKey: "debugForcePro") { entitled = true }
        #endif
        isPro = entitled
        isLifetime = entitled && lifetime
        // Mirrored for the capture pipeline, which reads the gate off the
        // main actor and can't await this actor-isolated state.
        UserDefaults.standard.set(entitled, forKey: AppSettings.proEntitledKey)
    }

    /// Returns true when the purchase completed and the entitlement is live.
    /// Takes the specific product so the caller — the paywall, offering both
    /// the subscription and the one-time purchase — decides which one the
    /// person tapped, rather than this type assuming the subscription.
    @discardableResult
    func purchase(_ product: Product) async -> Bool {
        guard !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }

        do {
            switch try await product.purchase() {
            case .success(let verification):
                lastPurchaseNote = "Last purchase: " + Self.describe(verification)
                guard let transaction = Self.entitlingTransaction(from: verification) else {
                    lastPurchaseNote += "\n  REFUSED: unverified transaction in a non-debug build"
                    await refreshEntitlement()
                    return false
                }
                await transaction.finish()
                // The completed purchase is itself authoritative. Re-reading
                // `currentEntitlements` alone is not enough: it can still be
                // catching up at this instant, and a purchase that visibly
                // succeeded would leave the app locked until something else
                // happened to refresh it.
                let isLifetimePurchase = Self.lifetimeProductIDs.contains(transaction.productID)
                if Self.entitles(transaction) { apply(entitled: true, lifetime: isLifetimePurchase) }
                await refreshEntitlement()
                // A refresh that lags must not undo what the purchase just
                // established; the listener below corrects it if it truly
                // isn't entitled.
                if !isPro, Self.entitles(transaction) {
                    apply(entitled: true, lifetime: isLifetimePurchase)
                }
                return isPro
            case .pending, .userCancelled:
                return false
            @unknown default:
                return false
            }
        } catch {
            return false
        }
    }

    /// Required by App Review: a way to recover an existing subscription on a
    /// new device or after a reinstall.
    func restore() async {
        guard !isWorking else { return }
        isWorking = true
        defer { isWorking = false }
        try? await AppStore.sync()
        await refreshEntitlement()
    }
}
