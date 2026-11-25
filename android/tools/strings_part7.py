# -*- coding: utf-8 -*-
"""Home viewfinder toggle + dual-camera swap, Pro upsell prompt, in-app Help
page, upload-queue root-cause banner, and the two new Pro capture features
(visible timestamp/GPS burn-in, combined picture-in-picture export).

Everything here comes from iOS work done after the last string sync (or,
for the Help page and the picture-in-picture export, has no iOS
Localizable.xcstrings entry at all - both are Android-only). Checked
against the catalog directly rather than assumed: a handful of strings that
do exist there (e.g. "Show viewfinder") still have zero it/es/fr/de
translations yet, a genuine gap on the iOS side. Everything below is an
original translation, matching the app's existing tone.
"""
import strings_part6  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------- home viewfinder toggle
new("home_show_viewfinder",
    "Show viewfinder", "Mostra mirino", "Mostrar visor", "Afficher le viseur", "Sucher anzeigen")
new("home_blackout_hint_viewfinder",
    "Recording shows a live preview.\nTriple-tap, then hold the stop icon to end.",
    "La registrazione mostra un'anteprima dal vivo.\nTocca tre volte, poi tieni premuta l'icona di stop per terminare.",
    "La grabación muestra una vista previa en directo.\nToca tres veces y luego mantén pulsado el icono de detener para terminar.",
    "L'enregistrement affiche un aperçu en direct.\nTriple appui, puis maintenez l'icône stop pour terminer.",
    "Die Aufnahme zeigt eine Live-Vorschau.\nDreimal tippen, dann das Stopp-Symbol gedrückt halten, um zu beenden.")

# ------------------------------------------------------------- dual-camera swap
new("recording_swap_main_view",
    "Swap main view", "Scambia vista principale", "Cambiar vista principal",
    "Permuter la vue principale", "Hauptansicht tauschen")
new("recording_swap_to_front",
    "Showing rear camera as main view. Tap to switch to front.",
    "Vista principale: fotocamera posteriore. Tocca per passare alla anteriore.",
    "Vista principal: cámara trasera. Toca para cambiar a la delantera.",
    "Vue principale : caméra arrière. Appuyez pour passer à l'avant.",
    "Hauptansicht: Rückkamera. Tippen, um zur Frontkamera zu wechseln.")
new("recording_swap_to_rear",
    "Showing front camera as main view. Tap to switch back to rear.",
    "Vista principale: fotocamera anteriore. Tocca per tornare alla posteriore.",
    "Vista principal: cámara delantera. Toca para volver a la trasera.",
    "Vue principale : caméra avant. Appuyez pour revenir à l'arrière.",
    "Hauptansicht: Frontkamera. Tippen, um zur Rückkamera zurückzuwechseln.")

# ------------------------------------------------------------ upload queue banner
new("queue_root_cause_title",
    "Nothing can upload right now", "Al momento non si può caricare nulla",
    "Ahora mismo no se puede subir nada", "Rien ne peut être envoyé pour l'instant",
    "Im Moment kann nichts hochgeladen werden")

# ------------------------------------------------------------------- Pro upsell
new("pro_upsell_title",
    "Want to film in 4K?", "Vuoi filmare in 4K?", "¿Quieres grabar en 4K?",
    "Envie de filmer en 4K ?", "In 4K filmen?")
new("pro_upsell_message",
    "Witness Pro unlocks 4K, higher frame rates, and 10-bit HDR — and helps fund the app. %1$s",
    "Witness Pro sblocca il 4K, frame rate più alti e HDR a 10 bit — e aiuta a finanziare l'app. %1$s",
    "Witness Pro desbloquea 4K, frecuencias de fotogramas más altas y HDR de 10 bits, y ayuda a financiar la app. %1$s",
    "Witness Pro débloque la 4K, des fréquences d'images plus élevées et le HDR 10 bits — et aide à financer l'app. %1$s",
    "Witness Pro schaltet 4K, höhere Bildraten und 10-Bit-HDR frei – und unterstützt die App. %1$s")
new("pro_upsell_see_pro",
    "See Witness Pro", "Scopri Witness Pro", "Ver Witness Pro", "Voir Witness Pro", "Witness Pro ansehen")
new("pro_upsell_not_now",
    "Not now", "Non ora", "Ahora no", "Pas maintenant", "Jetzt nicht")

# ------------------------------------------------------- Pro: timestamp burn-in
new("pro_timestamp_burn_in_toggle",
    "Visible timestamp & location", "Data/ora e posizione visibili",
    "Fecha/hora y ubicación visibles", "Horodatage et position visibles",
    "Sichtbarer Zeitstempel & Standort")
