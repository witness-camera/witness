# -*- coding: utf-8 -*-
import strings_part3  # noqa: F401  (registers parts 1-3)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------- storage picker
src("storage_title", "Storage")
new("storage_pick_footer",
    "Recordings upload to an account you own. Witness has no server of its own and keeps no copy: the developer cannot see your footage on any of these services.",
    "Le registrazioni vengono caricate su un account che possiedi tu. Witness non ha un proprio server e non conserva copie: lo sviluppatore non può vedere i tuoi filmati su nessuno di questi servizi.",
    "Las grabaciones se suben a una cuenta de tu propiedad. Witness no tiene servidor propio ni guarda copias: el desarrollador no puede ver tus grabaciones en ninguno de estos servicios.",
    "Les enregistrements sont envoyés vers un compte qui vous appartient. Witness n'a pas de serveur et n'en conserve aucune copie : le développeur ne peut voir vos vidéos sur aucun de ces services.",
    "Aufnahmen werden in ein Konto hochgeladen, das dir gehört. Witness hat keinen eigenen Server und behält keine Kopie: Der Entwickler kann deine Aufnahmen bei keinem dieser Dienste sehen.")
new("storage_pick_header",
    "Choose where footage goes", "Scegli dove finiscono i filmati", "Elige dónde van las grabaciones",
    "Choisissez où vont les vidéos", "Wähle, wohin die Aufnahmen gehen")
src("storage_coming_soon", "Coming soon")
src("action_cancel", "Cancel")

new("storage_google_drive", "Google Drive", "Google Drive", "Google Drive", "Google Drive", "Google Drive")
new("storage_google_drive_subtitle", "Sign in with Google", "Accedi con Google", "Iniciar sesión con Google",
    "Se connecter avec Google", "Mit Google anmelden")
new("storage_dropbox", "Dropbox", "Dropbox", "Dropbox", "Dropbox", "Dropbox")
src("storage_dropbox_subtitle", "Sign in with Dropbox")
new("storage_webdav", "Nextcloud or WebDAV", "Nextcloud o WebDAV", "Nextcloud o WebDAV",
    "Nextcloud ou WebDAV", "Nextcloud oder WebDAV")
src("storage_webdav_subtitle", "Your own server, or any WebDAV host")
new("storage_proton", "Proton Drive", "Proton Drive", "Proton Drive", "Proton Drive", "Proton Drive")
new("storage_proton_subtitle", "Not supported — tap to find out why",
    "Non supportato: tocca per scoprire perché", "No compatible: toca para saber por qué",
    "Non pris en charge — touchez pour savoir pourquoi", "Nicht unterstützt — tippen, um zu erfahren, warum")
new("storage_proton_unsupported_title",
    "Proton Drive isn't supported", "Proton Drive non è supportato", "Proton Drive no es compatible",
    "Proton Drive n'est pas pris en charge", "Proton Drive wird nicht unterstützt")
new("storage_proton_unsupported_message",
    "Proton encrypts every file with keys tied to your account before it ever leaves their app, and doesn't publish any way for other apps to connect — not even the shared-server access Nextcloud offers. There's no way to add real automatic upload here. You can still move footage into Proton Drive yourself afterward; until you connect a supported backend, footage stays on this device.",
    "Proton cifra ogni file con chiavi legate al tuo account prima ancora che lasci la loro app, e non pubblica alcun modo per altre app di connettersi, nemmeno l'accesso via server condiviso che offre Nextcloud. Non c'è modo di aggiungere qui un caricamento automatico reale. Puoi comunque spostare tu stesso i filmati su Proton Drive in seguito; finché non colleghi un servizio supportato, i filmati restano su questo dispositivo.",
    "Proton cifra cada archivo con claves ligadas a tu cuenta antes de que salga de su app, y no publica ninguna forma de que otras apps se conecten, ni siquiera el acceso por servidor compartido que ofrece Nextcloud. No hay forma de añadir aquí una subida automática real. Aun así puedes mover tú mismo después las grabaciones a Proton Drive; hasta que conectes un servicio compatible, las grabaciones permanecen en este dispositivo.",
    "Proton chiffre chaque fichier avec des clés liées à votre compte avant qu'il ne quitte leur appli, et ne publie aucun moyen pour d'autres applis de s'y connecter — pas même l'accès par serveur partagé que propose Nextcloud. Il n'existe aucun moyen d'ajouter ici un envoi automatique réel. Vous pouvez toujours déplacer vous-même les vidéos vers Proton Drive ensuite ; tant que vous n'avez pas connecté un service pris en charge, les vidéos restent sur cet appareil.",
    "Proton verschlüsselt jede Datei mit an dein Konto gebundenen Schlüsseln, bevor sie die App überhaupt verlässt, und veröffentlicht keine Möglichkeit für andere Apps, sich zu verbinden — nicht einmal den Shared-Server-Zugriff, den Nextcloud bietet. Ein echter automatischer Upload lässt sich hier nicht einrichten. Du kannst Aufnahmen weiterhin selbst nachträglich zu Proton Drive verschieben; bis du einen unterstützten Dienst verbindest, bleiben Aufnahmen auf diesem Gerät.")

