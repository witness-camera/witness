# -*- coding: utf-8 -*-
import strings_part2  # noqa: F401  (registers parts 1 and 2)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------------ settings
new("settings_section_drive", "Google Drive", "Google Drive", "Google Drive", "Google Drive", "Google Drive")
src("settings_account", "Account")
src("settings_sign_out", "Sign out")
src("settings_not_signed_in", "Not signed in, sign in from the home screen.")
src("settings_upload_folder_name", "Upload folder name")

src("settings_section_folder_naming", "Session folder naming")
src("settings_folder_template", "Folder name template")
src("settings_preview", "Preview")
src("settings_reset_to_default", "Reset to default")
src("settings_folder_naming_footer",
    "Placeholders: {date}, {time}, {location}, {session}. Each recording session's Drive subfolder is named from this template. Only applies to sessions started after you change it, existing folders keep their names.")

src("settings_section_camera", "Camera")
src("settings_starting_lens", "Starting lens")
src("settings_record_front_camera", "Record front camera too")
src("settings_dual_camera", "Dual camera")
src("settings_not_supported", "Not supported on this device")
src("settings_show_viewfinder", "Show viewfinder while recording")
src("settings_lens_footer_auto",
    "Recordings start at the 0.5× ultra-wide lens when the device supports it, otherwise 1×.")
src("settings_lens_footer_ultra_wide",
    "Recordings start at the 0.5× ultra-wide lens. In dual-camera mode, if the device can't run 0.5× with the front camera, it records a single 0.5× camera rather than switching to 1×.")
src("settings_lens_footer_wide", "Recordings start at the standard 1× lens.")
src("settings_lens_footer_live",
    "With the viewfinder on you can switch 0.5×↔1× live while recording, with no gap in the footage.")
# Android caps concurrent two-camera streaming at 720p per camera, which the
# iOS footer does not mention - so this one is adapted rather than reused.
new("settings_dual_footer_supported",
    "Records the front (selfie) camera at the same time as the rear one, producing two synchronized videos per session. Android caps simultaneous two-camera capture at 720p per camera; single-camera recordings stay at 1080p. Uses more battery and may warm the device during long recordings.",
    "Registra la fotocamera frontale (selfie) contemporaneamente a quella posteriore, producendo due video sincronizzati per sessione. Android limita la ripresa simultanea con due fotocamere a 720p per fotocamera; le registrazioni con una sola fotocamera restano a 1080p. Consuma più batteria e può scaldare il dispositivo durante registrazioni lunghe.",
    "Graba la cámara frontal (selfie) al mismo tiempo que la trasera, produciendo dos vídeos sincronizados por sesión. Android limita la captura simultánea con dos cámaras a 720p por cámara; las grabaciones con una sola cámara se mantienen en 1080p. Consume más batería y puede calentar el dispositivo en grabaciones largas.",
    "Enregistre la caméra frontale (selfie) en même temps que celle de l'arrière, produisant deux vidéos synchronisées par session. Android limite la capture simultanée à deux caméras à 720p par caméra ; les enregistrements à une seule caméra restent en 1080p. Consomme plus de batterie et peut chauffer l'appareil lors de longs enregistrements.",
    "Nimmt die Frontkamera (Selfie) gleichzeitig mit der Rückkamera auf und erzeugt so zwei synchrone Videos pro Sitzung. Android begrenzt die gleichzeitige Aufnahme mit zwei Kameras auf 720p je Kamera; Aufnahmen mit einer Kamera bleiben bei 1080p. Verbraucht mehr Akku und kann das Gerät bei langen Aufnahmen erwärmen.")
src("settings_dual_footer_unsupported",
    "This device can't record both cameras simultaneously, so only the rear camera is used.")
src("settings_viewfinder_footer_supported",
    "With the viewfinder on, recording starts with a live preview (rear full-screen, front in a corner) instead of a black screen. You can also toggle it any time while recording. Takes effect on your next recording.")
src("settings_viewfinder_footer_unsupported",
    "With the viewfinder on, recording starts with a live preview instead of a black screen. You can also toggle it any time while recording.")

# Instant-start section: Android only.
new("settings_section_instant_start", "Instant start",
    "Avvio immediato", "Inicio inmediato", "Démarrage immédiat", "Sofortstart")
new("settings_instant_start_body",
    "Add the Witness tile to Quick Settings, or the Witness widget to your home screen, to start recording in one tap. You can also say \"Hey Google, start recording with Witness\".",
    "Aggiungi il riquadro Witness alle Impostazioni rapide, o il widget Witness alla schermata Home, per avviare la registrazione con un tocco. Puoi anche dire \"Hey Google, avvia la registrazione con Witness\".",
    "Añade el mosaico Witness a los Ajustes rápidos, o el widget Witness a tu pantalla de inicio, para empezar a grabar con un toque. También puedes decir \"Hey Google, empieza a grabar con Witness\".",
    "Ajoutez la tuile Witness aux Réglages rapides, ou le widget Witness à votre écran d'accueil, pour lancer un enregistrement en un appui. Vous pouvez aussi dire « Hey Google, lance l'enregistrement avec Witness ».",
    "Füge die Witness-Kachel zu den Schnelleinstellungen oder das Witness-Widget zum Startbildschirm hinzu, um mit einem Tippen aufzunehmen. Du kannst auch sagen: \"Hey Google, starte die Aufnahme mit Witness\".")