new("pro_timestamp_burn_in_footer",
    "Burns the date, time and coordinates into the picture itself, visible to anyone who watches the video — for outlets that want dashcam-style on-screen provenance. This is separate from the invisible metadata every recording already carries, on or off: the SHA-256 hash, the chain-of-custody manifest, and the independent RFC 3161 timestamp. Turning this off doesn't remove any of that. Not available together with 10-bit HDR.",
    "Incide data, ora e coordinate direttamente nell'immagine, visibili a chiunque guardi il video — per chi desidera una prova visiva in stile dashcam. È indipendente dai metadati invisibili che ogni registrazione porta comunque, attivo o no: l'hash SHA-256, il verbale della catena di custodia e la marca temporale RFC 3161 indipendente. Disattivarlo non toglie nulla di tutto ciò. Non disponibile insieme all'HDR a 10 bit.",
    "Graba la fecha, la hora y las coordenadas directamente en la imagen, visibles para cualquiera que vea el vídeo, pensado para medios que quieren una procedencia visible al estilo dashcam. Es independiente de los metadatos invisibles que ya lleva toda grabación, esté activado o no: el hash SHA-256, el acta de cadena de custodia y el sello de tiempo RFC 3161 independiente. Desactivarlo no elimina nada de eso. No disponible junto con HDR de 10 bits.",
    "Incruste la date, l'heure et les coordonnées directement dans l'image, visibles par quiconque regarde la vidéo — pour les médias qui veulent une provenance visible façon dashcam. C'est indépendant des métadonnées invisibles que tout enregistrement porte déjà, activé ou non : le hash SHA-256, le procès-verbal de chaîne de possession et l'horodatage RFC 3161 indépendant. Le désactiver ne retire rien de tout cela. Non disponible avec le HDR 10 bits.",
    "Brennt Datum, Uhrzeit und Koordinaten direkt ins Bild ein, sichtbar für jeden, der das Video ansieht – für Redaktionen, die eine dashcam-artige, sichtbare Herkunftsangabe wollen. Das ist unabhängig von den unsichtbaren Metadaten, die jede Aufnahme ohnehin trägt, ob an oder aus: der SHA-256-Hash, das Beweiskettenprotokoll und der unabhängige RFC-3161-Zeitstempel. Das Ausschalten entfernt nichts davon. Nicht zusammen mit 10-Bit-HDR verfügbar.")

# ------------------------------------------------------------ Pro: PiP export
new("pro_pip_export_toggle",
    "Combined picture-in-picture video", "Video combinato picture-in-picture",
    "Vídeo combinado en pantalla dentro de pantalla", "Vidéo combinée incrustation d'image",
    "Kombiniertes Bild-in-Bild-Video")
new("pro_pip_export_footer",
    "When recording both cameras, also produce one video with the front camera as a small inset over the rear one — in addition to, never instead of, the two separate videos you already get. Meant for sharing a single file rather than two. Adds noticeably to how long the recording takes to finish assembling afterward.",
    "Quando registri con entrambe le fotocamere, produce anche un video con la fotocamera anteriore in un piccolo riquadro sopra quella posteriore — in aggiunta, mai al posto, ai due video separati che ottieni già. Pensato per condividere un unico file anziché due. Allunga sensibilmente il tempo necessario a completare l'assemblaggio della registrazione.",
    "Al grabar con ambas cámaras, produce también un vídeo con la cámara delantera como pequeño recuadro sobre la trasera — además de, nunca en lugar de, los dos vídeos independientes que ya obtienes. Pensado para compartir un único archivo en vez de dos. Alarga notablemente el tiempo que tarda la grabación en terminar de montarse.",
    "Lors d'un enregistrement avec les deux caméras, produit aussi une vidéo avec la caméra avant en petit encart sur l'arrière — en plus des, jamais à la place des, deux vidéos séparées déjà obtenues. Pensé pour partager un seul fichier plutôt que deux. Allonge nettement le temps que prend l'enregistrement à finir de s'assembler.",
    "Beim Aufnehmen mit beiden Kameras wird zusätzlich ein Video mit der Frontkamera als kleinem Bild über der Rückkamera erstellt — zusätzlich zu, nie anstelle der, zwei separaten Videos, die du bereits bekommst. Gedacht zum Teilen einer einzigen Datei statt zweier. Verlängert spürbar, wie lange die Aufnahme danach zum Fertigstellen braucht.")

# ---------------------------------------------------------------- Paywall facts
new("paywall_fact_timestamp",
    "Visible timestamp and location, burned into the picture.",
    "Data/ora e posizione visibili, incise nell'immagine.",
    "Fecha/hora y ubicación visibles, grabadas en la imagen.",
    "Horodatage et position visibles, incrustés dans l'image.",
    "Sichtbarer Zeitstempel und Standort, ins Bild eingebrannt.")
new("paywall_fact_pip",
    "Both cameras combined into one video.",
    "Entrambe le fotocamere unite in un unico video.",
    "Ambas cámaras combinadas en un solo vídeo.",
    "Les deux caméras combinées en une seule vidéo.",
    "Beide Kameras zu einem Video kombiniert.")

# ------------------------------------------------------------------- Help page
new("help_title",
    "Finding your footage", "Trovare le tue registrazioni", "Encontrar tus grabaciones",
    "Retrouver vos enregistrements", "Deine Aufnahmen finden")
new("help_no_delete_button",
    "Witness has no delete button.", "Witness non ha un pulsante elimina.",
    "Witness no tiene botón para eliminar.", "Witness n'a pas de bouton supprimer.",
    "Witness hat keine Löschtaste.")
new("help_no_delete_body",
    "It removes its own copy of a piece of footage only after your storage account has confirmed that the piece arrived. If a recording cannot be saved anywhere yet, it stays on this phone and Witness tries again every time you open the app.",
    "Rimuove la propria copia di una parte della registrazione solo dopo che il tuo account di archiviazione ha confermato che è arrivata. Se una registrazione non può ancora essere salvata da nessuna parte, resta su questo telefono e Witness riprova ogni volta che apri l'app.",
    "Elimina su propia copia de una parte de la grabación solo después de que tu cuenta de almacenamiento haya confirmado que llegó. Si una grabación aún no puede guardarse en ningún sitio, permanece en este teléfono y Witness lo vuelve a intentar cada vez que abres la app.",
    "Il ne supprime sa propre copie d'un morceau qu'une fois que votre compte de stockage a confirmé son arrivée. Si un enregistrement ne peut encore être sauvegardé nulle part, il reste sur ce téléphone et Witness réessaie à chaque ouverture de l'app.",
    "Die eigene Kopie eines Aufnahmeteils wird erst entfernt, nachdem dein Speicherkonto den Eingang bestätigt hat. Kann eine Aufnahme noch nirgendwo gespeichert werden, bleibt sie auf diesem Telefon, und Witness versucht es bei jedem Öffnen der App erneut.")
