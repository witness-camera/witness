# -*- coding: utf-8 -*-
"""Android string table.

Each entry is either:
  ("src", <english source string from the iOS String Catalog>)
      -> reuse the professionally translated it/es/fr/de strings verbatim
  ("new", {"en":..., "it":..., "es":..., "fr":..., "de":...})
      -> Android-specific copy that has no iOS counterpart, or copy whose
         wording had to change for Android (no Action Button, no Siri, gallery
         instead of Photos, a foreground-service notification), where the iOS
         translation would be wrong.
"""

STRINGS = {}

def src(key, english):
    STRINGS[key] = ("src", english)

def new(key, en, it, es, fr, de):
    STRINGS[key] = ("new", {"en": en, "it": it, "es": es, "fr": fr, "de": de})

# ---------------------------------------------------------------- app / chrome
new("app_name", "Witness", "Witness", "Witness", "Witness", "Witness")
src("settings_title", "Settings")
src("action_done", "Done")
new("action_close", "Close", "Chiudi", "Cerrar", "Fermer", "Schließen")
new("action_delete", "Delete", "Elimina", "Eliminar", "Supprimer", "Löschen")
new("onboarding_back", "Back", "Indietro", "Atrás", "Retour", "Zurück")

# ---------------------------------------------------------------------- home
src("home_start_recording", "Start Recording")
src("home_blackout_hint",
    "Screen goes black while recording.\nTriple-tap, then hold the stop icon to end.")
src("home_chunks_queued", "%lld chunk(s) queued for upload")
src("home_last_upload_error", "Last upload error: %@")
src("home_local_only_until_sign_in",
    "Until you sign in, footage is kept safely on this device only.")

# ----------------------------------------------------------------- recording
new("recording_badge_rec", "REC", "REC", "REC", "REC", "REC")
src("recording_hold_to_stop_long", "Recording, press and hold to stop")
src("recording_hold_to_stop_short", "Recording, hold to stop")
src("recording_stop", "Stop Recording")
new("recording_show_viewfinder", "Show viewfinder", "Mostra il mirino",
    "Mostrar el visor", "Afficher le viseur", "Sucher anzeigen")
new("recording_hide_viewfinder", "Hide viewfinder", "Nascondi il mirino",
    "Ocultar el visor", "Masquer le viseur", "Sucher ausblenden")

# -------------------------------------------------------------------- errors
# Deliberately not the iOS wording, which blames camera permission. On the
# first Android device this was ever run on that was simply wrong: permission
# was granted and the camera had opened - the capture *session* was refused
# further down. Naming a cause the app has not established sends people to fix
# something that is not broken.
new("error_camera_setup_failed",
    "Couldn't start the camera. See Diagnostics on the Witness Pro page.",
    "Impossibile avviare la fotocamera. Vedi Diagnostica nella pagina Witness Pro.",
    "No se pudo iniciar la cámara. Consulta Diagnóstico en la página de Witness Pro.",
    "Impossible de démarrer la caméra. Voir Diagnostic sur la page Witness Pro.",
    "Die Kamera konnte nicht gestartet werden. Siehe Diagnose auf der Witness-Pro-Seite.")
src("error_permissions_denied",
    "Camera or microphone permission denied. Enable both in Settings.")
new("error_sign_in_failed", "Could not connect to Google Drive.",
    "Impossibile connettersi a Google Drive.",
    "No se ha podido conectar con Google Drive.",
    "Impossible de se connecter à Google Drive.",
    "Verbindung zu Google Drive nicht möglich.")
src("warning_storage_low",
    "Storage low (%lld MB free). Recording continues; uploaded chunks are deleted automatically to free space.")

# --------------------------------------------------------------------- lens
src("lens_auto", "Auto (0.5× when available)")
src("lens_ultra_wide", "0.5× ultra-wide")
src("lens_wide", "1× wide")

# ------------------------------------------------------- notification (new)
# Android forces a foreground service to post a notification. The copy is
# deliberately plain: it should not announce to a bystander what the phone is
# doing, but it also must not be misleading.
new("notification_channel_recording", "Recording",
    "Registrazione", "Grabación", "Enregistrement", "Aufnahme")
new("notification_channel_recording_description",
    "Keeps a recording running when the screen is off. Android requires this notification; it is silent and shows no status bar icon.",
    "Mantiene attiva la registrazione quando lo schermo è spento. Android richiede questa notifica: è silenziosa e non mostra icone nella barra di stato.",
    "Mantiene la grabación en curso con la pantalla apagada. Android exige esta notificación: es silenciosa y no muestra ningún icono en la barra de estado.",
    "Maintient l'enregistrement en cours lorsque l'écran est éteint. Android impose cette notification : elle est silencieuse et n'affiche aucune icône dans la barre d'état.",
    "Hält die Aufnahme bei ausgeschaltetem Bildschirm am Laufen. Android verlangt diese Mitteilung; sie ist lautlos und zeigt kein Symbol in der Statusleiste.")
new("notification_recording_title", "Witness",
    "Witness", "Witness", "Witness", "Witness")
new("notification_recording_body", "Recording in progress",
    "Registrazione in corso", "Grabación en curso",
    "Enregistrement en cours", "Aufnahme läuft")

# ---------------------------------------------------- tile / widget entries
src("tile_start_recording", "Start Witness Recording")
new("widget_display_name", "Witness", "Witness", "Witness", "Witness", "Witness")
src("widget_start_recording", "Start Recording")
new("widget_description", "One tap starts a black-screen recording.",
    "Un tocco avvia una registrazione a schermo nero.",
    "Un toque inicia una grabación con la pantalla en negro.",
    "Un appui lance un enregistrement à écran noir.",
    "Ein Tippen startet eine Aufnahme mit schwarzem Bildschirm.")