# ------------------------------------------------------------- WebDAV setup
new("webdav_title", "WebDAV", "WebDAV", "WebDAV", "WebDAV", "WebDAV")
new("webdav_address_placeholder", "https://cloud.example.org/remote.php/dav/files/you",
    "https://cloud.example.org/remote.php/dav/files/you", "https://cloud.example.org/remote.php/dav/files/you",
    "https://cloud.example.org/remote.php/dav/files/you", "https://cloud.example.org/remote.php/dav/files/you")
src("webdav_username", "Username")
src("webdav_password", "Password")
new("webdav_server_footer",
    "On Nextcloud or ownCloud, the address is under Settings, then WebDAV. Use an app password rather than your account password where the server offers one: it can be revoked on its own if this phone is lost.",
    "Su Nextcloud o ownCloud l'indirizzo si trova in Impostazioni, poi WebDAV. Usa una password per app invece della password dell'account, se il server la offre: può essere revocata da sola se questo telefono viene perso.",
    "En Nextcloud u ownCloud, la dirección está en Ajustes, luego WebDAV. Usa una contraseña de aplicación en vez de la de tu cuenta si el servidor la ofrece: se puede revocar por separado si pierdes este teléfono.",
    "Sur Nextcloud ou ownCloud, l'adresse se trouve dans Réglages, puis WebDAV. Utilisez un mot de passe d'application plutôt que celui de votre compte lorsque le serveur le propose : il peut être révoqué seul si ce téléphone est perdu.",
    "Bei Nextcloud oder ownCloud findest du die Adresse unter Einstellungen, dann WebDAV. Verwende, wenn der Server es anbietet, ein App-Passwort statt deines Kontopassworts: Es lässt sich einzeln widerrufen, falls dieses Telefon verloren geht.")
src("webdav_connect", "Connect")
src("webdav_checking", "Checking…")
src("webdav_bad_address", "That doesn't look like a full web address.")
src("webdav_requires_https", "The address must start with https:// so your password isn't sent in the clear.")
src("webdav_sharing_footer",
    "Emergency contacts can't be given access automatically on WebDAV: the protocol has no sharing feature, and each server adds its own. Everything else works the same, and you can still share a folder yourself from your server.")

# ------------------------------------------------------------- home / not-signed-in
new("home_connect_cloud_storage", "Connect cloud storage", "Collega uno spazio cloud", "Conectar almacenamiento en la nube",
    "Connecter un stockage cloud", "Cloud-Speicher verbinden")
new("home_local_only_recording", "Recording stays on this device", "La registrazione resta su questo dispositivo",
    "La grabación permanece en este dispositivo", "L'enregistrement reste sur cet appareil",
    "Die Aufnahme bleibt auf diesem Gerät")
new("home_local_only_body",
    "The mastering codec is too large to upload live. Turn it off in Pro to start uploading again.",
    "Il codec mastering è troppo pesante per il caricamento in diretta. Disattivalo in Pro per riprendere il caricamento.",
    "El códec de masterización es demasiado pesado para subir en directo. Desactívalo en Pro para volver a subir.",
    "Le codec de mastering est trop lourd pour un envoi en direct. Désactivez-le dans Pro pour recommencer à envoyer.",
    "Der Mastering-Codec ist für einen Live-Upload zu schwer. Schalte ihn in Pro aus, um wieder hochzuladen.")