new("help_rarely_lost",
    "Recordings are almost never lost. They are usually somewhere you have not looked yet.",
    "Le registrazioni quasi non si perdono mai. Di solito sono da qualche parte che non hai ancora controllato.",
    "Las grabaciones casi nunca se pierden. Normalmente están en algún sitio donde aún no has mirado.",
    "Les enregistrements ne sont presque jamais perdus. Ils se trouvent généralement là où vous n'avez pas encore regardé.",
    "Aufnahmen gehen fast nie verloren. Sie liegen meist irgendwo, wo du noch nicht nachgesehen hast.")

new("help_section_just_lost",
    "If you think you just lost one", "Se pensi di averne appena persa una",
    "Si crees que acabas de perder una", "Si vous pensez venir d'en perdre un",
    "Wenn du glaubst, gerade eine verloren zu haben")
new("help_closed_before_stop_title",
    "Closed the app, or the phone locked, before you pressed stop?",
    "Hai chiuso l'app, o il telefono si è bloccato, prima di premere stop?",
    "¿Cerraste la app, o el teléfono se bloqueó, antes de pulsar detener?",
    "L'app s'est fermée, ou le téléphone s'est verrouillé, avant que vous n'appuyiez sur stop ?",
    "App geschlossen oder Telefon gesperrt, bevor du auf Stopp getippt hast?")
new("help_closed_before_stop_1",
    "That already finished the recording. Witness treats the app closing — force-quit, a lock screen, the phone dying — exactly like pressing stop: whatever was already uploaded is safe, and the rest resumes the moment you reopen Witness. Recording keeps running in the background here, unlike on iPhone, so the app closing rarely cuts a recording short at all.",
    "Quella registrazione è già conclusa. Witness tratta la chiusura dell'app — forzata, blocco schermo, telefono scarico — esattamente come premere stop: tutto ciò che era già stato caricato è al sicuro, e il resto riprende non appena riapri Witness. Qui la registrazione continua in background, a differenza dell'iPhone, quindi chiudere l'app raramente interrompe davvero una registrazione.",
    "Esa grabación ya terminó. Witness trata el cierre de la app —forzado, bloqueo de pantalla, que se apague el teléfono— exactamente igual que pulsar detener: lo que ya se había subido está a salvo, y el resto continúa en cuanto vuelves a abrir Witness. Aquí la grabación sigue en segundo plano, a diferencia del iPhone, así que cerrar la app casi nunca interrumpe de verdad una grabación.",
    "Cet enregistrement est déjà terminé. Witness traite la fermeture de l'app — forcée, verrouillage de l'écran, téléphone à plat — exactement comme un appui sur stop : tout ce qui était déjà envoyé est en sécurité, et le reste reprend dès que vous rouvrez Witness. Ici, l'enregistrement continue en arrière-plan, contrairement à l'iPhone, donc fermer l'app interrompt rarement un enregistrement pour de bon.",
    "Diese Aufnahme ist bereits beendet. Witness behandelt das Schließen der App – erzwungen, Bildschirmsperre, leerer Akku – genau wie das Drücken von Stopp: Alles bereits Hochgeladene ist sicher, der Rest läuft weiter, sobald du Witness wieder öffnest. Die Aufnahme läuft hier im Hintergrund weiter, anders als beim iPhone – das Schließen der App unterbricht eine Aufnahme daher nur selten wirklich.")
new("help_closed_before_stop_2",
    "So reopen Witness now. Check the main screen: if an upload count is showing, that recording is on its way. Then look in your cloud folder and gallery, below.",
    "Quindi riapri subito Witness. Controlla la schermata principale: se compare un conteggio dei caricamenti, quella registrazione è in viaggio. Poi guarda nella tua cartella cloud e nella galleria, qui sotto.",
    "Así que vuelve a abrir Witness ahora. Revisa la pantalla principal: si aparece un contador de subida, esa grabación va en camino. Después mira en tu carpeta en la nube y en la galería, más abajo.",
    "Rouvrez donc Witness maintenant. Vérifiez l'écran principal : si un compteur d'envoi s'affiche, cet enregistrement est en route. Regardez ensuite dans votre dossier cloud et votre galerie, ci-dessous.",
    "Öffne Witness jetzt wieder. Sieh auf dem Hauptbildschirm nach: Wird eine Upload-Zahl angezeigt, ist diese Aufnahme unterwegs. Schau dann in deinen Cloud-Ordner und deine Galerie weiter unten.")
