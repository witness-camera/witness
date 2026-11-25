# -*- coding: utf-8 -*-
"""Crew (iOS 1.1.11/1.1.12): several phones recording one event into a shared
case, lined up on one clock and exported as a multicam edit.

Lifted from iOS's String Catalog where the text is the same. Android's crew has
no live link between phones (iOS syncs through iCloud and Multipeer), so the
copy about *how* a crew stays up to date is Android's own.
"""
import strings_part11  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------------ entry points
src("crew_title", "Crew")
src("crew_default_title", "Crew")
src("crew_member_default", "Crew member")
src("crew_button_description", "Crew: record with others")
src("crew_recording_with", "Recording with crew: %@")

# ------------------------------------------------------------------ start
src("crew_hero_title", "Record together")
src("crew_hero_body", "Several phones film the same protest, raid or event into one shared case.")
src("crew_reason_angles_title", "Every angle")
src("crew_reason_angles_body",
    "What one phone misses, another catches: the start of an arrest, a face, a badge number.")
src("crew_reason_nobody_title", "Nobody holds it all")
src("crew_reason_nobody_body",
    "Each recording's folder is shared with the whole crew as it uploads. If one phone is taken, the others already have its footage.")
src("crew_reason_timeline_title", "One timeline")
src("crew_reason_timeline_body",
    "Clocks are matched, so every angle lines up to the second. Export it as one multicam edit for Final Cut, Premiere or Resolve.")
src("crew_reason_server_title", "No Witness server")
new("crew_reason_server_body",
    "Each phone keeps uploading to its own cloud. The crew shares only names, emails and timings, in a code or link you pass between phones.",
    "Ogni telefono continua a caricare sul proprio cloud. La crew condivide solo nomi, email e orari, in un codice o link che vi passate tra telefoni.",
    "Cada teléfono sigue subiendo a su propia nube. El equipo solo comparte nombres, correos y horarios, en un código o enlace que os pasáis entre teléfonos.",
    "Chaque téléphone continue d'envoyer vers son propre cloud. L'équipe ne partage que des noms, des e-mails et des horaires, dans un code ou un lien que vous vous passez d'un téléphone à l'autre.",
    "Jedes Handy lädt weiter in seine eigene Cloud hoch. Die Crew teilt nur Namen, E-Mail-Adressen und Zeiten, in einem Code oder Link, den ihr von Handy zu Handy weitergebt.")
src("crew_how_header", "How it works")
new("crew_step_1",
    "Start a crew and add your name and email below.",
    "Avvia una crew e aggiungi qui sotto il tuo nome e la tua email.",
    "Inicia un equipo y añade abajo tu nombre y tu correo.",
    "Démarrez une équipe et ajoutez ci-dessous votre nom et votre e-mail.",
    "Starte eine Crew und trage unten deinen Namen und deine E-Mail ein.")
new("crew_step_2",
    "The others scan your code in Witness, or open the link you send.",
    "Gli altri scansionano il tuo codice in Witness, oppure aprono il link che invii.",
    "Los demás escanean tu código en Witness, o abren el enlace que envíes.",
    "Les autres scannent votre code dans Witness, ou ouvrent le lien que vous envoyez.",
    "Die anderen scannen deinen Code in Witness oder öffnen den Link, den du sendest.")
new("crew_step_3",
    "Each person who joins shows their own code back to you, or sends their link, so you can see who is in.",
    "Ogni persona che entra ti mostra a sua volta il proprio codice, oppure ti manda il proprio link, così vedi chi c'è.",
    "Cada persona que se une te muestra su propio código, o te manda su enlace, para que veas quién está.",
    "Chaque personne qui rejoint vous montre à son tour son code, ou vous envoie son lien, pour que vous voyiez qui est là.",
    "Jede Person, die beitritt, zeigt dir ihren eigenen Code oder schickt dir ihren Link, damit du siehst, wer dabei ist.")