new("home_uploading_to_as", "Uploading to %1$s as %2$s", "Caricamento su %1$s come %2$s",
    "Subiendo a %1$s como %2$s", "Envoi vers %1$s en tant que %2$s", "Hochladen zu %1$s als %2$s")
new("home_uploading_to", "Uploading to %1$s", "Caricamento su %1$s", "Subiendo a %1$s",
    "Envoi vers %1$s", "Hochladen zu %1$s")

# ------------------------------------------------------------------ Pro badge
new("pro_badge", "PRO", "PRO", "PRO", "PRO", "PRO")
new("pro_title", "Witness Pro", "Witness Pro", "Witness Pro", "Witness Pro", "Witness Pro")

# --------------------------------------------------------------- ProSettings
new("pro_unlock_title", "Unlock Witness Pro", "Sblocca Witness Pro", "Desbloquea Witness Pro",
    "Débloquer Witness Pro", "Witness Pro freischalten")
new("pro_unlock_subtitle", "4K, 60 fps and 10-bit HDR", "4K, 60 fps e HDR 10 bit", "4K, 60 fps y HDR de 10 bits",
    "4K, 60 i/s et HDR 10 bits", "4K, 60 fps und 10-Bit-HDR")
new("pro_unlock_footer",
    "Built for documentary and investigative work. Everything in the free app stays free, Pro only adds capture quality.",
    "Pensato per il lavoro documentaristico e investigativo. Tutto ciò che è gratuito nell'app resta gratuito, Pro aggiunge solo qualità di ripresa.",
    "Pensado para trabajo documental y de investigación. Todo lo que es gratis en la app sigue siéndolo, Pro solo añade calidad de captura.",
    "Conçu pour le travail documentaire et d'investigation. Tout ce qui est gratuit dans l'appli le reste, Pro n'ajoute que la qualité de prise de vue.",
    "Für Dokumentar- und Recherchearbeit gebaut. Alles, was in der kostenlosen App kostenlos ist, bleibt es — Pro ergänzt nur die Aufnahmequalität.")
src("pro_section_capture", "Camera")
src("pro_resolution", "Resolution")
src("pro_frame_rate", "Frame rate")
src("pro_codec", "Codec")
new("resolution_hd1080", "1080p HD", "1080p HD", "1080p HD", "1080p HD", "1080p HD")
new("resolution_uhd4k", "4K Ultra HD", "4K Ultra HD", "4K Ultra HD", "4K Ultra HD", "4K Ultra HD")
src("framerate_24", "24 fps (cinema)")
src("framerate_25", "25 fps (PAL)")
new("framerate_30", "30 fps", "30 fps", "30 fps", "30 i/s", "30 fps")
new("framerate_60", "60 fps", "60 fps", "60 fps", "60 i/s", "60 fps")
new("codec_h264", "H.264", "H.264", "H.264", "H.264", "H.264")
new("codec_hevc", "HEVC 10-bit", "HEVC a 10 bit", "HEVC de 10 bits", "HEVC 10 bits", "HEVC 10-Bit")
new("codec_mastering", "Mastering (local only)", "Mastering (solo locale)", "Masterización (solo local)",
    "Mastering (local uniquement)", "Mastering (nur lokal)")
src("codec_footer_h264",
    "H.264 is the most compatible and the lightest to upload. Best when getting footage off the device matters most.")
src("codec_footer_hevc",
    "HEVC 10-bit holds far more tonal detail than H.264 at a similar size, and still uploads live. The best balance for evidence you also want to grade.")