new("help_closed_before_stop_3",
    "Still not there? Witness keeps every piece privately on this phone until your storage confirms it — nothing is deleted automatically — but that storage is not reachable through a file manager the way a normal folder is, so email us (below) rather than trying to browse for it yourself.",
    "Ancora non la trovi? Witness conserva ogni parte in modo privato su questo telefono finché il tuo spazio di archiviazione non la conferma — niente viene eliminato automaticamente — ma quello spazio non è raggiungibile tramite un file manager come una cartella normale, quindi scrivici (qui sotto) invece di provare a cercarla da solo.",
    "¿Sigue sin aparecer? Witness guarda cada parte de forma privada en este teléfono hasta que tu almacenamiento la confirma — nada se elimina automáticamente — pero ese almacenamiento no es accesible desde un gestor de archivos como una carpeta normal, así que escríbenos (más abajo) en vez de intentar buscarla tú mismo.",
    "Toujours introuvable ? Witness conserve chaque morceau de façon privée sur ce téléphone jusqu'à ce que votre stockage le confirme — rien n'est supprimé automatiquement — mais ce stockage n'est pas accessible via un gestionnaire de fichiers comme un dossier normal ; écrivez-nous plutôt (ci-dessous) que d'essayer de le retrouver vous-même.",
    "Immer noch nicht da? Witness bewahrt jedes Teil privat auf diesem Telefon auf, bis dein Speicher es bestätigt – nichts wird automatisch gelöscht –, aber dieser Speicher ist nicht wie ein gewöhnlicher Ordner über eine Dateiverwaltung erreichbar. Schreib uns daher lieber (unten), statt selbst danach zu suchen.")

new("help_section_where",
    "Where a recording goes", "Dove finisce una registrazione", "Adónde va una grabación",
    "Où va un enregistrement", "Wohin eine Aufnahme geht")
new("help_dest_cloud_title",
    "Your cloud folder", "La tua cartella cloud", "Tu carpeta en la nube",
    "Votre dossier cloud", "Dein Cloud-Ordner")
new("help_dest_cloud_body",
    "Uploaded while you film, in 15-second pieces, one at a time. Each session gets its own folder named after the date, time and place.",
    "Caricata mentre filmi, in parti da 15 secondi, una alla volta. Ogni sessione ha una propria cartella, nominata con data, ora e luogo.",
    "Se sube mientras filmas, en partes de 15 segundos, una a la vez. Cada sesión tiene su propia carpeta, con nombre según la fecha, la hora y el lugar.",
    "Envoyée pendant que vous filmez, par morceaux de 15 secondes, un par un. Chaque session a son propre dossier, nommé d'après la date, l'heure et le lieu.",
    "Wird während des Filmens in 15-Sekunden-Teilen hochgeladen, eins nach dem anderen. Jede Sitzung erhält einen eigenen Ordner, benannt nach Datum, Uhrzeit und Ort.")
new("help_dest_gallery_title",
    "Your gallery", "La tua galleria", "Tu galería", "Votre galerie", "Deine Galerie")
new("help_dest_gallery_body",
    "One video per camera for each session, saved to a Witness folder in your gallery, if “Save recordings to gallery” is on in Settings.",
    "Un video per fotocamera per ogni sessione, salvato in una cartella Witness nella tua galleria, se in Impostazioni “Salva registrazioni in galleria” è attivo.",
    "Un vídeo por cámara para cada sesión, guardado en una carpeta Witness de tu galería, si “Guardar grabaciones en la galería” está activado en Ajustes.",
    "Une vidéo par caméra pour chaque session, enregistrée dans un dossier Witness de votre galerie, si « Enregistrer dans la galerie » est activé dans les Réglages.",
    "Ein Video pro Kamera für jede Sitzung, gespeichert in einem Witness-Ordner deiner Galerie, sofern „Aufnahmen in Galerie speichern“ in den Einstellungen aktiviert ist.")

new("help_section_questions",
    "Common questions", "Domande frequenti", "Preguntas frecuentes",
    "Questions fréquentes", "Häufige Fragen")

new("help_q_not_in_gallery",
    "It isn't in my gallery", "Non è nella mia galleria", "No está en mi galería",
    "Elle n'est pas dans ma galerie", "Es ist nicht in meiner Galerie")
new("help_a_not_in_gallery_1",
    "Open Settings and check that “Save recordings to gallery” is switched on. It only affects recordings made after you turn it on.",
    "Apri Impostazioni e controlla che “Salva registrazioni in galleria” sia attivo. Riguarda solo le registrazioni fatte dopo averlo attivato.",
    "Abre Ajustes y comprueba que “Guardar grabaciones en la galería” esté activado. Solo afecta a las grabaciones hechas después de activarlo.",
    "Ouvrez les Réglages et vérifiez que « Enregistrer dans la galerie » est activé. Cela ne concerne que les enregistrements faits après l'avoir activé.",
    "Öffne die Einstellungen und prüfe, ob „Aufnahmen in Galerie speichern“ aktiviert ist. Das betrifft nur Aufnahmen, die nach dem Aktivieren gemacht wurden.")
new("help_a_not_in_gallery_2",
    "After a long recording, give it a moment: the pieces are combined the instant you stop, so it should appear within a few seconds.",
    "Dopo una registrazione lunga, dai qualche istante: le parti vengono unite nell'istante in cui premi stop, quindi dovrebbe comparire in pochi secondi.",
    "Después de una grabación larga, dale un momento: las partes se combinan en cuanto pulsas detener, así que debería aparecer en unos segundos.",
    "Après un long enregistrement, laissez-lui un instant : les morceaux sont assemblés dès que vous appuyez sur stop, elle devrait donc apparaître en quelques secondes.",
    "Gib einer langen Aufnahme einen Moment: Die Teile werden in dem Augenblick zusammengefügt, in dem du stoppst, sie sollte also nach wenigen Sekunden erscheinen.")

new("help_q_not_in_cloud",
    "It isn't in my cloud folder", "Non è nella mia cartella cloud", "No está en mi carpeta en la nube",
    "Elle n'est pas dans mon dossier cloud", "Es ist nicht in meinem Cloud-Ordner")
