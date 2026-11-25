# -*- coding: utf-8 -*-
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------------ practice
src("practice_intro_title", "One quick practice")
src("practice_intro_body",
    "When you record for real, the screen goes black. Stopping takes a specific gesture, let's practice it now, so it's second nature later.")
src("practice_start", "Start practice")
src("practice_skip", "Skip")
src("practice_this_is_recording", "This is what recording looks like")
src("practice_triple_tap_prompt", "Triple-tap anywhere to reveal the controls")
src("practice_badge", "PRACTICE")
src("practice_hold_prompt", "Now press and hold the stop icon for 1 second")
src("practice_success_title", "That's it")
src("practice_success_body",
    "Triple-tap, then press and hold. Same gesture, every time you actually record.")
src("practice_continue", "Continue")

# ---------------------------------------------------------------- onboarding
src("onboarding_next", "Next")
src("onboarding_get_started", "Get Started")
src("onboarding_welcome_body",
    "Preserve footage automatically, even if your phone is taken.")
src("onboarding_how_title", "How it works")
src("onboarding_how_black", "Screen goes black.")
src("onboarding_how_uploads", "Uploads live, every 15s.")
src("onboarding_how_hashed", "Hashed & time-stamped.")

# Instant start. iOS binds the Action Button; Android has none, so this page is
# rewritten around the platform's own one-gesture entry points and cannot reuse
# the iOS translations.
src("onboarding_instant_title", "Start in one press")
new("onboarding_instant_body", "Set up a shortcut so recording starts without opening the app:",
    "Imposta una scorciatoia per avviare la registrazione senza aprire l'app:",
    "Configura un acceso directo para empezar a grabar sin abrir la app:",
    "Configure un raccourci pour lancer l'enregistrement sans ouvrir l'app :",
    "Richte eine Verknüpfung ein, damit die Aufnahme startet, ohne die App zu öffnen:")
new("onboarding_instant_tile_a", "Quick Settings", "Impostazioni rapide",
    "Ajustes rápidos", "Réglages rapides", "Schnelleinstellungen")
new("onboarding_instant_tile_b", "Add the Witness tile", "Aggiungi il riquadro Witness",
    "Añade el mosaico Witness", "Ajoutez la tuile Witness", "Witness-Kachel hinzufügen")
new("onboarding_instant_widget_a", "Home screen", "Schermata Home",
    "Pantalla de inicio", "Écran d'accueil", "Startbildschirm")
new("onboarding_instant_widget_b", "Add the Witness widget", "Aggiungi il widget Witness",
    "Añade el widget Witness", "Ajoutez le widget Witness", "Witness-Widget hinzufügen")
new("onboarding_instant_caption",
    "The Quick Settings tile works from the lock screen. You can also say \"Hey Google, start recording with Witness\", or turn on the power-button double-press in Settings.",
    "Il riquadro nelle Impostazioni rapide funziona anche dalla schermata di blocco. Puoi anche dire \"Hey Google, avvia la registrazione con Witness\", oppure attivare il doppio clic sul tasto di accensione nelle Impostazioni.",
    "El mosaico de Ajustes rápidos funciona desde la pantalla de bloqueo. También puedes decir \"Hey Google, empieza a grabar con Witness\", o activar la doble pulsación del botón de encendido en Ajustes.",
    "La tuile des Réglages rapides fonctionne depuis l'écran de verrouillage. Vous pouvez aussi dire « Hey Google, lance l'enregistrement avec Witness », ou activer le double appui sur le bouton marche/arrêt dans les Réglages.",
    "Die Kachel in den Schnelleinstellungen funktioniert auch im Sperrbildschirm. Du kannst auch sagen: \"Hey Google, starte die Aufnahme mit Witness\" – oder in den Einstellungen den Doppelklick auf die Ein-/Aus-Taste aktivieren.")

src("onboarding_permissions_title", "Turn on access")
src("onboarding_permissions_body",
    "Camera, microphone, and location, used only while recording.")
src("onboarding_permission_camera_mic", "Camera & microphone")
src("onboarding_permission_location", "Location")
new("onboarding_permission_notifications", "Notifications", "Notifiche",
    "Notificaciones", "Notifications", "Mitteilungen")
# iOS explains a Photos permission here. Android needs none to save its own
# recordings, but does need notifications for the service that keeps recording
# alive with the screen off - so the caption is different copy, not a
# translation of the iOS one.
new("onboarding_permissions_caption",
    "Notifications are what let recording keep running when the screen goes off. Saving to your gallery needs no permission at all.",
    "Le notifiche sono ciò che permette alla registrazione di continuare quando lo schermo si spegne. Il salvataggio nella galleria non richiede alcun permesso.",
    "Las notificaciones son lo que permite que la grabación siga cuando la pantalla se apaga. Guardar en la galería no requiere ningún permiso.",
    "Les notifications sont ce qui permet à l'enregistrement de continuer quand l'écran s'éteint. L'enregistrement dans votre galerie ne demande aucune autorisation.",
    "Mitteilungen sind es, die die Aufnahme weiterlaufen lassen, wenn der Bildschirm ausgeht. Für das Speichern in der Galerie ist keinerlei Berechtigung nötig.")

src("onboarding_drive_title", "Connect Google Drive")
src("onboarding_drive_body",
    "Footage uploads to your own Drive as you record, safe even if your phone isn't.")
src("onboarding_drive_sign_in", "Sign in with Google")
src("onboarding_drive_caption", "Until you sign in, footage stays only on this device.")

src("onboarding_contact_title", "Add someone who'll know")
src("onboarding_contact_body",
    "An emergency contact automatically gets access to your footage as it uploads.")
src("onboarding_contact_added", "Added, they'll be notified once you start recording.")
src("onboarding_contact_caption", "You can add more anytime in Settings.")

src("onboarding_rights_title", "Know your rights")
src("onboarding_rights_body",
    "Recording in public is legal in most countries, but rules on distance, audio, and interference vary widely by jurisdiction.")
src("onboarding_rights_link", "US rights guide (ACLU)")
src("onboarding_rights_caption",
    "Not legal advice. Check your local laws, stay safe, and announce you're recording if you can.")

# ------------------------------------------------------------------ contacts
src("contact_name", "Name")
src("contact_email", "Email")
src("contact_add", "Add contact")

# ------------------------------------------------------------------- support
src("support_title", "Support Witness")
src("support_subtitle", "A civic project, free for everyone, and always will be.")
src("support_fact_free", "Free forever, no ads.")
src("support_fact_donations", "Kept running by donations.")
src("support_fact_feedback", "Bugs and ideas welcome.")
src("support_donate", "Donate with PayPal")
src("support_footer",
    "If you're able, contributions help keep Witness going and fund future apps for citizens.")
