# -*- coding: utf-8 -*-
"""Upload queue, cloud-upload switch, Nextcloud sign-in, lifetime purchase.

Everything here comes from iOS work done after the last string sync, and the
iOS Localizable.xcstrings has no it/es/fr/de for any of it yet - a genuine gap
on the iOS side, not something skipped here. These are original translations,
matching the app's existing tone.
"""
import strings_part5  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------- upload queue
new("queue_title",
    "Upload queue", "Coda di caricamento", "Cola de subida",
    "File d'envoi", "Upload-Warteschlange")

# Singular and plural are separate strings rather than one with a count
# substituted in, so the last piece in the queue does not read "1 pieces
# remaining". Every language here has exactly two cardinal forms for this
# pattern, so two strings covers it.
new("queue_header_one",
    "1 piece remaining", "1 parte rimanente", "Queda 1 parte",
    "1 morceau restant", "Noch 1 Teil")
new("queue_header",
    "%1$d pieces remaining", "%1$d parti rimanenti", "Quedan %1$d partes",
    "%1$d morceaux restants", "Noch %1$d Teile")
new("queue_header_one_with_size",
    "1 piece remaining, %1$s", "1 parte rimanente, %1$s", "Queda 1 parte, %1$s",
    "1 morceau restant, %1$s", "Noch 1 Teil, %1$s")
new("queue_header_with_size",
    "%1$d pieces remaining, %2$s", "%1$d parti rimanenti, %2$s", "Quedan %1$d partes, %2$s",
    "%1$d morceaux restants, %2$s", "Noch %1$d Teile, %2$s")

new("queue_footer",
    "Pieces upload one at a time, oldest first, so each one finishes instead of all of them crawling together. Nothing is deleted from this phone until your storage confirms it arrived.",
    "Le parti vengono caricate una alla volta, dalla più vecchia, così ognuna arriva in fondo invece di procedere tutte insieme a rilento. Niente viene eliminato da questo telefono finché il tuo spazio di archiviazione non conferma di averlo ricevuto.",
    "Las partes se suben de una en una, de la más antigua a la más reciente, para que cada una termine en lugar de avanzar todas a la vez muy despacio. No se elimina nada de este teléfono hasta que tu almacenamiento confirma que llegó.",
    "Les morceaux sont envoyés un par un, du plus ancien au plus récent, pour que chacun aille au bout au lieu qu'ils avancent tous ensemble au ralenti. Rien n'est supprimé de ce téléphone tant que votre stockage n'a pas confirmé la réception.",
    "Die Teile werden nacheinander hochgeladen, das älteste zuerst, damit jedes fertig wird, statt dass alle gemeinsam kriechen. Nichts wird von diesem Telefon gelöscht, bevor dein Speicher den Eingang bestätigt hat.")
new("queue_footer_with_previews",
    "Small preview copies go up straight away, without waiting behind the full piece they stand in for. Full pieces go one at a time, oldest first, so each one finishes instead of all of them crawling together. Nothing is deleted from this phone until your storage confirms it arrived.",
    "Le piccole copie di anteprima partono subito, senza mettersi in coda dietro la parte completa che sostituiscono. Le parti complete vanno una alla volta, dalla più vecchia, così ognuna arriva in fondo invece di procedere tutte insieme a rilento. Niente viene eliminato da questo telefono finché il tuo spazio di archiviazione non conferma di averlo ricevuto.",
    "Las copias de vista previa, pequeñas, se envían enseguida, sin esperar detrás de la parte completa a la que sustituyen. Las partes completas van de una en una, de la más antigua a la más reciente, para que cada una termine en lugar de avanzar todas a la vez muy despacio. No se elimina nada de este teléfono hasta que tu almacenamiento confirma que llegó.",
    "Les petites copies d'aperçu partent immédiatement, sans attendre derrière le morceau complet qu'elles remplacent. Les morceaux complets partent un par un, du plus ancien au plus récent, pour que chacun aille au bout au lieu qu'ils avancent tous ensemble au ralenti. Rien n'est supprimé de ce téléphone tant que votre stockage n'a pas confirmé la réception.",
    "Kleine Vorschaukopien gehen sofort raus, ohne hinter dem vollständigen Teil zu warten, für das sie einstehen. Vollständige Teile gehen nacheinander, das älteste zuerst, damit jedes fertig wird, statt dass alle gemeinsam kriechen. Nichts wird von diesem Telefon gelöscht, bevor dein Speicher den Eingang bestätigt hat.")