new("crew_step_4",
    "Record as usual. Afterwards, swap codes once more so everyone has every recording, then make the multicam edit files.",
    "Registra come al solito. Poi scambiatevi i codici ancora una volta, così tutti hanno ogni registrazione, e crea i file di montaggio multicam.",
    "Graba como siempre. Después, intercambiad los códigos una vez más para que todos tengan cada grabación, y crea los archivos de edición multicámara.",
    "Enregistrez comme d'habitude. Ensuite, échangez de nouveau vos codes pour que chacun ait tous les enregistrements, puis créez les fichiers de montage multicaméra.",
    "Nimm wie gewohnt auf. Tauscht danach noch einmal die Codes, damit alle jede Aufnahme haben, und erstelle dann die Multicam-Schnittdateien.")
src("crew_you_header", "You")
src("crew_your_name", "Your name")
src("crew_your_email", "Your email, to receive the crew's footage")
src("crew_details_footer_first",
    "Add your name and email so the crew can see you've joined and send you their footage. Shared only with your crew.")
src("crew_details_footer", "Shared only with your crew, inside the crew's code and link.")
src("crew_event_hint", "Event name, e.g. March 12 protest")
src("crew_start", "Start a crew")
new("crew_start_footer",
    "To join someone else's crew, scan their code or open the link they sent.",
    "Per entrare nella crew di qualcun altro, scansiona il suo codice o apri il link che ti ha inviato.",
    "Para unirte al equipo de otra persona, escanea su código o abre el enlace que te envió.",
    "Pour rejoindre l'équipe de quelqu'un, scannez son code ou ouvrez le lien qu'il vous a envoyé.",
    "Um der Crew von jemand anderem beizutreten, scanne den Code oder öffne den Link, den du bekommen hast.")

# ------------------------------------------------------------------ joining
new("crew_join_header", "Join a crew", "Entra in una crew", "Unirse a un equipo", "Rejoindre une équipe", "Einer Crew beitreten")
new("crew_scan", "Scan a crew code", "Scansiona un codice crew", "Escanear un código de equipo",
    "Scanner un code d'équipe", "Crew-Code scannen")
new("crew_join_hint", "Paste an invite link", "Incolla un link di invito", "Pega un enlace de invitación",
    "Collez un lien d'invitation", "Einladungslink einfügen")
new("crew_paste", "Paste", "Incolla", "Pegar", "Coller", "Einfügen")
new("crew_join", "Join", "Entra", "Unirse", "Rejoindre", "Beitreten")
new("crew_scan_failed",
    "The scanner isn't available on this phone. Scan the code with your camera app, or paste the link instead.",
    "Lo scanner non è disponibile su questo telefono. Scansiona il codice con la fotocamera, oppure incolla il link.",
    "El escáner no está disponible en este teléfono. Escanea el código con la cámara, o pega el enlace.",
    "Le scanner n'est pas disponible sur ce téléphone. Scannez le code avec l'appareil photo, ou collez le lien.",
    "Der Scanner ist auf diesem Handy nicht verfügbar. Scanne den Code mit der Kamera-App oder füge stattdessen den Link ein.")
new("crew_not_a_crew",
    "That isn't a Witness crew code or link.",
    "Questo non è un codice o un link crew di Witness.",
    "Eso no es un código ni un enlace de equipo de Witness.",
    "Ce n'est pas un code ni un lien d'équipe Witness.",
    "Das ist kein Witness-Crew-Code oder -Link.")
new("crew_joined", "Joined %s", "Sei entrato in %s", "Te has unido a %s", "Vous avez rejoint %s", "%s beigetreten")
new("crew_updated", "Crew updated", "Crew aggiornata", "Equipo actualizado", "Équipe mise à jour", "Crew aktualisiert")