new("settings_power_button", "Start with a power-button double-press",
    "Avvia con doppio clic sul tasto di accensione",
    "Iniciar con doble pulsación del botón de encendido",
    "Lancer par double appui sur le bouton marche/arrêt",
    "Mit Doppelklick auf die Ein-/Aus-Taste starten")
new("settings_instant_start_footer",
    "Android reserves the power-button double-press for camera apps, so turning this on also offers Witness as a camera app and Android will ask which one to use. Turn it off to give the gesture back to your usual camera.",
    "Android riserva il doppio clic sul tasto di accensione alle app fotocamera: attivandolo, Witness viene proposta anche come app fotocamera e Android chiederà quale usare. Disattivalo per restituire il gesto alla tua fotocamera abituale.",
    "Android reserva la doble pulsación del botón de encendido para las apps de cámara: al activarlo, Witness también se ofrece como app de cámara y Android preguntará cuál usar. Desactívalo para devolver el gesto a tu cámara habitual.",
    "Android réserve le double appui sur le bouton marche/arrêt aux applis appareil photo : en l'activant, Witness est aussi proposée comme appareil photo et Android demandera laquelle utiliser. Désactivez-le pour rendre ce geste à votre appareil photo habituel.",
    "Android reserviert den Doppelklick auf die Ein-/Aus-Taste für Kamera-Apps: Wenn du dies aktivierst, wird Witness auch als Kamera-App angeboten und Android fragt, welche verwendet werden soll. Schalte es aus, um die Geste deiner gewohnten Kamera zurückzugeben.")

# On-device backup: "Photos library" becomes the Android gallery, and the
# iCloud sentence does not apply - adapted copy, not the iOS translation.
src("settings_section_device_backup", "On-device backup")
new("settings_save_to_gallery", "Save recordings to gallery",
    "Salva le registrazioni nella galleria",
    "Guardar las grabaciones en la galería",
    "Enregistrer les vidéos dans la galerie",
    "Aufnahmen in der Galerie speichern")
new("settings_gallery_footer",
    "Saves one stitched video per camera per recording session into a Witness folder in your gallery, as backup independent of Google Drive. If you sync your gallery to a cloud photo service, it goes there too. Turn this off to keep recordings out of your gallery, Drive upload is unaffected.",
    "Salva un video unito per fotocamera per ogni sessione di registrazione in una cartella Witness nella tua galleria, come backup indipendente da Google Drive. Se sincronizzi la galleria con un servizio foto cloud, finirà anche lì. Disattivalo per tenere le registrazioni fuori dalla galleria: il caricamento su Drive non cambia.",
    "Guarda un vídeo unido por cámara y sesión de grabación en una carpeta Witness de tu galería, como copia de seguridad independiente de Google Drive. Si sincronizas la galería con un servicio de fotos en la nube, también irá allí. Desactívalo para mantener las grabaciones fuera de la galería; la subida a Drive no se ve afectada.",
    "Enregistre une vidéo assemblée par caméra et par session dans un dossier Witness de votre galerie, comme sauvegarde indépendante de Google Drive. Si vous synchronisez votre galerie avec un service photo cloud, elle y sera aussi. Désactivez pour garder les vidéos hors de votre galerie ; l'envoi vers Drive n'est pas affecté.",
    "Speichert pro Kamera und Aufnahmesitzung ein zusammengefügtes Video in einem Witness-Ordner in deiner Galerie – als Sicherung unabhängig von Google Drive. Wenn du deine Galerie mit einem Cloud-Fotodienst synchronisierst, landet es auch dort. Schalte dies aus, um Aufnahmen aus der Galerie herauszuhalten; der Drive-Upload bleibt davon unberührt.")

src("settings_combine_videos", "Combine into one video per camera")
src("settings_drive_stitch_footer",
    "Once every small chunk of a session is safely uploaded, Witness merges them into a single video per camera, uploads that to the session's Drive folder, and then deletes the small chunks, so you get one clean file per camera instead of a folder full of pieces. This never happens until the small chunks are already confirmed in Drive, so nothing is at risk while it runs. Turn this off to keep the individual chunks instead.")

src("settings_section_contacts", "Emergency contacts")
src("settings_contact_no_email", "No valid email, won't receive footage")
src("settings_contacts_footer",
    "When a recording starts uploading, each contact is granted view access to that recording's Google Drive folder and notified by Google Drive. They can watch footage arrive live, access that survives even if your phone is taken. Contacts are only shared with when you record.")

