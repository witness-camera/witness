# -*- coding: utf-8 -*-
"""Emergency location ping, phone numbers on emergency contacts, camera/mic
permission recovery and the highlight-cut Pro export - what iOS 1.1.7 shipped.

Every string here exists in iOS's String Catalog with all four translations,
so it is lifted with src() rather than re-translated.
"""
import strings_part7  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------- location ping
src("ping_subject", "I need help")
src("ping_message_help", "I need help. This is an alert sent from Witness.")
src("ping_message_location", "My last known location:")
src("ping_message_no_location", "Location isn't available right now.")
src("ping_location_denied_title", "Location access is off")
src("ping_location_denied_message",
    "Turn on location for Witness so your contact can see where you are, or send the message without it.")
src("ping_open_settings", "Open Settings")
src("ping_send_without_location", "Send Without Location")
src("ping_no_contacts_title", "No emergency contact on file")
src("ping_no_contacts_message",
    "Add one with a phone number or email in Settings so this button has someone to reach.")
src("ping_button_description", "Alert your emergency contact with your location")

# ----------------------------------------------------------- contact details
src("contact_phone", "Phone (optional)")
src("settings_contact_no_channel", "No phone or email, can't be alerted")
src("settings_contacts_footer_ping",
    "\n\nDuring a recording, a location button lets you send your last known location to every contact here by text and email, whichever they have on file — you send it yourself from a compose screen, nothing goes out automatically.")
src("onboarding_contact_body",
    "An emergency contact automatically gets access to your footage as it uploads, and can be reached with your location at a tap while you're recording.")
src("settings_contacts_footer",
    "When a recording starts uploading, each contact with an email is granted view access to that recording's cloud folder and notified by email. They can watch footage arrive live, access that survives even if your phone is taken. Contacts are only shared with when you record.")
src("settings_contacts_footer_unsupported",
    "%@ can't grant access automatically: emergency contacts with an email won't be notified or given access to your recordings. Switch to a provider that supports sharing, or share a folder yourself from that account.")

# ------------------------------------------------- camera / mic permission
src("permission_needed_title", "Camera & Microphone Access Needed")
src("permission_needed_message",
    "Witness can't record without camera and microphone access. Turn them on in Settings to start recording.")

# ------------------------------------------------------------- Pro exports
src("pro_highlight_title", "Highlight export")
src("pro_highlight_footer",
    "Also produce a shorter cut of the rear camera's video with the quiet dead air trimmed out and the stretches where someone is actually talking kept — in addition to, never instead of, the full recording you already get. Meant for sharing something watchable without asking someone to sit through the whole thing. Adds to how long the recording takes to finish assembling afterward.")
src("paywall_fact_highlights", "A shorter highlight cut with the dead air trimmed out.")

# ------------------------------------------------------------ Pro promo video
# iOS's own description names Apple Log and an iPhone; this one describes what
# the Android clip actually shows.
new("pro_promo_description",
    "Witness Pro features: 4K at 60 fps with 10-bit HDR, stabilization from off to extreme, both cameras in one video, blurred faces and plates, and highlight cuts.",
    "Funzioni di Witness Pro: 4K a 60 fps con HDR a 10 bit, stabilizzazione da spenta a estrema, entrambe le fotocamere in un unico video, volti e targhe sfocati e tagli dei momenti salienti.",
    "Funciones de Witness Pro: 4K a 60 fps con HDR de 10 bits, estabilización de apagada a extrema, ambas cámaras en un solo vídeo, caras y matrículas difuminadas y resúmenes de lo más destacado.",
    "Fonctions de Witness Pro : 4K à 60 i/s en HDR 10 bits, stabilisation de désactivée à extrême, les deux caméras dans une seule vidéo, visages et plaques floutés et montages des temps forts.",
    "Funktionen von Witness Pro: 4K mit 60 fps und 10-Bit-HDR, Stabilisierung von aus bis extrem, beide Kameras in einem Video, verpixelte Gesichter und Kennzeichen und Highlight-Schnitte.")