# ------------------------------------------------------------------ a crew
src("crew_new_recordings_join", "New recordings join this crew.")
src("crew_new_recordings_dont", "New recordings don't join this crew.")
src("crew_stop_recording_into", "Stop recording into this crew")
src("crew_record_into", "Record into this crew")
new("crew_connect_storage_warning",
    "Connect cloud storage on the home screen so your recordings reach the crew. Until then they stay on this phone.",
    "Collega un cloud dalla schermata iniziale perché le tue registrazioni raggiungano la crew. Fino ad allora restano su questo telefono.",
    "Conecta un almacenamiento en la nube en la pantalla de inicio para que tus grabaciones lleguen al equipo. Hasta entonces se quedan en este teléfono.",
    "Connectez un stockage cloud sur l'écran d'accueil pour que vos enregistrements parviennent à l'équipe. D'ici là, ils restent sur ce téléphone.",
    "Verbinde auf dem Startbildschirm einen Cloud-Speicher, damit deine Aufnahmen die Crew erreichen. Bis dahin bleiben sie auf diesem Handy.")
src("crew_invite_header", "Invite")
src("crew_code_description", "Crew code")
src("crew_send_link", "Send invite link")
src("crew_share_message", "Join my Witness crew for %@")
new("crew_invite_footer",
    "This code is your phone's copy of the crew. Show it to someone to invite them, or to hand over what you've learned. Anyone with the code or link can join, so share it only with your crew.",
    "Questo codice è la copia della crew sul tuo telefono. Mostralo a qualcuno per invitarlo, o per passargli ciò che hai appreso. Chiunque abbia il codice o il link può entrare: condividilo solo con la tua crew.",
    "Este código es la copia del equipo en tu teléfono. Muéstraselo a alguien para invitarle, o para pasarle lo que has aprendido. Cualquiera con el código o el enlace puede unirse: compártelo solo con tu equipo.",
    "Ce code est la copie de l'équipe sur votre téléphone. Montrez-le à quelqu'un pour l'inviter, ou pour lui transmettre ce que vous savez. Toute personne ayant le code ou le lien peut rejoindre : ne le partagez qu'avec votre équipe.",
    "Dieser Code ist die Kopie der Crew auf deinem Handy. Zeige ihn jemandem, um ihn einzuladen oder um weiterzugeben, was du erfahren hast. Wer den Code oder Link hat, kann beitreten – teile ihn nur mit deiner Crew.")
new("crew_code_too_big",
    "This crew has grown too big for one code. Send the invite link instead.",
    "Questa crew è diventata troppo grande per un solo codice. Invia il link di invito.",
    "Este equipo ha crecido demasiado para un solo código. Envía el enlace de invitación.",
    "Cette équipe est devenue trop grande pour un seul code. Envoyez plutôt le lien d'invitation.",
    "Diese Crew ist für einen einzelnen Code zu groß geworden. Sende stattdessen den Einladungslink.")
new("crew_sync_header", "Keeping everyone up to date", "Tenere tutti aggiornati", "Mantener a todos al día",
    "Tenir tout le monde à jour", "Alle auf dem Laufenden halten")
new("crew_sync_footer",
    "Phones aren't connected live. When someone joins or a recording ends, show your code to a crew member or send them your link, and scan or open theirs. Each phone adds what the other knows; nothing is ever removed.",
    "I telefoni non sono collegati in tempo reale. Quando qualcuno entra o una registrazione termina, mostra il tuo codice a un membro o mandagli il tuo link, e scansiona o apri il suo. Ogni telefono aggiunge ciò che sa l'altro; nulla viene mai rimosso.",
    "Los teléfonos no están conectados en directo. Cuando alguien se une o termina una grabación, muestra tu código a un miembro o envíale tu enlace, y escanea o abre el suyo. Cada teléfono añade lo que sabe el otro; nunca se elimina nada.",
    "Les téléphones ne sont pas connectés en direct. Quand quelqu'un rejoint ou qu'un enregistrement se termine, montrez votre code à un membre ou envoyez-lui votre lien, et scannez ou ouvrez le sien. Chaque téléphone ajoute ce que sait l'autre ; rien n'est jamais supprimé.",
    "Die Handys sind nicht live verbunden. Wenn jemand beitritt oder eine Aufnahme endet, zeige einem Mitglied deinen Code oder sende ihm deinen Link und scanne oder öffne seinen. Jedes Handy übernimmt, was das andere weiß; nichts wird je entfernt.")