new("codec_footer_mastering",
    "Mastering records at a very high, fixed bit rate for maximum picture quality — far heavier than HEVC, and the closest thing on Android to the iOS build's ProRes tier (Android has no ProRes encoder at all, on any device). Recordings never leave this phone: they're too large to upload live, so this trades away the protection the rest of the app is built on — footage surviving because it left the device during the encounter. Choose Mastering when picture quality matters more than that.",
    "Mastering registra a un bitrate fisso molto elevato per la massima qualità dell'immagine, ben più pesante di HEVC, ed è la cosa più vicina su Android al livello ProRes di iOS (Android non ha alcun encoder ProRes, su nessun dispositivo). Le registrazioni non lasciano mai questo telefono: sono troppo pesanti per il caricamento in diretta, quindi si rinuncia alla protezione su cui si basa il resto dell'app, cioè che i filmati sopravvivano perché hanno lasciato il dispositivo durante l'episodio. Scegli Mastering quando la qualità dell'immagine conta più di questo.",
    "Mastering graba a un bitrate fijo muy alto para la máxima calidad de imagen, mucho más pesado que HEVC, y es lo más parecido en Android al nivel ProRes de iOS (Android no tiene ningún codificador ProRes, en ningún dispositivo). Las grabaciones nunca salen de este teléfono: son demasiado pesadas para subirlas en directo, así que se renuncia a la protección en la que se basa el resto de la app: que las grabaciones sobrevivan por haber salido del dispositivo durante el incidente. Elige Mastering cuando la calidad de imagen importe más que eso.",
    "Mastering enregistre à un débit fixe très élevé pour une qualité d'image maximale, bien plus lourd que HEVC, et c'est ce qui se rapproche le plus sur Android du niveau ProRes d'iOS (Android n'a aucun encodeur ProRes, sur aucun appareil). Les enregistrements ne quittent jamais ce téléphone : trop lourds pour un envoi en direct, ce choix renonce donc à la protection sur laquelle repose le reste de l'appli — des vidéos qui survivent parce qu'elles ont quitté l'appareil pendant l'incident. Choisissez Mastering quand la qualité d'image compte plus que cela.",
    "Mastering nimmt mit einer sehr hohen, festen Bitrate für maximale Bildqualität auf — deutlich schwerer als HEVC, und auf Android das Nächste an der ProRes-Stufe von iOS (Android hat auf keinem Gerät einen ProRes-Encoder). Aufnahmen verlassen dieses Telefon nie: Sie sind zu groß für einen Live-Upload, wodurch der Schutz aufgegeben wird, auf dem der Rest der App beruht — dass Aufnahmen überleben, weil sie das Gerät während des Vorfalls verlassen haben. Wähle Mastering, wenn Bildqualität wichtiger ist als das.")

new("pro_section_hdr", "10-bit HDR", "10-bit HDR", "10-bit HDR", "10-bit HDR", "10-bit HDR")
new("pro_hdr_toggle", "10-bit HDR", "HDR a 10 bit", "HDR de 10 bits", "HDR 10 bits", "10-Bit-HDR")
new("pro_hdr_footer_unsupported_device",
    "This device can't record 10-bit HDR — its camera doesn't report HLG10 support. Every other setting on this page still works.",
    "Questo dispositivo non può registrare HDR a 10 bit: la fotocamera non dichiara il supporto HLG10. Tutte le altre impostazioni di questa pagina funzionano comunque.",
    "Este dispositivo no puede grabar HDR de 10 bits: su cámara no declara compatibilidad con HLG10. El resto de ajustes de esta página siguen funcionando.",
    "Cet appareil ne peut pas enregistrer en HDR 10 bits : son appareil photo ne signale pas la prise en charge du HLG10. Tous les autres réglages de cette page fonctionnent toujours.",
    "Dieses Gerät kann kein 10-Bit-HDR aufnehmen — seine Kamera meldet keine HLG10-Unterstützung. Alle anderen Einstellungen auf dieser Seite funktionieren weiterhin.")
new("pro_hdr_footer_needs_codec",
    "10-bit HDR needs the HEVC or Mastering codec.",
    "L'HDR a 10 bit richiede il codec HEVC o Mastering.",
    "El HDR de 10 bits necesita el códec HEVC o Mastering.",
    "Le HDR 10 bits nécessite le codec HEVC ou Mastering.",
    "10-Bit-HDR benötigt den Codec HEVC oder Mastering.")
new("pro_hdr_footer_locked",
    "Records with the wider tonal range 10-bit HDR captures, for more latitude when grading in post. Unlock Witness Pro to use it.",
    "Registra con la gamma tonale più ampia catturata dall'HDR a 10 bit, per più margine in fase di color grading. Sblocca Witness Pro per usarlo.",
    "Graba con el rango tonal más amplio que capta el HDR de 10 bits, para más margen al etalonar después. Desbloquea Witness Pro para usarlo.",
    "Enregistre avec la gamme tonale plus large capturée par le HDR 10 bits, pour plus de marge à l'étalonnage. Débloquez Witness Pro pour l'utiliser.",
    "Nimmt mit dem breiteren Tonwertumfang auf, den 10-Bit-HDR erfasst — für mehr Spielraum bei der Farbkorrektur. Schalte Witness Pro frei, um es zu nutzen.")
