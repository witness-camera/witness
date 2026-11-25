package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoCameraBack
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.model.EmergencyContact
import com.witnessrecord.app.service.storage.GoogleDriveProvider
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 7

/**
 * One-time onboarding, paged one idea per screen - how it works, instant
 * start, permissions, connecting Drive, adding an emergency contact, then
 * rights - so each is actually read rather than scrolled past. Full-bleed
 * ambient background, big minimal text, a "Next"/"Get Started" pill, and
 * native paging (swipe or tap).
 */
@Composable
fun OnboardingScreen(
    onRequestCapturePermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSignIn: (provider: StorageProvider, onError: (String) -> Unit) -> Unit,
    onFinished: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage

    // Back steps through the flow rather than leaving the app; on the first
    // page there is nothing behind it, so the system default applies.
    BackHandler(enabled = page > 0) {
        scope.launch { pagerState.animateScrollToPage(page - 1) }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(page = page)

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // Back chrome
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 20.dp, vertical = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (page > 0) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { index ->
                // The welcome page is the app's own brand mark, not a feature
                // bullet, so it gets a page that reads like a title screen -
                // centered - instead of the shared top-aligned OnboardingPage
                // shell the rest of the pages use.
                if (index == 0) {
                    WelcomePage()
                } else {
                    OnboardingPage {
                        when (index) {
                            1 -> HowItWorksPage()
                            2 -> InstantStartPage()
                            3 -> PermissionsPage(
                                onRequestCapturePermissions = onRequestCapturePermissions,
                                onRequestLocationPermission = onRequestLocationPermission,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                            )
                            4 -> DrivePage(onSignIn = onSignIn)
                            5 -> ContactPage()
                            else -> RightsPage()
                        }
                    }
                }
            }

            // Footer: page dots plus the forward pill.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(PAGE_COUNT) { index ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (index == page) 20.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    Color.White.copy(alpha = if (index == page) 0.9f else 0.25f)
                                )
                        )
                    }
                }

                HeroPillButton(
                    text = stringResource(
                        if (page == PAGE_COUNT - 1) R.string.onboarding_get_started
                        else R.string.onboarding_next
                    ),
                    onClick = {
                        if (page == PAGE_COUNT - 1) {
                            onFinished()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * One onboarding page's content, top-aligned with generous breathing room
 * above - the same vertical rhythm as the iOS build's `OnboardingPage`.
 */
@Composable
private fun OnboardingPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(60.dp))
        content()
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - Pages

/**
 * Centered rather than the shared top-aligned OnboardingPage shell: this is
 * the app's own brand mark, not a feature bullet, so it gets the one page
 * that reads like a title screen instead of a list.
 */
@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.witness_mark),
            contentDescription = null,
            modifier = Modifier.size(140.dp).padding(bottom = 12.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            fontSize = 21.sp,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HowItWorksPage() {
    TitleRow(stringResource(R.string.onboarding_how_title))
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        FactRow(Icons.Filled.VisibilityOff, stringResource(R.string.onboarding_how_black))
        FactRow(Icons.Filled.Podcasts, stringResource(R.string.onboarding_how_uploads))
        FactRow(Icons.Filled.VerifiedUser, stringResource(R.string.onboarding_how_hashed))
    }
}

/**
 * The Android counterpart of the iOS build's Action Button page. Android has
 * no Action Button, so the same promise - recording starts without navigating
 * the app - is delivered by the native equivalents, led by the
 * quick-settings tile since it is reachable from any screen in one pull.
 */
@Composable
private fun InstantStartPage() {
    TitleRow(stringResource(R.string.onboarding_instant_title))
    BodyText(stringResource(R.string.onboarding_instant_body))
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            stringResource(R.string.onboarding_instant_tile_a),
            stringResource(R.string.onboarding_instant_tile_b),
        )
        FlowRow(
            stringResource(R.string.onboarding_instant_widget_a),
            stringResource(R.string.onboarding_instant_widget_b),
        )
    }
    Spacer(Modifier.height(10.dp))
    CaptionText(stringResource(R.string.onboarding_instant_caption))
}

@Composable
private fun PermissionsPage(
    onRequestCapturePermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    TitleRow(stringResource(R.string.onboarding_permissions_title))
    BodyText(stringResource(R.string.onboarding_permissions_body))
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionRow(
            title = stringResource(R.string.onboarding_permission_camera_mic),
            icon = Icons.Filled.Videocam,
            onClick = onRequestCapturePermissions,
        )
        PermissionRow(
            title = stringResource(R.string.onboarding_permission_location),
            icon = Icons.Filled.LocationOn,
            onClick = onRequestLocationPermission,
        )
        PermissionRow(
            title = stringResource(R.string.onboarding_permission_notifications),
            icon = Icons.Filled.Notifications,
            onClick = onRequestNotificationPermission,
        )
    }
    Spacer(Modifier.height(6.dp))
    CaptionText(stringResource(R.string.onboarding_permissions_caption))
}