new("help_a_not_in_cloud_1",
    "Tap the orange upload count on the main screen. That shows the queue itself: every piece still waiting, how far the current one has got, and what has already arrived.",
    "Tocca il conteggio arancione sulla schermata principale. Mostra la coda stessa: ogni parte ancora in attesa, a che punto è quella in corso e cosa è già arrivato.",
    "Toca el contador naranja de la pantalla principal. Muestra la propia cola: cada parte que sigue esperando, cuánto lleva la actual y qué ha llegado ya.",
    "Appuyez sur le compteur orange de l'écran principal. Il affiche la file elle-même : chaque morceau encore en attente, l'avancement de celui en cours, et ce qui est déjà arrivé.",
    "Tippe auf die orange Upload-Zahl auf dem Hauptbildschirm. Sie zeigt die Warteschlange selbst: jedes noch wartende Teil, wie weit das aktuelle ist, und was bereits angekommen ist.")
new("help_a_not_in_cloud_2",
    "The most common cause by far is a storage account with no room left. Witness cannot create the session folder, and everything stops behind it. Free up space, or connect a different account, and the queue starts moving again on its own — nothing is lost while it waits.",
    "La causa di gran lunga più comune è un account di archiviazione senza più spazio. Witness non riesce a creare la cartella della sessione, e tutto resta bloccato dietro. Libera spazio, o collega un altro account, e la coda riparte da sola — niente va perso nel frattempo.",
    "La causa más habitual con diferencia es una cuenta de almacenamiento sin espacio. Witness no puede crear la carpeta de la sesión, y todo se detiene detrás de eso. Libera espacio, o conecta otra cuenta, y la cola se pondrá en marcha sola — no se pierde nada mientras espera.",
    "La cause la plus fréquente de loin est un compte de stockage sans place restante. Witness ne peut pas créer le dossier de session, et tout reste bloqué derrière. Libérez de l'espace, ou connectez un autre compte, et la file repart d'elle-même — rien n'est perdu en attendant.",
    "Die mit Abstand häufigste Ursache ist ein Speicherkonto ohne freien Platz. Witness kann den Sitzungsordner nicht anlegen, und alles dahinter stockt. Schaffe Platz oder verbinde ein anderes Konto, dann läuft die Warteschlange von selbst wieder an – währenddessen geht nichts verloren.")
new("help_a_not_in_cloud_3",
    "The next most common cause is the connection. Pieces upload one at a time so that each one finishes, and when the network is struggling a small preview copy of each piece goes up first. A folder holding previews is not a failure; the full-quality files follow and replace them.",
    "La seconda causa più comune è la connessione. Le parti vengono caricate una alla volta perché ognuna arrivi in fondo, e quando la rete è in difficoltà viene inviata prima una piccola copia di anteprima di ogni parte. Una cartella con delle anteprime non è un errore; i file a piena qualità seguono e le sostituiscono.",
    "La segunda causa más común es la conexión. Las partes se suben de una en una para que cada una termine, y cuando la red va mal se envía antes una pequeña copia de vista previa de cada parte. Una carpeta con vistas previas no es un fallo; los archivos de calidad completa llegan después y las sustituyen.",
    "La deuxième cause la plus fréquente est la connexion. Les morceaux sont envoyés un par un pour que chacun aille au bout, et quand le réseau peine, une petite copie d'aperçu de chaque morceau part d'abord. Un dossier contenant des aperçus n'est pas un échec ; les fichiers pleine qualité suivent et les remplacent.",
    "Die zweithäufigste Ursache ist die Verbindung. Teile werden nacheinander hochgeladen, damit jedes fertig wird, und wenn das Netz schwächelt, geht zuerst eine kleine Vorschaukopie jedes Teils raus. Ein Ordner mit Vorschauen ist kein Fehler; die Dateien in voller Qualität folgen und ersetzen sie.")

new("help_q_taken_mid_recording",
    "The app closed, or the phone was taken, mid-recording",
    "L'app si è chiusa, o il telefono è stato preso, durante la registrazione",
    "La app se cerró, o el teléfono fue arrebatado, a mitad de grabación",
    "L'app s'est fermée, ou le téléphone a été pris, en pleine capture",
    "Die App hat sich geschlossen, oder das Telefon wurde mitten in der Aufnahme weggenommen")
new("help_a_taken_mid_recording_1",
    "Every 15 seconds Witness closes the piece it is writing and starts a new one, so the recording is never held hostage by a file that has not finished. Whatever has already uploaded is beyond the reach of the phone entirely.",
    "Ogni 15 secondi Witness chiude la parte che sta scrivendo e ne inizia una nuova, così la registrazione non è mai ostaggio di un file non ancora completato. Tutto ciò che è già stato caricato è del tutto fuori dalla portata del telefono.",
    "Cada 15 segundos Witness cierra la parte que está escribiendo y empieza una nueva, así la grabación nunca queda rehén de un archivo sin terminar. Lo que ya se subió está totalmente fuera del alcance del teléfono.",
    "Toutes les 15 secondes, Witness referme le morceau en cours d'écriture et en commence un nouveau, si bien que l'enregistrement n'est jamais otage d'un fichier inachevé. Tout ce qui est déjà envoyé est totalement hors de portée du téléphone.",
    "Alle 15 Sekunden schließt Witness das gerade geschriebene Teil ab und beginnt ein neues, sodass die Aufnahme nie von einer unvollständigen Datei abhängt. Bereits Hochgeladenes ist dem Telefon vollständig entzogen.")