new("queue_piece_rear",
    "Rear piece %1$d", "Parte posteriore %1$d", "Parte trasera %1$d",
    "Morceau arrière %1$d", "Rückseite, Teil %1$d")
new("queue_piece_front",
    "Front piece %1$d", "Parte frontale %1$d", "Parte frontal %1$d",
    "Morceau avant %1$d", "Frontkamera, Teil %1$d")
new("queue_piece_preview",
    "%1$s (preview copy)", "%1$s (copia di anteprima)", "%1$s (copia de vista previa)",
    "%1$s (copie d'aperçu)", "%1$s (Vorschaukopie)")

new("queue_percent_uploaded",
    "%1$d%% uploaded", "%1$d%% caricato", "%1$d%% subido",
    "%1$d%% envoyé", "%1$d%% hochgeladen")

new("queue_waiting_for_storage",
    "Waiting for your storage — retrying in %1$ds",
    "In attesa del tuo spazio di archiviazione — nuovo tentativo tra %1$d s",
    "Esperando a tu almacenamiento — se reintentará en %1$d s",
    "En attente de votre stockage — nouvel essai dans %1$d s",
    "Warten auf deinen Speicher — neuer Versuch in %1$d s")
new("queue_retrying_one",
    "Retrying in %1$ds, after 1 attempt",
    "Nuovo tentativo tra %1$d s, dopo 1 tentativo",
    "Se reintentará en %1$d s, tras 1 intento",
    "Nouvel essai dans %1$d s, après 1 tentative",
    "Neuer Versuch in %1$d s, nach 1 Versuch")
new("queue_retrying",
    "Retrying in %1$ds, after %2$d attempts",
    "Nuovo tentativo tra %1$d s, dopo %2$d tentativi",
    "Se reintentará en %1$d s, tras %2$d intentos",
    "Nouvel essai dans %1$d s, après %2$d tentatives",
    "Neuer Versuch in %1$d s, nach %2$d Versuchen")

new("queue_state_fingerprinting",
    "Fingerprinting", "Calcolo dell'impronta", "Calculando la huella",
    "Calcul de l'empreinte", "Fingerabdruck wird berechnet")
new("queue_state_waiting",
    "Waiting to upload", "In attesa di caricamento", "Esperando para subir",
    "En attente d'envoi", "Wartet auf Upload")
new("queue_state_starting",
    "Starting upload", "Avvio del caricamento", "Iniciando la subida",
    "Envoi en cours de démarrage", "Upload wird gestartet")
new("queue_state_uploaded",
    "Uploaded", "Caricato", "Subido", "Envoyé", "Hochgeladen")

new("queue_all_uploaded",
    "Everything has been uploaded", "È stato caricato tutto", "Se ha subido todo",
    "Tout a été envoyé", "Alles wurde hochgeladen")
new("queue_nothing_waiting",
    "Nothing is waiting on this phone.", "Non c'è nulla in attesa su questo telefono.",
    "No queda nada esperando en este teléfono.", "Rien n'est en attente sur ce téléphone.",
    "Auf diesem Telefon wartet nichts mehr.")

new("queue_recording_of",
    "Recording of %1$s", "Registrazione del %1$s", "Grabación del %1$s",
    "Enregistrement du %1$s", "Aufnahme vom %1$s")
new("queue_pieces_delivered",
    "Pieces delivered", "Parti consegnate", "Partes entregadas",
    "Morceaux livrés", "Zugestellte Teile")
new("queue_previews_sent",
    "Preview copies sent", "Copie di anteprima inviate", "Copias de vista previa enviadas",
    "Copies d'aperçu envoyées", "Gesendete Vorschaukopien")
new("queue_total",
    "Total", "Totale", "Total", "Total", "Gesamt")
new("queue_last_confirmed",
    "Last confirmed", "Ultima conferma", "Última confirmación",
    "Dernière confirmation", "Zuletzt bestätigt")
new("queue_combined_one",
    "Combined into one video", "Unite in un unico video", "Combinadas en un solo vídeo",
    "Réunis en une seule vidéo", "Zu einem Video zusammengefügt")
