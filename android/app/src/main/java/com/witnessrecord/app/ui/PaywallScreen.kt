package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.launch

/**
 * Witness Pro paywall, styled like the onboarding and support pages.
 *
 * Carries what Play policy expects on the page itself: the subscription's
 * name, length and price, the auto-renew disclosure, links to the Terms of
 * Use and Privacy Policy, and a restore path.
 */
@Composable
fun PaywallScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val activity = LocalContext.current as? android.app.Activity
    val uriHandler = LocalUriHandler.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val isPro by ProEntitlement.isPro.collectAsState()
    val monthly by ProEntitlement.monthly.collectAsState()
    val lifetime by ProEntitlement.lifetime.collectAsState()
    val isWorking by ProEntitlement.isWorking.collectAsState()
    val storeUnavailable by ProEntitlement.storeUnavailable.collectAsState()

    // Nothing left to sell once the purchase lands.
    LaunchedEffect(isPro) { if (isPro) onDismiss() }

    val termsURL = "https://play.google.com/about/play-terms/"
    val privacyURL = "https://witnesscamera.com/privacy"

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.size(40.dp))
            Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(30.dp))
            Spacer(Modifier.size(14.dp))
            Text(stringResource(R.string.pro_title), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.size(9.dp))
            Text(stringResource(R.string.paywall_subtitle), fontSize = 21.sp, color = Color.White.copy(alpha = 0.85f))

            Column(modifier = Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                FactRow(Icons.Filled.HighQuality, stringResource(R.string.paywall_fact_4k))
                FactRow(Icons.Filled.Speed, stringResource(R.string.paywall_fact_fps))
                FactRow(Icons.Filled.CameraAlt, stringResource(R.string.paywall_fact_hdr))
                FactRow(Icons.Filled.PanTool, stringResource(R.string.paywall_fact_stabilization))
                FactRow(Icons.Filled.LocationOn, stringResource(R.string.paywall_fact_stamp_no_tag))
                FactRow(Icons.Filled.PictureInPicture, stringResource(R.string.paywall_fact_pip))
                FactRow(Icons.Filled.GraphicEq, stringResource(R.string.paywall_fact_highlights))
                FactRow(Icons.Filled.VisibilityOff, stringResource(R.string.paywall_fact_blur))
                FactRow(Icons.Filled.ScreenRotation, stringResource(R.string.paywall_fact_stealth))
                FactRow(Icons.Filled.Flag, stringResource(R.string.paywall_fact_markers))
            }

            Text(
                text = stringResource(R.string.paywall_footer_note),
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 22.dp),
            )

            Spacer(Modifier.size(32.dp))

            when {
                storeUnavailable -> Text(
                    text = stringResource(R.string.paywall_store_unavailable),
                    fontSize = 15.sp, color = com.witnessrecord.app.ui.theme.WitnessOrange,
                )
                // Either product missing is a configuration problem worth
                // knowing about, but whichever one did load can still be sold -
                // so these are two independent offers rather than one block.
                monthly != null || lifetime != null -> {
                    monthly?.let { product ->
                        val price = product.subscriptionOfferDetails?.firstOrNull()
                            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice ?: ""
                        HeroPillButton(
                            text = stringResource(R.string.paywall_price_per_month, price),
                            enabled = !isWorking,
                            onClick = { activity?.let { ProEntitlement.launchPurchase(it) } },
                        )
                    }
                    lifetime?.let { product ->
                        if (monthly != null) {
                            Text(
                                text = stringResource(R.string.paywall_or),
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.padding(top = 14.dp),
                            )
                        }
                        val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                        HeroPillButton(
                            text = stringResource(R.string.paywall_lifetime_price, price),
                            enabled = !isWorking,
                            onClick = { activity?.let { ProEntitlement.launchLifetimePurchase(it) } },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(
                            text = stringResource(R.string.paywall_lifetime_footer),
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clickable(enabled = !isWorking) { scope.launch { ProEntitlement.restore() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.paywall_restore),
                            fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                else -> CircularProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).size(24.dp),
                    color = Color.White,
                )
            }

            Column(modifier = Modifier.padding(top = 18.dp, bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.paywall_legal_footer),
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.legal_terms_of_use),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { uriHandler.openUri(termsURL) },
                    )
                    Text(
                        text = stringResource(R.string.legal_privacy_policy),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { uriHandler.openUri(privacyURL) },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 20.dp, top = 6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun FactRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(26.dp))
        Text(text = text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