new("help_a_taken_mid_recording_2",
    "Recording itself keeps running even with the app closed or the screen off — it only stops if the phone is switched off, or the recording is force-stopped from Android's own app settings. At worst you lose the few seconds that were still being written when that happened.",
    "La registrazione continua anche ad app chiusa o schermo spento — si interrompe solo se il telefono viene spento o la registrazione viene forzatamente arrestata dalle impostazioni app di Android. Nel peggiore dei casi perdi solo i pochi secondi che si stavano ancora scrivendo in quel momento.",
    "La grabación sigue funcionando aunque la app esté cerrada o la pantalla apagada — solo se detiene si apagas el teléfono o forzas la detención de la grabación desde los ajustes de aplicaciones de Android. En el peor de los casos pierdes solo los pocos segundos que se estaban escribiendo en ese momento.",
    "L'enregistrement continue même avec l'app fermée ou l'écran éteint — il ne s'arrête que si vous éteignez le téléphone ou forcez l'arrêt de l'enregistrement depuis les paramètres d'applications d'Android. Au pire, vous perdez les quelques secondes encore en cours d'écriture à ce moment-là.",
    "Die Aufnahme läuft auch bei geschlossener App oder ausgeschaltetem Bildschirm weiter – sie stoppt nur, wenn das Telefon ausgeschaltet oder die Aufnahme über die App-Einstellungen von Android erzwungen beendet wird. Schlimmstenfalls gehen nur die wenigen Sekunden verloren, die gerade geschrieben wurden.")

new("help_q_pieces",
    "Why is my recording in pieces instead of one video?",
    "Perché la mia registrazione è divisa in parti invece di un unico video?",
    "¿Por qué mi grabación está en partes en vez de un solo vídeo?",
    "Pourquoi mon enregistrement est-il en morceaux plutôt qu'une seule vidéo ?",
    "Warum ist meine Aufnahme in Teile zerlegt statt ein einziges Video?")
new("help_a_pieces_1",
    "Because a single continuous file cannot be uploaded until it has finished. If the phone is taken during an encounter, a recording made that way goes with it. Pieces start leaving this phone after fifteen seconds.",
    "Perché un unico file continuo non può essere caricato finché non è terminato. Se il telefono viene preso durante un incontro, una registrazione fatta così va persa insieme a esso. Le parti iniziano a lasciare questo telefono dopo quindici secondi.",
    "Porque un único archivo continuo no se puede subir hasta que termina. Si te quitan el teléfono durante un encuentro, una grabación hecha así se pierde con él. Las partes empiezan a salir de este teléfono a los quince segundos.",
    "Parce qu'un seul fichier continu ne peut être envoyé qu'une fois terminé. Si le téléphone est pris pendant un contrôle, un enregistrement fait ainsi part avec lui. Les morceaux commencent à quitter ce téléphone au bout de quinze secondes.",
    "Weil eine einzige durchgehende Datei erst nach ihrem Abschluss hochgeladen werden kann. Wird das Telefon während einer Begegnung weggenommen, geht eine so gemachte Aufnahme mit ihm verloren. Teile verlassen dieses Telefon bereits nach fünfzehn Sekunden.")
new("help_a_pieces_2",
    "Witness puts them back together for you: one video per camera into your gallery, and, when “Combine into one video per camera” is on in Settings, one into your cloud folder as well — but only once every individual piece is already confirmed safe.",
    "Witness le ricompone per te: un video per fotocamera nella galleria e, se in Impostazioni “Unisci in un video per fotocamera” è attivo, anche uno nella cartella cloud — ma solo dopo che ogni singola parte è già confermata al sicuro.",
    "Witness las vuelve a unir por ti: un vídeo por cámara en tu galería y, si “Combinar en un vídeo por cámara” está activado en Ajustes, también uno en tu carpeta en la nube, pero solo una vez que cada parte individual ya está confirmada a salvo.",
    "Witness les rassemble pour vous : une vidéo par caméra dans votre galerie et, si « Combiner en une vidéo par caméra » est activé dans les Réglages, une autre dans votre dossier cloud — mais seulement une fois chaque morceau individuel déjà confirmé en sécurité.",
    "Witness fügt sie für dich wieder zusammen: ein Video pro Kamera in deine Galerie und, wenn „Zu einem Video pro Kamera zusammenfügen“ in den Einstellungen aktiviert ist, zusätzlich eines in deinen Cloud-Ordner – aber erst, wenn jedes einzelne Teil bereits sicher bestätigt ist.")

new("help_q_stuck_in_queue",
    "Something has been queued for a long time",
    "Qualcosa è in coda da molto tempo",
    "Algo lleva mucho tiempo en la cola",
    "Quelque chose attend dans la file depuis longtemps",
    "Etwas wartet schon lange in der Warteschlange")
new("help_a_stuck_in_queue",
    "Uploads carry on when Witness is closed, at Android's own pace. Opening Witness on Wi-Fi and leaving it on screen is the fastest way to clear a backlog. Nothing in the queue expires, and it survives restarting the phone — a piece waits until it is delivered.",
    "I caricamenti proseguono anche ad app chiusa, al ritmo deciso da Android. Aprire Witness in Wi-Fi e lasciarlo sullo schermo è il modo più veloce per smaltire un arretrato. Niente nella coda scade, e sopravvive al riavvio del telefono — una parte aspetta finché non viene consegnata.",
    "Las subidas continúan con Witness cerrado, al ritmo que marca Android. Abrir Witness con Wi-Fi y dejarlo en pantalla es la forma más rápida de despejar una acumulación. Nada en la cola caduca, y sobrevive a un reinicio del teléfono — una parte espera hasta que se entrega.",
    "Les envois continuent même Witness fermé, au rythme fixé par Android. Ouvrir Witness en Wi-Fi et le laisser à l'écran est le moyen le plus rapide de résorber un arriéré. Rien dans la file n'expire, et elle survit au redémarrage du téléphone — un morceau attend jusqu'à sa livraison.",
    "Uploads laufen auch bei geschlossenem Witness weiter, im Tempo, das Android vorgibt. Witness bei WLAN zu öffnen und auf dem Bildschirm zu lassen, räumt einen Rückstau am schnellsten ab. Nichts in der Warteschlange läuft ab, und sie übersteht einen Neustart des Telefons – ein Teil wartet, bis es zugestellt ist.")

