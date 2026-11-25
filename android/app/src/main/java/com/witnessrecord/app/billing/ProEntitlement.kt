package com.witnessrecord.app.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.EventLogger
import com.witnessrecord.app.service.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Witness Pro entitlement on Google Play: what the store offers,
 * whether this account currently has it, and the purchase/restore flows.
 *
 * The Android counterpart of the iOS build's StoreKit-backed
 * `ProEntitlement`. Entitlement is re-read from Play Billing's own purchase
 * records on every launch and after every purchase-flow result, rather than
 * trusted from a local flag alone, so a lapsed or refunded subscription stops
 * unlocking the Pro capture settings on its own.
 *
 * Requires two products created in Play Console - see [MONTHLY_PRODUCT_ID]
 * and [LIFETIME_PRODUCT_ID]. Nothing unlocks until they exist, are active,
 * and this build is signed with a key registered to an app with billing
 * permission (a release-signed build installed via Play, or a license tester
 * on a debug build added in Play Console's testing track).
 */
object ProEntitlement {

    /**
     * Must match the subscription product ID created in Play Console exactly
     * - Play Console -> your app -> Monetize -> Products -> Subscriptions.
     * Suggested price to match iOS: $1.99/month (the iOS product is
     * `com.witnessrecord.app.pro.monthly` at the same price; Play product IDs
     * commonly use this lowercase/underscore convention rather than iOS's
     * reverse-DNS style, but any ID works as long as it matches here).
     */
    const val MONTHLY_PRODUCT_ID = "witness_pro_monthly"

    /**
     * A one-time purchase - Play Console -> Monetize -> Products -> One-time
     * products. A different product *type* from the subscription above, not
     * another tier within it: it has no expiry, nothing renews, and there is
     * nothing to cancel. $29.99, matching the iOS product
     * `com.witnessrecord.app.pro.onetime`.
     *
     * Unlike a consumable, this must never be consumed - consuming it would
     * make it buyable again and revoke the entitlement it granted. It is only
     * acknowledged.
     */
    const val LIFETIME_PRODUCT_ID = "witness_pro_lifetime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isPro = MutableStateFlow(Prefs.getBool(AppSettings.proEntitledKey, false))
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    /**
     * True when the entitlement came from the one-time purchase rather than
     * the subscription - so the UI can stop offering to "manage" a renewal
     * that was never going to happen.
     */
    private val _isLifetime = MutableStateFlow(false)
    val isLifetime: StateFlow<Boolean> = _isLifetime.asStateFlow()

    private val _monthly = MutableStateFlow<ProductDetails?>(null)
    val monthly: StateFlow<ProductDetails?> = _monthly.asStateFlow()

    private val _lifetime = MutableStateFlow<ProductDetails?>(null)
    val lifetime: StateFlow<ProductDetails?> = _lifetime.asStateFlow()

    /**
     * What the last [loadProducts] actually got back, for the Diagnostics
     * section on the Pro page.
     *
     * A product Play has not activated, or that belongs to a track this build
     * was not installed from, is simply absent from the reply - it is not an
     * error, and the paywall's only symptom is a button that isn't there.
     * That is indistinguishable from a layout bug by looking, which is how a
     * store-review rejection for "we could not find the in-app purchases"
     * ends up with nothing to go on. So the raw answer is recorded and shown.
     */
    private val _productDiagnostics = MutableStateFlow("Store: not checked yet")
    val productDiagnostics: StateFlow<String> = _productDiagnostics.asStateFlow()

    /**
     * What Play says this account owns right now, and what the last purchase
     * attempt returned, so "I bought it and nothing unlocked" can be told
     * apart: still pending, refused, cancelled, or simply not listed.
     */
    private val _entitlementDiagnostics = MutableStateFlow("Entitlements: not checked yet")
    val entitlementDiagnostics: StateFlow<String> = _entitlementDiagnostics.asStateFlow()
    private var lastPurchaseNote = ""

    private fun Purchase.describe(): String {
        val state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> "PURCHASED"
            Purchase.PurchaseState.PENDING -> "PENDING"
            else -> "UNSPECIFIED"
        }
        val renewal = if (products.any { it == MONTHLY_PRODUCT_ID }) {
            ", auto-renewing: ${if (isAutoRenewing) "yes" else "no"}"
        } else ""
        return "${products.joinToString()}\n  $state, acknowledged: ${if (isAcknowledged) "yes" else "no"}$renewal"
    }

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    /** Set when the store can't be reached, so the paywall can say so rather than showing an empty price. */
    private val _storeUnavailable = MutableStateFlow(false)
    val storeUnavailable: StateFlow<Boolean> = _storeUnavailable.asStateFlow()

    @Volatile private var client: BillingClient? = null
    @Volatile private var connecting = false

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases?.let { list ->
                    lastPurchaseNote = "Last purchase: " + list.joinToString("; ") { it.describe().replace("\n  ", " ") }
                    scope.launch { list.forEach { handlePurchase(it) } }
                }
            BillingClient.BillingResponseCode.USER_CANCELED -> lastPurchaseNote = "Last purchase: cancelled by the user"
            else -> {
                lastPurchaseNote = "Last purchase: refused - ${result.debugMessage}"
                EventLogger.log("billing.purchase_flow_error", detail = result.debugMessage)
            }
        }
    }

    /** Call once, from [com.witnessrecord.app.WitnessApplication]. */
    fun initialize(context: Context) {
        if (client != null || connecting) return
        connecting = true

        val pendingPurchases = PendingPurchasesParams.newBuilder()
            .enableOneTimeProducts()
            .build()
        val billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(pendingPurchases)
            .build()
        client = billingClient

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _storeUnavailable.value = true
                    EventLogger.log("billing.setup_failed", detail = result.debugMessage)
                    return
                }
                scope.launch {
                    loadProducts()
                    refreshEntitlement()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnected lazily on the next explicit call (refreshEntitlement/purchase),
                // rather than retried here on a timer this object doesn't run.
            }
        })
    }

    /**
     * Fetches both products. Play requires a separate query per product type,
     * so this is two round trips rather than one: subscriptions and one-time
     * products cannot be asked for together.
     */
    suspend fun loadProducts() {
        val billingClient = client ?: return

        suspend fun query(productID: String, type: String): ProductDetails? {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productID)
                .setProductType(type)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                EventLogger.log(
                    "billing.query_products_failed",
                    detail = "$productID: ${result.billingResult.debugMessage}"
                )
                return null
            }
            return result.productDetailsList?.firstOrNull { it.productId == productID }
        }

        val monthly = query(MONTHLY_PRODUCT_ID, BillingClient.ProductType.SUBS)
        val lifetime = query(LIFETIME_PRODUCT_ID, BillingClient.ProductType.INAPP)
        _monthly.value = monthly
        _lifetime.value = lifetime

        // Either one missing is a real configuration problem worth knowing
        // about, but the paywall can still sell whichever one did load - only
        // silent when both are unreachable.
        _storeUnavailable.value = monthly == null && lifetime == null

        val returned = listOfNotNull(monthly, lifetime).size
        _productDiagnostics.value = buildString {
            appendLine("Store: $returned of 2 products returned")
            appendLine("$MONTHLY_PRODUCT_ID: ${monthly.describe()}")
            append("$LIFETIME_PRODUCT_ID: ${lifetime.describe()}")
        }
        EventLogger.log(
            "billing.products_loaded",
            detail = "monthly=${monthly != null} lifetime=${lifetime != null}"
        )
    }

    /** The price Play returned, or a plain statement that it returned nothing. */
    private fun ProductDetails?.describe(): String {
        val details = this ?: return "NOT RETURNED"
        val price = details.oneTimePurchaseOfferDetails?.formattedPrice
            ?: details.subscriptionOfferDetails
                ?.firstOrNull()
                ?.pricingPhases
                ?.pricingPhaseList
                ?.lastOrNull()
                ?.formattedPrice
        return "OK ${price ?: "(no price)"}"
    }

    /**
     * Re-reads the current entitlement from Play Billing's own purchase
     * records - both product types, since either grants Pro and a subscriber
     * who later bought the one-time product (or the reverse) holds both.
     */
    suspend fun refreshEntitlement() {
        val billingClient = client ?: return

        suspend fun owned(type: String): List<Purchase>? {
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            val result = billingClient.queryPurchasesAsync(params)
            // Null, not empty: a query that failed says nothing about what
            // this account owns, and treating it as "owns nothing" would
            // revoke Pro over a bad connection.
            return if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.purchasesList
            } else {
                null
            }
        }

        val subscriptions = owned(BillingClient.ProductType.SUBS)
        val oneTime = owned(BillingClient.ProductType.INAPP)
        if (subscriptions == null && oneTime == null) {
            _entitlementDiagnostics.value = "Entitlements: Play could not be asked just now."
            return
        }

        val purchases = subscriptions.orEmpty() + oneTime.orEmpty()
        val live = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val lifetime = live.any { it.products.contains(LIFETIME_PRODUCT_ID) }
        val subscribed = live.any { it.products.contains(MONTHLY_PRODUCT_ID) }
        apply(entitled = lifetime || subscribed, lifetime = lifetime)

        val listing = if (purchases.isEmpty()) "No purchases listed." else purchases.joinToString("\n") { it.describe() }
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _entitlementDiagnostics.value = buildString {
            appendLine("Entitlements (now $clock):")
            appendLine(listing)
            append("Result: ${if (lifetime || subscribed) "entitled" else "NOT entitled"}")
            if (lastPurchaseNote.isNotEmpty()) append("\n$lastPurchaseNote")
        }

        // Required by Play within 3 days of purchase or it is automatically
        // refunded - acknowledging here covers a purchase made while the app
        // was killed before it could acknowledge on its own.
        //
        // Acknowledged, never consumed: consuming the one-time product would
        // make it buyable again and revoke the entitlement it just granted.
        for (purchase in live) {
            if (!purchase.isAcknowledged) acknowledge(purchase)
        }
    }

    /** Single place that writes the gate, so the published flow and the capture pipeline's mirror can never disagree. */
    private fun apply(entitled: Boolean, lifetime: Boolean) {
        _isPro.value = entitled
        _isLifetime.value = entitled && lifetime
        Prefs.setBool(AppSettings.proEntitledKey, entitled)
    }

    /**
     * Launches Play's purchase sheet. Unlike the iOS build's `purchase()`,
     * this cannot return whether the purchase succeeded: Play Billing reports
     * the outcome asynchronously to [purchasesUpdatedListener], not as the
     * result of launching the flow. Callers observe [isPro] instead - see
     * `PaywallScreen`, which dismisses itself when it flips to true.
     */
    fun launchPurchase(activity: Activity): Boolean =
        launch(activity, _monthly.value)

    /** The one-time purchase. Same sheet, different product. */
    fun launchLifetimePurchase(activity: Activity): Boolean =
        launch(activity, _lifetime.value)

    private fun launch(activity: Activity, product: ProductDetails?): Boolean {
        val billingClient = client ?: return false
        if (product == null) return false

        val details = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        // Subscriptions must name which offer is being bought; one-time
        // products have no offers to choose between, and passing a token for
        // one is rejected.
        if (product.productType == BillingClient.ProductType.SUBS) {
            val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: return false
            details.setOfferToken(offerToken)
        }

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(details.build()))
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val lifetime = purchase.products.contains(LIFETIME_PRODUCT_ID)
        if (!lifetime && !purchase.products.contains(MONTHLY_PRODUCT_ID)) return
        if (!purchase.isAcknowledged) acknowledge(purchase)
        apply(entitled = true, lifetime = lifetime)
    }

    private suspend fun acknowledge(purchase: Purchase) {
        val billingClient = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        val result = billingClient.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            EventLogger.log("billing.acknowledge_failed", detail = result.debugMessage)
        }
    }

    /**
     * Required by Play Store review as a way to recover an existing
     * subscription on a new device or after a reinstall - unlike iOS's
     * `AppStore.sync()`, Play purchases are already tied to the signed-in
     * Google account, so this is the same re-query as [refreshEntitlement].
     */
    suspend fun restore() {
        _isWorking.value = true
        try {
            refreshEntitlement()
        } finally {
            _isWorking.value = false
        }
    }

    fun openSubscriptionManagement(context: Context) {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions?sku=$MONTHLY_PRODUCT_ID&package=${context.packageName}"
        )
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