new("crew_scan_member", "Scan a member's code", "Scansiona il codice di un membro", "Escanear el código de un miembro",
    "Scanner le code d'un membre", "Code eines Mitglieds scannen")
src("crew_clock_matched", "Clock matched to within %lld ms.")
new("crew_clock_unmatched",
    "Clock not matched yet: open this screen with an internet connection. Until then the phone's own time is used, usually within a fraction of a second.",
    "Orologio non ancora allineato: apri questa schermata con una connessione internet. Nel frattempo si usa l'ora del telefono, di solito precisa entro una frazione di secondo.",
    "Reloj aún sin ajustar: abre esta pantalla con conexión a internet. Mientras tanto se usa la hora del teléfono, normalmente con una diferencia de una fracción de segundo.",
    "Horloge pas encore calée : ouvrez cet écran avec une connexion internet. D'ici là, l'heure du téléphone est utilisée, généralement à une fraction de seconde près.",
    "Uhr noch nicht abgeglichen: Öffne diesen Bildschirm mit Internetverbindung. Bis dahin wird die Uhrzeit des Handys genutzt, meist auf Sekundenbruchteile genau.")
new("crew_clock_matching", "Matching the clock to network time…", "Allineamento dell'orologio all'ora di rete…",
    "Ajustando el reloj a la hora de red…", "Calage de l'horloge sur l'heure du réseau…",
    "Uhr wird mit der Netzwerkzeit abgeglichen …")
new("crew_clock_match_now", "Match clock now", "Allinea l'orologio ora", "Ajustar el reloj ahora",
    "Caler l'horloge maintenant", "Uhr jetzt abgleichen")
src("crew_members_header", "Members")
src("crew_organizer", "Organizer")
src("crew_you", "You")
src("crew_filming_now", "Filming now")
src("crew_no_email", "No email: won't receive footage")
src("crew_recordings_header", "Recordings")
new("crew_recordings_footer",
    "A recording appears here once its phone's code or link reaches you after it stops. The folder opens in Google Drive or Dropbox for everyone who gave their email.",
    "Una registrazione compare qui quando il codice o il link del suo telefono ti arriva dopo che si è fermata. La cartella si apre in Google Drive o Dropbox per chiunque abbia dato la propria email.",
    "Una grabación aparece aquí cuando el código o el enlace de su teléfono te llega después de que se detenga. La carpeta se abre en Google Drive o Dropbox para quien haya dado su correo.",
    "Un enregistrement apparaît ici quand le code ou le lien de son téléphone vous parvient après son arrêt. Le dossier s'ouvre dans Google Drive ou Dropbox pour toute personne ayant donné son e-mail.",
    "Eine Aufnahme erscheint hier, sobald dich der Code oder Link ihres Handys nach dem Stopp erreicht. Der Ordner öffnet sich in Google Drive oder Dropbox für alle, die ihre E-Mail angegeben haben.")
src("crew_no_recordings", "No recordings yet.")
src("crew_make_export", "Make multicam edit files")
src("crew_share_export", "Share for Final Cut, Premiere or Resolve")
new("crew_export_failed", "Couldn't make the files", "Impossibile creare i file", "No se pudieron crear los archivos",
    "Impossible de créer les fichiers", "Die Dateien konnten nicht erstellt werden")
new("crew_angle_time", "%1$s, %2$d min", "%1$s, %2$d min", "%1$s, %2$d min", "%1$s, %2$d min", "%1$s, %2$d Min.")
src("crew_open_drive", "Open in Google Drive")
src("crew_open_dropbox", "Open in Dropbox")
src("crew_open_dropbox_shared", "Open Dropbox shared folders")
src("crew_own_server_note", "On their own server, which can't be shared automatically.")
src("crew_waiting_footage", "Waiting for the footage to start uploading.")
src("crew_earlier_header", "Earlier crews")
new("crew_delete", "Delete crew from this phone", "Elimina la crew da questo telefono",
    "Eliminar el equipo de este teléfono", "Supprimer l'équipe de ce téléphone", "Crew von diesem Handy löschen")