new("queue_combined_many",
    "Combined into %1$d videos", "Unite in %1$d video", "Combinadas en %1$d vídeos",
    "Réunis en %1$d vidéos", "Zu %1$d Videos zusammengefügt")

new("queue_delivered_footer",
    "Confirmed by your storage — these are on it now, not on their way.",
    "Confermato dal tuo spazio di archiviazione: questi file sono già lì, non in viaggio.",
    "Confirmado por tu almacenamiento: estos archivos ya están allí, no en camino.",
    "Confirmé par votre stockage : ces fichiers y sont déjà, pas en route.",
    "Von deinem Speicher bestätigt — diese Dateien liegen dort bereits, sie sind nicht unterwegs.")
new("queue_delivered_footer_merged",
    "Confirmed by your storage. The separate pieces have been replaced there by one video per camera, which is why the folder holds fewer files than the count above.",
    "Confermato dal tuo spazio di archiviazione. Le singole parti sono state sostituite lì da un video per fotocamera: per questo la cartella contiene meno file del conteggio qui sopra.",
    "Confirmado por tu almacenamiento. Las partes sueltas se han sustituido allí por un vídeo por cámara, por eso la carpeta contiene menos archivos que el recuento de arriba.",
    "Confirmé par votre stockage. Les morceaux séparés y ont été remplacés par une vidéo par caméra, c'est pourquoi le dossier contient moins de fichiers que le décompte ci-dessus.",
    "Von deinem Speicher bestätigt. Die einzelnen Teile wurden dort durch ein Video pro Kamera ersetzt — deshalb enthält der Ordner weniger Dateien als die Zahl oben.")

# --------------------------------------------------------------- cloud upload
new("settings_section_cloud_upload",
    "Cloud upload", "Caricamento nel cloud", "Subida a la nube",
    "Envoi vers le cloud", "Cloud-Upload")
new("settings_upload_new_recordings",
    "Upload new recordings", "Carica le nuove registrazioni", "Subir las grabaciones nuevas",
    "Envoyer les nouveaux enregistrements", "Neue Aufnahmen hochladen")
new("settings_cloud_upload_footer",
    "On, every recording is sent to your storage as you film. Off, new recordings stay on this phone only — they are still recorded, still saved to your gallery if that is on, and still hashed and timestamped.",
    "Attivo, ogni registrazione viene inviata al tuo spazio di archiviazione mentre filmi. Disattivo, le nuove registrazioni restano solo su questo telefono: vengono comunque registrate, comunque salvate nella galleria se l'opzione è attiva, e comunque sottoposte a hash e marcatura temporale.",
    "Activado, cada grabación se envía a tu almacenamiento mientras grabas. Desactivado, las grabaciones nuevas se quedan solo en este teléfono: se siguen grabando, se siguen guardando en tu galería si esa opción está activada, y se les sigue calculando el hash y la marca de tiempo.",
    "Activé, chaque enregistrement est envoyé vers votre stockage pendant que vous filmez. Désactivé, les nouveaux enregistrements restent uniquement sur ce téléphone : ils sont toujours enregistrés, toujours sauvegardés dans votre galerie si cette option est active, et toujours hachés et horodatés.",
    "An wird jede Aufnahme schon während des Filmens an deinen Speicher gesendet. Aus bleiben neue Aufnahmen nur auf diesem Telefon — sie werden trotzdem aufgezeichnet, trotzdem in deiner Galerie gespeichert, sofern das an ist, und trotzdem gehasht und mit Zeitstempel versehen.")
new("settings_cloud_upload_asymmetry_footer",
    "Turning this off never stops an upload already under way. Anything filmed while it was on keeps going until your storage confirms it, and no setting in this app can call that back — so taking the phone and switching this off cannot unsend what has already been recorded.",
    "Disattivarlo non interrompe mai un caricamento già in corso. Tutto ciò che è stato filmato mentre era attivo prosegue finché il tuo spazio di archiviazione non lo conferma, e nessuna impostazione di questa app può richiamarlo indietro: quindi prendere il telefono e disattivare questa opzione non può annullare l'invio di ciò che è già stato registrato.",
    "Desactivarlo nunca detiene una subida ya en curso. Todo lo grabado mientras estaba activado sigue adelante hasta que tu almacenamiento lo confirma, y ninguna opción de esta app puede retirarlo: por tanto, coger el teléfono y desactivar esto no puede deshacer el envío de lo ya grabado.",
    "Le désactiver n'arrête jamais un envoi déjà en cours. Tout ce qui a été filmé pendant qu'il était actif continue jusqu'à confirmation par votre stockage, et aucun réglage de cette appli ne peut le rappeler : prendre le téléphone et désactiver ceci ne peut donc pas annuler l'envoi de ce qui a déjà été enregistré.",
    "Das Ausschalten stoppt nie einen bereits laufenden Upload. Alles, was aufgenommen wurde, während es an war, läuft weiter, bis dein Speicher es bestätigt, und keine Einstellung in dieser App kann das zurückholen — wer das Telefon nimmt und dies ausschaltet, kann bereits Aufgenommenes also nicht ungeschehen machen.")