@Composable
private fun DrivePage(onSignIn: (provider: StorageProvider, onError: (String) -> Unit) -> Unit) {
    val userEmail by GoogleDriveProvider.accountLabelFlow.collectAsState()
    var signInError by remember { mutableStateOf<String?>(null) }

    Image(
        painter = painterResource(R.drawable.google_drive_icon),
        contentDescription = null,
        modifier = Modifier.size(width = 40.dp, height = 36.dp),
    )
    Spacer(Modifier.height(12.dp))
    TitleRow(stringResource(R.string.onboarding_drive_title))
    BodyText(stringResource(R.string.onboarding_drive_body))
    Spacer(Modifier.height(8.dp))

    if (userEmail != null) {
        // A blank (non-null) value means signed in with the account name not
        // resolved yet, not signed out - the check above still has to fire so
        // this reads as connected, and the name falls back to the provider's
        // own label rather than showing an empty row.
        val displayName = userEmail!!.takeIf { it.isNotBlank() } ?: stringResource(R.string.storage_google_drive)
        BounceCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WitnessGreen)
                Text(
                    text = displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    } else {
        BounceCard(onClick = { onSignIn(GoogleDriveProvider) { message -> signInError = message } }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.PersonAddAlt,
                    contentDescription = null,
                    tint = WitnessRed,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.onboarding_drive_sign_in),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
        signInError?.let {
            Spacer(Modifier.height(6.dp))
            // A runtime error string from the sign-in SDK, not translatable
            // app copy - shown as-is rather than treated as a lookup key.
            Text(text = it, fontSize = 14.sp, color = WitnessRed.copy(alpha = 0.85f))
        }
    }

    Spacer(Modifier.height(10.dp))
    CaptionText(stringResource(R.string.onboarding_drive_caption))
}

@Composable
private fun ContactPage() {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(false) }

    TitleRow(stringResource(R.string.onboarding_contact_title))
    BodyText(stringResource(R.string.onboarding_contact_body))
    Spacer(Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DarkTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.contact_name),
        )
        DarkTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = stringResource(R.string.contact_email),
            keyboardType = KeyboardType.Email,
        )
        DarkTextField(
            value = phone,
            onValueChange = { phone = it },
            placeholder = stringResource(R.string.contact_phone),
            keyboardType = KeyboardType.Phone,
        )
        HeroPillButton(
            text = stringResource(R.string.contact_add),
            enabled = name.isNotBlank() && (email.contains("@") || phone.isNotBlank()),
            onClick = {
                val contacts = EmergencyContact.load(context) +
                    EmergencyContact.newContact(name.trim(), email.trim(), phone.trim())
                EmergencyContact.save(context, contacts)
                name = ""
                email = ""
                phone = ""
                added = true
            },
        )
    }

    if (added) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_contact_added),
            fontSize = 14.sp,
            color = WitnessGreen.copy(alpha = 0.85f),
        )
    }
    Spacer(Modifier.height(10.dp))
    CaptionText(stringResource(R.string.onboarding_contact_caption))
}

@Composable
private fun RightsPage() {
    val uriHandler = LocalUriHandler.current

    TitleRow(stringResource(R.string.onboarding_rights_title))
    BodyText(stringResource(R.string.onboarding_rights_body))
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable { uriHandler.openUri(ACLU_URL) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_rights_link),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    CaptionText(stringResource(R.string.onboarding_rights_caption))
}

private const val ACLU_URL = "https://www.aclu.org/know-your-rights/protesters-rights"

// MARK: - Building blocks

@Composable
private fun TitleRow(title: String) {
    Text(
        text = title,
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, fontSize = 21.sp, color = Color.White.copy(alpha = 0.85f))
}

@Composable
private fun CaptionText(text: String) {
    Text(text = text, fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
}

@Composable
private fun FactRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(26.dp))
        Text(text = text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/**
 * Composed from two independently-translated strings plus a literal arrow -
 * not one interpolated template, which would bake the *words* in as opaque
 * runtime arguments that never go through their own translation lookup.
 */
@Composable
private fun FlowRow(first: String, second: String) {
    Text(
        text = "$first → $second",
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
}

@Composable
private fun PermissionRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    BounceCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(22.dp))
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = Color.White.copy(alpha = 0.4f))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = if (keyboardType == KeyboardType.Email) KeyboardCapitalization.None
            else KeyboardCapitalization.Words,
            autoCorrectEnabled = keyboardType != KeyboardType.Email,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.1f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = WitnessRed,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