src("crew_delete_footer", "Recordings stay in your case and cloud. Folders already shared stay shared.")

# ------------------------------------------------------------------ multicam export
src("crew_camera_front", "front camera")
src("crew_camera_wide", "wide")
src("crew_camera_ultrawide", "ultra wide")
src("crew_camera_pip", "picture in picture")
src("crew_camera_rear", "rear camera")
src("crew_export_readme_intro",
    "Every angle below is placed on the crew's shared clock. To open the timelines, put the footage in one folder laid out like this, next to these files:")
src("crew_export_readme_relink",
    "Each member's footage is in the folders they shared with the crew. If your files are laid out differently, relink by file name when your editor asks.")

# ------------------------------------------------------------------ help
new("help_crew_q", "How does a crew work?", "Come funziona una crew?", "¿Cómo funciona un equipo?",
    "Comment fonctionne une équipe ?", "Wie funktioniert eine Crew?")
new("help_crew_a1",
    "Tap the people icon at the top of the main screen and start a crew. Others join by scanning your code in Witness, or opening the link you send. iPhone and Android phones can be in the same crew.",
    "Tocca l'icona delle persone in alto nella schermata principale e avvia una crew. Gli altri entrano scansionando il tuo codice in Witness, o aprendo il link che invii. Telefoni iPhone e Android possono stare nella stessa crew.",
    "Toca el icono de personas en la parte superior de la pantalla principal e inicia un equipo. Los demás se unen escaneando tu código en Witness, o abriendo el enlace que envíes. Pueden estar en el mismo equipo iPhone y Android.",
    "Touchez l'icône de personnes en haut de l'écran principal et démarrez une équipe. Les autres rejoignent en scannant votre code dans Witness, ou en ouvrant le lien que vous envoyez. Des iPhone et des Android peuvent être dans la même équipe.",
    "Tippe oben auf dem Hauptbildschirm auf das Personensymbol und starte eine Crew. Die anderen treten bei, indem sie deinen Code in Witness scannen oder den Link öffnen, den du sendest. iPhone und Android können in derselben Crew sein.")
new("help_crew_a2",
    "Every phone keeps uploading to its own cloud, and each recording's folder is shared with the crew's emails. Android phones match their clock to network time, so every angle lines up. Phones aren't connected live: swap codes before and after recording so everyone knows who and what is in the crew.",
    "Ogni telefono continua a caricare sul proprio cloud, e la cartella di ogni registrazione è condivisa con le email della crew. I telefoni Android allineano l'orologio all'ora di rete, così ogni inquadratura combacia. I telefoni non sono collegati in tempo reale: scambiatevi i codici prima e dopo la registrazione perché tutti sappiano chi e cosa c'è nella crew.",
    "Cada teléfono sigue subiendo a su propia nube, y la carpeta de cada grabación se comparte con los correos del equipo. Los teléfonos Android ajustan su reloj a la hora de red, así que cada ángulo encaja. Los teléfonos no están conectados en directo: intercambiad códigos antes y después de grabar para que todos sepan quién y qué hay en el equipo.",
    "Chaque téléphone continue d'envoyer vers son propre cloud, et le dossier de chaque enregistrement est partagé avec les e-mails de l'équipe. Les téléphones Android calent leur horloge sur l'heure du réseau, si bien que chaque angle s'aligne. Les téléphones ne sont pas connectés en direct : échangez vos codes avant et après l'enregistrement pour que chacun sache qui et quoi est dans l'équipe.",
    "Jedes Handy lädt weiter in seine eigene Cloud hoch, und der Ordner jeder Aufnahme wird mit den E-Mail-Adressen der Crew geteilt. Android-Handys gleichen ihre Uhr mit der Netzwerkzeit ab, sodass jeder Blickwinkel passt. Die Handys sind nicht live verbunden: Tauscht vor und nach der Aufnahme die Codes, damit alle wissen, wer und was in der Crew ist.")
src("help_crew_a3",
    "Afterwards, open the crew and tap “Make multicam edit files” for Final Cut, Premiere or DaVinci Resolve.")