# ------------------------------------------------- dual camera ruled out by Pro
new("settings_dual_blocked_by",
    "Both cameras are off because of: %1$s.",
    "Entrambe le fotocamere sono disattivate a causa di: %1$s.",
    "Ambas cámaras están desactivadas por: %1$s.",
    "Les deux caméras sont désactivées à cause de : %1$s.",
    "Beide Kameras sind aus wegen: %1$s.")
new("settings_dual_blocked_title",
    "Both cameras need the free capture settings",
    "Entrambe le fotocamere richiedono le impostazioni di ripresa gratuite",
    "Ambas cámaras requieren los ajustes de captura gratuitos",
    "Les deux caméras nécessitent les réglages de capture gratuits",
    "Beide Kameras brauchen die kostenlosen Aufnahmeeinstellungen")
new("settings_dual_blocked_message",
    "Recording both cameras at once is limited to 1080p, 30 fps and H.264, and can't do 10-bit HDR. Change these in Witness Pro, or restore them here and the front camera switches back on by itself.",
    "La registrazione simultanea di entrambe le fotocamere è limitata a 1080p, 30 fps e H.264, e non supporta l'HDR a 10 bit. Modifica queste impostazioni in Witness Pro, oppure ripristinale qui e la fotocamera frontale si riattiverà da sola.",
    "Grabar con ambas cámaras a la vez está limitado a 1080p, 30 fps y H.264, y no admite HDR de 10 bits. Cambia estos ajustes en Witness Pro, o restáuralos aquí y la cámara frontal se volverá a activar sola.",
    "L'enregistrement simultané des deux caméras est limité à 1080p, 30 i/s et H.264, et ne prend pas en charge le HDR 10 bits. Modifiez ces réglages dans Witness Pro, ou restaurez-les ici et la caméra avant se réactivera d'elle-même.",
    "Die gleichzeitige Aufnahme beider Kameras ist auf 1080p, 30 fps und H.264 begrenzt und kann kein 10-Bit-HDR. Ändere diese Einstellungen in Witness Pro, oder stelle sie hier zurück — dann schaltet sich die Frontkamera von selbst wieder ein.")
new("settings_dual_blocked_restore",
    "Restore them", "Ripristinale", "Restaurarlos", "Les restaurer", "Zurückstellen")
new("settings_dual_blocked_leave",
    "Leave as is", "Lascia così", "Dejarlo así", "Laisser tel quel", "So lassen")

# ----------------------------------------------------------- Nextcloud sign-in
new("webdav_nextcloud_sign_in",
    "Sign in with Nextcloud", "Accedi con Nextcloud", "Iniciar sesión con Nextcloud",
    "Se connecter avec Nextcloud", "Mit Nextcloud anmelden")
new("webdav_nextcloud_footer",
    "If your address is a Nextcloud or ownCloud server, sign in on the server's own page instead. It issues Witness its own app password, so your account password is never typed here.",
    "Se il tuo indirizzo è un server Nextcloud o ownCloud, accedi invece sulla pagina del server stesso. Rilascerà a Witness una password per app dedicata, così la password del tuo account non viene mai digitata qui.",
    "Si tu dirección es un servidor Nextcloud u ownCloud, inicia sesión en la propia página del servidor. Este le emitirá a Witness su propia contraseña de aplicación, así la contraseña de tu cuenta nunca se escribe aquí.",
    "Si votre adresse est un serveur Nextcloud ou ownCloud, connectez-vous plutôt sur la page du serveur. Il délivrera à Witness son propre mot de passe d'application, votre mot de passe de compte n'est donc jamais saisi ici.",
    "Wenn deine Adresse ein Nextcloud- oder ownCloud-Server ist, melde dich stattdessen auf der Seite des Servers an. Er stellt Witness ein eigenes App-Passwort aus, dein Kontopasswort wird hier also nie eingetippt.")
