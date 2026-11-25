package com.witnessrecord.app.service

import android.content.Context
import com.witnessrecord.app.R
import com.witnessrecord.app.model.EmergencyContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Drives the "let my emergency contacts know where I am" flow: builds one
 * message, then hands it to whichever compose screens the saved contacts can
 * actually receive it through - the phone's messaging app, its email app, or
 * messaging followed by email if contacts exist for both.
 *
 * Deliberately not silent. The message is opened ready-written in the
 * user's own messaging or email app and sending it takes a tap there. That
 * is the same behaviour as the iOS build, and it is the right one here too:
 * sending an SMS without that tap needs the restricted SEND_SMS permission,
 * which Google Play only grants to apps whose core purpose is messaging, and
 * a covert recorder asking to text people on its own would be the wrong thing
 * to hold anyway.
 *
 * This object only decides what to open. Opening it needs an activity, so the
 * recording screen observes [step], launches it, and reports back through
 * [stepLaunched], [stepFailed] and [onReturned].
 */
object EmergencyPing {

    sealed interface Step {
        data class Sms(val recipients: List<String>, val body: String) : Step
        data class Mail(val recipients: List<String>, val subject: String, val body: String) : Step
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _step = MutableStateFlow<Step?>(null)
    /** The compose screen waiting to be opened, or null. */
    val step: StateFlow<Step?> = _step.asStateFlow()

    private val _noEligibleContacts = MutableStateFlow(false)
    /** Set when there is nobody to send this to, so the screen can explain. */
    val noEligibleContacts: StateFlow<Boolean> = _noEligibleContacts.asStateFlow()

    private val _locationDeniedPromptNeeded = MutableStateFlow(false)
    /** Set when location access is off and the person must choose: Settings, or send without it. */
    val locationDeniedPromptNeeded: StateFlow<Boolean> = _locationDeniedPromptNeeded.asStateFlow()

    private var pendingMail: Step.Mail? = null
    private var waitingRecipients: Pair<List<String>, List<String>>? = null
    private var mailFollowsReturn = false

    /**
     * Begins a ping. [context] only supplies the saved contacts and the
     * message's wording; nothing is launched from here.
     */
    fun start(context: Context) {
        val contacts = EmergencyContact.load(context)
        val smsRecipients = contacts.filter { it.canReceiveSMS }.mapNotNull { it.phone?.trim() }
        val mailRecipients = contacts.filter { it.hasValidEmail }.mapNotNull { it.email?.trim() }

        if (smsRecipients.isEmpty() && mailRecipients.isEmpty()) {
            _noEligibleContacts.value = true
            return
        }

        val appContext = context.applicationContext
        scope.launch {
            if (LocationService.hasPermission) {
                LocationService.requestOneShotLocation()
                // Up to a few seconds for a first fix - worth it for a
                // message that is useless without one, but bounded so a poor
                // signal cannot hold the alert up indefinitely.
                val deadline = System.currentTimeMillis() + 6_000
                while (LocationService.lastLocation == null && System.currentTimeMillis() < deadline) {
                    delay(200)
                }
                present(appContext, smsRecipients, mailRecipients)
            } else {
                // Declined: the system will not ask again, so the choice is
                // Settings, or going ahead without a location.
                waitingRecipients = smsRecipients to mailRecipients
                _locationDeniedPromptNeeded.value = true
            }
        }
    }

    /** The "Send Without Location" answer to the denied-access prompt. */
    fun sendWithoutLocation(context: Context) {
        val waiting = waitingRecipients ?: return
        waitingRecipients = null
        _locationDeniedPromptNeeded.value = false
        present(context.applicationContext, waiting.first, waiting.second)
    }

    fun cancelWaiting() {
        waitingRecipients = null
        _locationDeniedPromptNeeded.value = false
    }

    fun dismissNoContacts() {
        _noEligibleContacts.value = false
    }

    private fun present(context: Context, sms: List<String>, mail: List<String>) {
        val body = messageBody(context)
        if (mail.isNotEmpty()) {
            pendingMail = Step.Mail(mail, context.getString(R.string.ping_subject), body)
        }
        if (sms.isNotEmpty()) {
            _step.value = Step.Sms(sms, body)
        } else {
            // Every contact only had an email: straight to the mail app.
            presentPendingMail()
        }
    }

    /** The screen opened [launched]; clears it so it is not opened twice. */
    fun stepLaunched(launched: Step) {
        if (_step.value == launched) _step.value = null
        // Messaging is another app: mail follows once the user is back here.
        if (launched is Step.Sms) mailFollowsReturn = true
    }

    /** Nothing on this phone could open [failed] (no messaging or mail app). */
    fun stepFailed(failed: Step) {
        if (_step.value == failed) _step.value = null
        if (failed is Step.Sms) presentPendingMail() else pendingMail = null
    }

    /**
     * Called each time the app comes back to the front. If messaging was just
     * opened, this is the moment it has been closed again - sent, discarded
     * or abandoned alike - so a contact reachable only by email still gets
     * their message.
     */
    fun onReturned() {
        if (!mailFollowsReturn) return
        mailFollowsReturn = false
        presentPendingMail()
    }

    private fun presentPendingMail() {
        val pending = pendingMail ?: return
        pendingMail = null
        scope.launch {
            // Lets the previous app finish closing first - opening another in
            // the same moment as a return is unreliable.
            delay(400)
            _step.value = pending
        }
    }

    private fun messageBody(context: Context): String {
        val lines = mutableListOf(context.getString(R.string.ping_message_help))
        val location = LocationService.lastLocation
        if (location != null) {
            val link = String.format(
                Locale.US, "https://maps.google.com/?q=%.6f,%.6f", location.latitude, location.longitude
            )
            lines.add(context.getString(R.string.ping_message_location) + " " + link)
        } else {
            lines.add(context.getString(R.string.ping_message_no_location))
        }
        return lines.joinToString("\n\n")
    }
}