new("help_q_who_can_delete",
    "Can anyone delete my recordings?", "Qualcuno può eliminare le mie registrazioni?",
    "¿Puede alguien eliminar mis grabaciones?", "Quelqu'un peut-il supprimer mes enregistrements ?",
    "Kann jemand meine Aufnahmen löschen?")
new("help_a_who_can_delete_1",
    "Not through this app. There is no delete button anywhere in Witness, and it only ever adds files to your storage account.",
    "Non tramite questa app. Non c'è alcun pulsante elimina da nessuna parte in Witness, e aggiunge soltanto file al tuo account di archiviazione.",
    "No a través de esta app. No hay ningún botón para eliminar en Witness, y solo añade archivos a tu cuenta de almacenamiento.",
    "Pas via cette app. Il n'existe aucun bouton supprimer dans Witness, qui ne fait qu'ajouter des fichiers à votre compte de stockage.",
    "Nicht über diese App. Es gibt in Witness nirgends eine Löschtaste, und sie fügt deinem Speicherkonto nur Dateien hinzu.")
new("help_a_who_can_delete_2",
    "The real risk is different: somebody holding your unlocked phone could sign in to your storage account through that provider's own app or website and delete the footage there. That is worth guarding against in advance.",
    "Il vero rischio è un altro: chi ha in mano il tuo telefono sbloccato potrebbe accedere al tuo account di archiviazione tramite l'app o il sito web del provider ed eliminare lì le registrazioni. Vale la pena proteggersi in anticipo da questo.",
    "El riesgo real es otro: alguien con tu teléfono desbloqueado podría iniciar sesión en tu cuenta de almacenamiento a través de la app o el sitio web de ese proveedor y eliminar allí las grabaciones. Vale la pena protegerse de esto con antelación.",
    "Le vrai risque est ailleurs : une personne tenant votre téléphone déverrouillé pourrait se connecter à votre compte de stockage via l'app ou le site de ce fournisseur et y supprimer les enregistrements. Cela vaut la peine de s'en prémunir à l'avance.",
    "Das eigentliche Risiko liegt woanders: Wer dein entsperrtes Telefon in der Hand hat, könnte sich über die eigene App oder Website dieses Anbieters in dein Speicherkonto einloggen und die Aufnahmen dort löschen. Davor lohnt es sich, sich vorab zu schützen.")

new("help_section_still_cant_find",
    "Still can't find it", "Ancora non la trovi?", "¿Sigues sin encontrarla?",
    "Toujours introuvable ?", "Immer noch nicht gefunden?")
new("help_email_us",
    "Email us", "Scrivici", "Escríbenos", "Écrivez-nous", "Schreib uns")
new("help_email_hint",
    "Say roughly when you recorded, which storage account you were using, and whether “Save recordings to gallery” was on. A photo of the upload queue screen helps most of all.",
    "Indica più o meno quando hai registrato, quale account di archiviazione stavi usando e se “Salva registrazioni in galleria” era attivo. Una foto della schermata della coda di caricamento è la cosa più utile.",
    "Indícanos aproximadamente cuándo grabaste, qué cuenta de almacenamiento usabas y si “Guardar grabaciones en la galería” estaba activado. Una foto de la pantalla de la cola de subida es lo que más ayuda.",
    "Indiquez approximativement quand vous avez filmé, quel compte de stockage vous utilisiez, et si « Enregistrer dans la galerie » était activé. Une photo de l'écran de la file d'envoi aide le plus.",
    "Nenne ungefähr, wann du aufgenommen hast, welches Speicherkonto du genutzt hast, und ob „Aufnahmen in Galerie speichern“ aktiviert war. Ein Foto des Upload-Warteschlange-Bildschirms hilft am meisten.")
new("help_footer",
    "Nothing is given up on. If a recording cannot reach your storage or your gallery, it stays on this phone until it can.",
    "Non si rinuncia a nulla. Se una registrazione non riesce a raggiungere il tuo spazio di archiviazione o la galleria, resta su questo telefono finché non ci riesce.",
    "No se abandona nada. Si una grabación no puede llegar a tu almacenamiento o a tu galería, permanece en este teléfono hasta que pueda.",
    "Rien n'est jamais abandonné. Si un enregistrement ne peut pas atteindre votre stockage ou votre galerie, il reste sur ce téléphone jusqu'à ce qu'il le puisse.",
    "Nichts wird aufgegeben. Kann eine Aufnahme deinen Speicher oder deine Galerie nicht erreichen, bleibt sie auf diesem Telefon, bis es geht.")