new("pro_hdr_footer_on",
    "On. Footage keeps more shadow and highlight detail than standard 8-bit, at the cost of a heavier file.",
    "Attivo. I filmati conservano più dettaglio in ombre e alte luci rispetto all'8 bit standard, a costo di un file più pesante.",
    "Activado. Las grabaciones conservan más detalle en sombras y luces que el 8 bits estándar, a costa de un archivo más pesado.",
    "Activé. Les images conservent plus de détail dans les ombres et les hautes lumières qu'en 8 bits standard, au prix d'un fichier plus lourd.",
    "Ein. Aufnahmen behalten mehr Schatten- und Lichterdetails als Standard-8-Bit, auf Kosten einer schwereren Datei.")
new("pro_hdr_footer_off",
    "Records with the wider tonal range 10-bit HDR captures, for more latitude when grading in post.",
    "Registra con la gamma tonale più ampia catturata dall'HDR a 10 bit, per più margine in fase di color grading.",
    "Graba con el rango tonal más amplio que capta el HDR de 10 bits, para más margen al etalonar después.",
    "Enregistre avec la gamme tonale plus large capturée par le HDR 10 bits, pour plus de marge à l'étalonnage.",
    "Nimmt mit dem breiteren Tonwertumfang auf, den 10-Bit-HDR erfasst — für mehr Spielraum bei der Farbkorrektur.")

src("pro_section_weight", "How much it weighs")
src("pro_every_15s", "Every 15 seconds")
src("pro_every_minute", "Every minute")
src("pro_weight_footer",
    "Recording fills storage at this rate. Witness keeps recording when space runs low, but the device will eventually stop it.")

new("pro_local_only_label", "Stays on this device", "Resta su questo dispositivo", "Permanece en este dispositivo",
    "Reste sur cet appareil", "Bleibt auf diesem Gerät")

new("pro_single_camera_label", "Front camera is off", "La fotocamera anteriore è spenta",
    "La cámara frontal está apagada", "La caméra avant est désactivée", "Die Frontkamera ist aus")
new("pro_single_camera_footer",
    "Both cameras at once is limited to 1080p, 30 fps, H.264 and 8-bit. Anything above that takes priority and only the rear camera records.",
    "Entrambe le fotocamere insieme sono limitate a 1080p, 30 fps, H.264 e 8 bit. Qualsiasi impostazione superiore ha la priorità e registra solo la fotocamera posteriore.",
    "Ambas cámaras a la vez se limitan a 1080p, 30 fps, H.264 y 8 bits. Cualquier ajuste superior tiene prioridad y solo graba la cámara trasera.",
    "Les deux caméras à la fois sont limitées à 1080p, 30 i/s, H.264 et 8 bits. Tout réglage supérieur est prioritaire et seule la caméra arrière enregistre.",
    "Beide Kameras gleichzeitig sind auf 1080p, 30 fps, H.264 und 8-Bit begrenzt. Alles darüber hat Vorrang, und es nimmt nur die Rückkamera auf.")
new("pro_restore_dual_camera", "Restore dual-camera settings", "Ripristina le impostazioni doppia fotocamera",
    "Restaurar los ajustes de doble cámara", "Rétablir les réglages double caméra",
    "Zwei-Kamera-Einstellungen wiederherstellen")

src("pro_evidence_packages", "Evidence packages")
new("pro_evidence_packages_footer",
    "Turns a finished recording into a PDF report with its timeline, hashes and verification steps, alongside the manifest and time-stamp proof it describes.",
    "Trasforma una registrazione conclusa in un report PDF con cronologia, hash e passaggi di verifica, insieme al manifest e alla marca temporale che descrive.",
    "Convierte una grabación finalizada en un informe PDF con su cronología, hashes y pasos de verificación, junto con el manifiesto y la marca de tiempo que describe.",
    "Transforme un enregistrement terminé en un rapport PDF avec sa chronologie, ses empreintes et les étapes de vérification, accompagné du manifeste et de l'horodatage qu'il décrit.",
    "Verwandelt eine abgeschlossene Aufnahme in einen PDF-Bericht mit Zeitachse, Hashes und Prüfschritten, zusammen mit dem beschriebenen Manifest und Zeitstempelnachweis.")