new("webdav_nextcloud_waiting",
    "Waiting for you to finish signing in…", "In attesa che tu completi l'accesso…",
    "Esperando a que termines de iniciar sesión…", "En attente de la fin de votre connexion…",
    "Warte darauf, dass du die Anmeldung abschließt…")
new("webdav_nextcloud_failed",
    "That address didn't offer a Nextcloud sign-in page. Enter a username and password below instead.",
    "Quell'indirizzo non ha offerto una pagina di accesso Nextcloud. Inserisci invece nome utente e password qui sotto.",
    "Esa dirección no ofreció una página de inicio de sesión de Nextcloud. Introduce abajo un nombre de usuario y una contraseña.",
    "Cette adresse n'a pas proposé de page de connexion Nextcloud. Saisissez plutôt un nom d'utilisateur et un mot de passe ci-dessous.",
    "Diese Adresse hat keine Nextcloud-Anmeldeseite angeboten. Gib stattdessen unten Benutzername und Passwort ein.")

# ------------------------------------------------------------ lifetime purchase
new("paywall_lifetime_price",
    "%1$s once", "%1$s una tantum", "%1$s una sola vez",
    "%1$s une fois", "%1$s einmalig")
new("paywall_lifetime_footer",
    "Paid once, kept for good. Nothing renews and there is nothing to cancel.",
    "Si paga una volta sola e resta per sempre. Non si rinnova nulla e non c'è nulla da annullare.",
    "Se paga una sola vez y se conserva para siempre. No se renueva nada y no hay nada que cancelar.",
    "Payé une seule fois, conservé pour toujours. Rien ne se renouvelle et il n'y a rien à annuler.",
    "Einmal bezahlt, dauerhaft behalten. Nichts verlängert sich, und es gibt nichts zu kündigen.")
new("paywall_or",
    "or", "oppure", "o", "ou", "oder")

new("pro_store_diagnostics_header",
    "Diagnostics", "Diagnostica", "Diagnóstico", "Diagnostic", "Diagnose")
new("pro_store_diagnostics_footer",
    "What the Play Store returned when this app last asked for its products. A product that has not been activated, or that belongs to a release track this build did not come from, is simply absent from the reply rather than an error — which otherwise looks exactly like a missing button.",
    "Ciò che il Play Store ha restituito l'ultima volta che questa app ha richiesto i suoi prodotti. Un prodotto non attivato, o che appartiene a un canale di rilascio diverso da quello di questa build, risulta semplicemente assente dalla risposta anziché generare un errore: cosa che altrimenti appare identica a un pulsante mancante.",
    "Lo que devolvió Play Store la última vez que esta app pidió sus productos. Un producto que no se ha activado, o que pertenece a un canal de publicación distinto del de esta compilación, simplemente falta en la respuesta en lugar de dar un error, algo que de otro modo se ve igual que un botón que falta.",
    "Ce que le Play Store a renvoyé la dernière fois que cette appli a demandé ses produits. Un produit non activé, ou appartenant à un canal de publication dont cette version ne provient pas, est simplement absent de la réponse plutôt qu'une erreur — ce qui, sinon, ressemble exactement à un bouton manquant.",
    "Was der Play Store zurückgegeben hat, als diese App zuletzt nach ihren Produkten gefragt hat. Ein Produkt, das nicht aktiviert wurde oder zu einem Release-Kanal gehört, aus dem dieser Build nicht stammt, fehlt in der Antwort schlicht, statt einen Fehler zu erzeugen — was sonst genau wie ein fehlender Knopf aussieht.")
new("pro_lifetime_active",
    "Unlocked by your one-time purchase. There is nothing to renew or cancel.",
    "Sbloccato dal tuo acquisto una tantum. Non c'è nulla da rinnovare o annullare.",
    "Desbloqueado por tu compra única. No hay nada que renovar ni cancelar.",
    "Débloqué par votre achat unique. Il n'y a rien à renouveler ni à annuler.",
    "Durch deinen Einmalkauf freigeschaltet. Es gibt nichts zu verlängern oder zu kündigen.")