# ------------------------------------------------- Drive refusal / storage space
new("drive_error_full",
    "Your Google Drive is full, so nothing can be uploaded. Free up space in Drive, or connect a different storage account — everything already recorded is still on this phone and will upload once there is room.",
    "Il tuo Google Drive è pieno, quindi non si può caricare nulla. Libera spazio su Drive o collega un altro account di archiviazione — tutto ciò che è già stato registrato è ancora su questo telefono e verrà caricato appena ci sarà spazio.",
    "Tu Google Drive está lleno, así que no se puede subir nada. Libera espacio en Drive o conecta otra cuenta de almacenamiento — todo lo ya grabado sigue en este teléfono y se subirá en cuanto haya espacio.",
    "Votre Google Drive est plein, rien ne peut donc être envoyé. Libérez de l'espace dans Drive ou connectez un autre compte de stockage — tout ce qui est déjà enregistré reste sur ce téléphone et sera envoyé dès qu'il y aura de la place.",
    "Dein Google Drive ist voll, daher kann nichts hochgeladen werden. Schaffe Platz in Drive oder verbinde ein anderes Speicherkonto – alles bereits Aufgenommene befindet sich weiterhin auf diesem Telefon und wird hochgeladen, sobald wieder Platz ist.")
new("drive_error_refused",
    "Google Drive refused the upload (%1$d). Try signing out and back in under Storage.",
    "Google Drive ha rifiutato il caricamento (%1$d). Prova a uscire e rientrare in Archiviazione.",
    "Google Drive rechazó la subida (%1$d). Prueba a cerrar sesión y volver a iniciarla en Almacenamiento.",
    "Google Drive a refusé l'envoi (%1$d). Essayez de vous déconnecter puis de vous reconnecter dans Stockage.",
    "Google Drive hat den Upload abgelehnt (%1$d). Melde dich unter Speicher ab und wieder an.")
new("drive_error_folder_failed",
    "Could not create the Drive folder", "Impossibile creare la cartella su Drive",
    "No se pudo crear la carpeta de Drive", "Impossible de créer le dossier Drive",
    "Der Drive-Ordner konnte nicht erstellt werden")
new("home_storage_full",
    "Your storage is full — recordings can't be uploaded. Free up space or connect another account.",
    "Il tuo spazio è pieno — le registrazioni non possono essere caricate. Libera spazio o collega un altro account.",
    "Tu almacenamiento está lleno: las grabaciones no se pueden subir. Libera espacio o conecta otra cuenta.",
    "Votre stockage est plein — les enregistrements ne peuvent pas être envoyés. Libérez de l'espace ou connectez un autre compte.",
    "Dein Speicher ist voll – Aufnahmen können nicht hochgeladen werden. Schaffe Platz oder verbinde ein anderes Konto.")
new("home_storage_low",
    "Only about %1$d min of recording will fit in your storage. Free up space or connect another account.",
    "Nel tuo spazio c'è posto per circa %1$d min di registrazione. Libera spazio o collega un altro account.",
    "En tu almacenamiento solo caben unos %1$d min de grabación. Libera espacio o conecta otra cuenta.",
    "Il ne reste de la place que pour environ %1$d min d'enregistrement. Libérez de l'espace ou connectez un autre compte.",
    "In deinem Speicher ist nur noch Platz für etwa %1$d Min. Aufnahme. Schaffe Platz oder verbinde ein anderes Konto.")

# ------------------------------------------------- gallery held on while local-only
new("settings_gallery_held_footer",
    "Held on because nothing is uploading these recordings — with cloud upload off, or in the Mastering codec, the gallery is the only place they exist. Your own setting comes back when uploading resumes.",
    "Mantenuto attivo perché nulla sta caricando queste registrazioni — con il caricamento cloud disattivato, o con il codec Mastering, la galleria è l'unico posto in cui esistono. La tua impostazione tornerà quando riprenderà il caricamento.",
    "Se mantiene activado porque nada está subiendo estas grabaciones: con la subida a la nube desactivada, o con el códec Mastering, la galería es el único lugar donde existen. Tu ajuste vuelve cuando se reanuda la subida.",
    "Maintenu activé car rien n'envoie ces enregistrements — avec l'envoi cloud désactivé, ou avec le codec Mastering, la galerie est le seul endroit où ils existent. Votre réglage revient dès que l'envoi reprend.",
    "Bleibt eingeschaltet, weil nichts diese Aufnahmen hochlädt – bei ausgeschaltetem Cloud-Upload oder mit dem Mastering-Codec ist die Galerie der einzige Ort, an dem sie existieren. Deine eigene Einstellung kehrt zurück, sobald das Hochladen wieder läuft.")
new("settings_cloud_upload_footer",
    "On, every recording is sent to your storage as you film. Off, new recordings stay on this phone only — still recorded, still hashed and timestamped, and saved to your gallery, which becomes the only place they exist.",
    "Attivo, ogni registrazione viene inviata al tuo spazio mentre filmi. Disattivo, le nuove registrazioni restano solo su questo telefono — comunque registrate, con hash e marca temporale, e salvate nella galleria, che diventa l'unico posto in cui esistono.",
    "Activado, cada grabación se envía a tu almacenamiento mientras filmas. Desactivado, las grabaciones nuevas se quedan solo en este teléfono: se graban igualmente, con hash y sello de tiempo, y se guardan en tu galería, que pasa a ser el único lugar donde existen.",
    "Activé, chaque enregistrement est envoyé à votre stockage pendant que vous filmez. Désactivé, les nouveaux enregistrements restent sur ce téléphone uniquement — toujours enregistrés, avec hash et horodatage, et sauvegardés dans votre galerie, qui devient le seul endroit où ils existent.",
    "Eingeschaltet wird jede Aufnahme beim Filmen an deinen Speicher gesendet. Ausgeschaltet bleiben neue Aufnahmen nur auf diesem Telefon – weiterhin aufgenommen, mit Hash und Zeitstempel, und in deiner Galerie gespeichert, die dann der einzige Ort ist, an dem sie existieren.")