new("pro_diagnostics", "Diagnostics", "Diagnostica", "Diagnóstico", "Diagnostic", "Diagnose")
new("pro_diagnostics_placeholder", "Start a recording once to see what the camera actually did.",
    "Avvia una registrazione per vedere cosa ha fatto effettivamente la fotocamera.",
    "Inicia una grabación para ver lo que la cámara hizo realmente.",
    "Lancez un enregistrement pour voir ce que la caméra a réellement fait.",
    "Starte einmal eine Aufnahme, um zu sehen, was die Kamera tatsächlich getan hat.")
new("pro_diagnostics_footer",
    "Read back from the camera itself, not from the settings above. If HDR is switched on but the camera didn't deliver 10-bit, this says which step didn't take.",
    "Letta direttamente dalla fotocamera, non dalle impostazioni sopra. Se l'HDR è attivo ma la fotocamera non ha fornito 10 bit, qui si vede quale passaggio non è andato a buon fine.",
    "Leída directamente de la cámara, no de los ajustes de arriba. Si el HDR está activado pero la cámara no entregó 10 bits, aquí se indica qué paso no se aplicó.",
    "Relu directement depuis la caméra, pas depuis les réglages ci-dessus. Si le HDR est activé mais que la caméra n'a pas fourni de 10 bits, ceci indique quelle étape n'a pas pris.",
    "Direkt von der Kamera zurückgelesen, nicht aus den obigen Einstellungen. Ist HDR aktiviert, hat die Kamera aber kein 10-Bit geliefert, zeigt dies, welcher Schritt nicht gegriffen hat.")

src("pro_manage_subscription", "Manage subscription")
new("pro_manage_subscription_footer",
    "Witness Pro is active. Manage or cancel it in your Play Store subscriptions.",
    "Witness Pro è attivo. Gestiscilo o annullalo dai tuoi abbonamenti Play Store.",
    "Witness Pro está activo. Gestíonalo o cancélalo en tus suscripciones de Play Store.",
    "Witness Pro est actif. Gérez-le ou annulez-le dans vos abonnements Play Store.",
    "Witness Pro ist aktiv. Verwalte oder kündige es in deinen Play Store-Abos.")

# --------------------------------------------------------------------- Paywall
new("paywall_subtitle", "Broadcast-grade capture, for documentary and investigative work.",
    "Ripresa di livello broadcast, per il lavoro documentaristico e investigativo.",
    "Captura de nivel de emisión, para trabajo documental y de investigación.",
    "Une prise de vue de niveau broadcast, pour le travail documentaire et d'investigation.",
    "Aufnahmen in Broadcast-Qualität für Dokumentar- und Recherchearbeit.")
new("paywall_fact_4k", "Record in 4K.", "Registra in 4K.", "Graba en 4K.", "Filmez en 4K.", "Nimm in 4K auf.")
new("paywall_fact_fps", "24, 25 and 60 fps.", "24, 25 e 60 fps.", "24, 25 y 60 fps.", "24, 25 et 60 i/s.", "24, 25 und 60 fps.")
new("paywall_fact_hdr", "10-bit HDR for more grading latitude.", "HDR a 10 bit per più margine in color grading.",
    "HDR de 10 bits para más margen al etalonar.", "HDR 10 bits pour plus de marge d'étalonnage.",
    "10-Bit-HDR für mehr Spielraum bei der Farbkorrektur.")
new("paywall_footer_note", "Everything in the free app stays free. Pro only adds capture quality.",
    "Tutto ciò che è gratuito nell'app resta gratuito. Pro aggiunge solo qualità di ripresa.",
    "Todo lo que es gratis en la app sigue siéndolo. Pro solo añade calidad de captura.",
    "Tout ce qui est gratuit dans l'appli le reste. Pro n'ajoute que la qualité de prise de vue.",
    "Alles, was in der kostenlosen App kostenlos ist, bleibt es. Pro ergänzt nur die Aufnahmequalität.")
new("paywall_price_per_month", "%s / month", "%s al mese", "%s al mes", "%s / mois", "%s / Monat")
src("paywall_restore", "Restore purchases")
new("paywall_store_unavailable", "The Play Store isn't reachable right now. Check your connection and try again.",
    "Il Play Store non è raggiungibile in questo momento. Controlla la connessione e riprova.",
    "El Play Store no está disponible ahora mismo. Comprueba tu conexión e inténtalo de nuevo.",
    "Le Play Store est actuellement injoignable. Vérifiez votre connexion et réessayez.",
    "Der Play Store ist gerade nicht erreichbar. Prüfe deine Verbindung und versuche es erneut.")