src("settings_protect_footage", "Protect footage from deletion")
new("settings_protect_footer",
    "Witness never deletes your recordings from Drive itself. But anyone holding your unlocked phone could delete them through the Google Drive app or website. Learn how to guard against that.",
    None, None, None, None)  # placeholder, replaced below by the iOS source

# The footer above is verbatim iOS copy - reuse its translations.
src("settings_protect_footer",
    "Witness never deletes your recordings from Drive itself. But anyone holding your unlocked phone could delete them through the Google Drive app or website. Learn how to guard against that.")

# ------------------------------------------------------------ protect footage
src("protect_title", "Protect footage")
src("protect_what_header", "What this app does")
src("protect_what_body",
    "Witness never deletes your recordings, there is no delete button anywhere in this app, and it only ever adds files to your Drive.")
src("protect_risk_header", "The risk")
src("protect_risk_body_1",
    "Your footage uploads to your own Google Drive. In Google Drive, the account owner can always delete their files. So if someone has your unlocked phone and can open the Google Drive app, the Gmail app, or drive.google.com in a browser, they can delete the recordings there, and deleting them removes access for your emergency contacts too.")
src("protect_risk_body_2",
    "Sharing with an emergency contact does not, by itself, give them a separate copy: it grants them a view of your file. If your copy is deleted, their view disappears with it (though deleted files sit in Drive's Trash, recoverable for ~30 days, unless the Trash is also emptied).")
src("protect_strongest_header", "Strongest protection (optional)")
src("protect_dedicated_account", "Use a dedicated Google account")
# The iOS text says "your iPhone's Settings"; on Android the account would be
# added under the system account list instead, so this is adapted copy.
new("protect_dedicated_account_body",
    "Create a Google account used only for Witness, and sign in to it here. Crucially, do not add that account to your phone's system accounts, the Gmail or Drive apps, or any browser you're signed into. Then there is no way to open that Drive from your phone to delete anything, the account simply isn't reachable through the phone's apps. This app keeps its own private login and offers no way to view or delete the footage.",
    "Crea un account Google usato solo per Witness e accedi qui con quello. Fondamentale: non aggiungere quell'account agli account di sistema del telefono, alle app Gmail o Drive, né a un browser in cui hai effettuato l'accesso. Così non c'è modo di aprire quel Drive dal telefono per cancellare qualcosa: l'account semplicemente non è raggiungibile dalle app del telefono. Questa app mantiene il proprio accesso privato e non offre alcun modo di vedere o eliminare i filmati.",
    "Crea una cuenta de Google usada solo para Witness e inicia sesión aquí con ella. Fundamental: no añadas esa cuenta a las cuentas del sistema de tu teléfono, a las apps de Gmail o Drive, ni a ningún navegador en el que hayas iniciado sesión. Así no habrá forma de abrir ese Drive desde el teléfono para borrar nada: la cuenta sencillamente no es accesible desde las apps del teléfono. Esta app mantiene su propio acceso privado y no ofrece ninguna forma de ver ni eliminar las grabaciones.",
    "Créez un compte Google utilisé uniquement pour Witness et connectez-vous avec ici. Essentiel : n'ajoutez pas ce compte aux comptes système de votre téléphone, aux applis Gmail ou Drive, ni à un navigateur où vous êtes connecté. Ainsi, il n'y a aucun moyen d'ouvrir ce Drive depuis le téléphone pour supprimer quoi que ce soit : le compte n'est tout simplement pas accessible via les applis du téléphone. Cette app conserve sa propre connexion privée et n'offre aucun moyen de voir ou de supprimer les vidéos.",
    "Erstelle ein Google-Konto, das nur für Witness verwendet wird, und melde dich hier damit an. Entscheidend: Füge dieses Konto nicht den Systemkonten deines Telefons, den Gmail- oder Drive-Apps oder einem Browser hinzu, in dem du angemeldet bist. Dann gibt es keine Möglichkeit, dieses Drive vom Telefon aus zu öffnen und etwas zu löschen – das Konto ist über die Apps des Telefons schlicht nicht erreichbar. Diese App behält ihre eigene private Anmeldung und bietet keinerlei Möglichkeit, die Aufnahmen anzusehen oder zu löschen.")
src("protect_backstop_header", "Extra backstop (optional)")
src("protect_own_copy", "Have contacts keep their own copy")
src("protect_own_copy_body",
    "When an emergency contact receives the shared folder, they can open it and choose “Make a copy” to save the footage into their own Drive. That copy belongs to them and cannot be deleted from your phone or account.")
src("protect_optional_footer",
    "These steps are optional. If you'd rather use your everyday Google account, Witness still works exactly the same, this only affects how hard it is for someone with your phone to delete footage after the fact.")