new("paywall_legal_footer",
    "Auto-renews monthly until cancelled. Cancel any time in your Play Store subscriptions.",
    "Si rinnova automaticamente ogni mese fino alla disdetta. Annulla in qualsiasi momento dai tuoi abbonamenti Play Store.",
    "Se renueva automáticamente cada mes hasta que se cancele. Cancela cuando quieras en tus suscripciones de Play Store.",
    "Renouvellement automatique mensuel jusqu'à annulation. Annulez à tout moment dans vos abonnements Play Store.",
    "Verlängert sich automatisch monatlich bis zur Kündigung. Jederzeit in deinen Play Store-Abos kündbar.")
src("legal_terms_of_use", "Terms of Use")
src("legal_privacy_policy", "Privacy Policy")

# ------------------------------------------------------------- Evidence export
src("evidence_title", "Evidence packages")
new("evidence_none_title", "No finished recordings", "Nessuna registrazione conclusa", "No hay grabaciones finalizadas",
    "Aucun enregistrement terminé", "Keine abgeschlossenen Aufnahmen")
src("evidence_none_body", "A record is kept here once a recording finishes and its manifest is written.")
src("evidence_footer", "A package contains a PDF report, the manifest.json it describes, and the time-stamp proof where one was obtained. It contains no video: the footage stays wherever you already keep it.")
src("evidence_unlock_footer", "Your recordings and their proofs are kept either way. Pro adds the report that turns them into something an attorney or an editor can read.")
src("evidence_export_subtitle", "Export evidence packages")
src("evidence_build_failed", "Couldn't build the package")
new("evidence_segments", "%d segments", "%d segmenti", "%d segmentos", "%d segments", "%d Segmente")
src("evidence_timestamped", "Time-stamped")
src("action_done", "Done")

# ---------------------------------------------------------------- Settings additions
new("settings_low_res_fallback", "Send a stand-in when uploads fall behind",
    "Invia una copia provvisoria quando i caricamenti restano indietro",
    "Enviar una copia provisional cuando las subidas se retrasen",
    "Envoyer un substitut quand les envois prennent du retard",
    "Bei Rückstand beim Hochladen einen Platzhalter senden")
new("settings_low_res_fallback_footer",
    "When uploads are visibly falling behind, Witness sends a small, low-quality copy of each new chunk ahead of the full-quality file, so something arrives fast even on a struggling connection. The full file follows and replaces it as soon as the network allows. Off by default costs nothing on a healthy connection: the stand-in is only ever produced when it's actually needed.",
    "Quando i caricamenti restano visibilmente indietro, Witness invia una copia piccola e di bassa qualità di ogni nuovo segmento prima del file completo, così qualcosa arriva rapidamente anche con una connessione in difficoltà. Il file completo segue e la sostituisce non appena la rete lo consente. Con connessione sana non costa nulla: la copia viene prodotta solo quando serve davvero.",
    "Cuando las subidas se retrasan visiblemente, Witness envía una copia pequeña y de baja calidad de cada segmento nuevo antes del archivo completo, para que algo llegue rápido incluso con una conexión con problemas. El archivo completo la sigue y la sustituye en cuanto la red lo permite. Con una conexión sana no cuesta nada: la copia solo se genera cuando realmente hace falta.",
    "Lorsque les envois prennent visiblement du retard, Witness envoie une petite copie de basse qualité de chaque nouveau segment avant le fichier complet, pour que quelque chose arrive vite même sur une connexion en difficulté. Le fichier complet suit et la remplace dès que le réseau le permet. Sur une bonne connexion, cela ne coûte rien : le substitut n'est produit qu'en cas de besoin réel.",
    "Wenn Uploads sichtbar in Rückstand geraten, sendet Witness vor der vollwertigen Datei eine kleine, minderwertige Kopie jedes neuen Abschnitts, damit bei einer schwachen Verbindung schnell etwas ankommt. Die vollwertige Datei folgt und ersetzt sie, sobald das Netz es zulässt. Bei guter Verbindung kostet das nichts: Der Platzhalter entsteht nur, wenn er wirklich gebraucht wird.")
