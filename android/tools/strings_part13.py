# -*- coding: utf-8 -*-
"""Crew editor (iOS 1.1.13): watch a crew's angles on one timeline, cut between
cameras, set In and Out, and export the edit as one video.

Lifted from iOS's String Catalog where the text is the same. iOS plays small
previews shared through iCloud; Android plays the footage on the phone (the
person's own recordings, plus any angle they have downloaded and linked), so
the copy about where the footage comes from is Android's own.
"""
import strings_part12  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

new("crew_watch", "Watch all angles in sync", "Guarda tutte le angolazioni in sincrono",
    "Mira todos los ángulos sincronizados", "Regarder tous les angles en synchronisation",
    "Alle Blickwinkel synchron ansehen")
new("crew_player_no_footage", "No footage on this phone yet", "Ancora nessun filmato su questo telefono",
    "Aún no hay grabaciones en este teléfono", "Pas encore d'images sur ce téléphone",
    "Noch kein Material auf diesem Handy")
src("crew_no_camera_now", "No camera was filming at this moment")
src("crew_cameras_header", "Cameras")
src("crew_in", "In")
src("crew_out", "Out")
src("crew_remove_cut", "Remove cut")
src("crew_go_in", "Go to In")
src("crew_go_out", "Go to Out")
src("crew_clear_inout", "Clear In and Out")
src("crew_clear_cuts", "Clear all cuts")
src("crew_zoom_whole", "Show the whole timeline")
src("crew_zoom_inout", "Zoom to In and Out")
src("crew_more_options", "More editing options")
src("crew_back_1s", "Back one second")
src("crew_fwd_1s", "Forward one second")
new("crew_play", "Play", "Riproduci", "Reproducir", "Lire", "Abspielen")
new("crew_pause", "Pause", "Pausa", "Pausa", "Pause", "Pause")
src("crew_cameras_now", "%lld cameras at this moment")
src("crew_status_filming", "Filming at this moment")
src("crew_status_not_filming", "Not filming at this moment")
new("crew_status_no_footage", "Footage not on this phone yet", "Filmato non ancora su questo telefono",
    "Grabación aún no en este teléfono", "Images pas encore sur ce téléphone", "Material noch nicht auf diesem Handy")
new("crew_add_footage", "Add footage", "Aggiungi filmato", "Añadir grabación", "Ajouter les images", "Material hinzufügen")
new("crew_remove_footage", "Remove link", "Rimuovi collegamento", "Quitar enlace", "Retirer le lien", "Verknüpfung entfernen")
src("crew_phone", "Crew phone")
src("crew_export_header", "Export")
src("crew_range_whole", "Whole timeline")
src("crew_range_inout", "In to Out")
src("crew_export_video", "Export video")
src("crew_exporting", "Exporting…")
src("crew_share", "Share")
new("crew_save_gallery", "Save to gallery", "Salva nella galleria", "Guardar en la galería",
    "Enregistrer dans la galerie", "In der Galerie sichern")
new("crew_saved_gallery", "Saved to gallery", "Salvato nella galleria", "Guardado en la galería",
    "Enregistré dans la galerie", "In der Galerie gesichert")
new("crew_save_gallery_failed", "The gallery didn't accept the video.", "La galleria non ha accettato il video.",
    "La galería no aceptó el vídeo.", "La galerie n'a pas accepté la vidéo.", "Die Galerie hat das Video nicht angenommen.")
src("crew_export_no_footage", "There's no footage in this part of the timeline yet.")
src("crew_export_start_failed", "Couldn't start the export.")
new("crew_export_note",
    "Made from the footage on this phone. For full control, use Make multicam edit files on the crew screen and conform the full footage in your editor.",
    "Creato dai filmati presenti su questo telefono. Per il pieno controllo, usa Crea file di montaggio multicam nella schermata della crew e conforma i filmati completi nel tuo editor.",
    "Creado con las grabaciones de este teléfono. Para tener control total, usa Crear archivos de edición multicámara en la pantalla del equipo y conforma las grabaciones completas en tu editor.",
    "Réalisé à partir des images présentes sur ce téléphone. Pour un contrôle total, utilisez Créer les fichiers de montage multicaméra sur l'écran de l'équipe et conformez les images complètes dans votre éditeur.",
    "Aus dem Material auf diesem Handy erstellt. Für volle Kontrolle nutze Multicam-Schnittdateien erstellen im Crew-Bildschirm und konformiere das komplette Material in deinem Schnittprogramm.")
new("crew_player_help",
    "Tap a camera to cut to it at the playhead. Cuts are saved on this phone and playback follows them; if the chosen camera wasn't filming, another one that was takes over. Your own recordings are found automatically. For another member's camera, download its video from the folder they shared (Open in Google Drive or Dropbox), then tap Add footage on that camera.",
    "Tocca una camera per passare a essa nel punto di lettura. I tagli sono salvati su questo telefono e la riproduzione li segue; se la camera scelta non stava riprendendo, subentra un'altra che lo faceva. Le tue registrazioni vengono trovate in automatico. Per la camera di un altro membro, scarica il suo video dalla cartella condivisa (Apri in Google Drive o Dropbox), poi tocca Aggiungi filmato su quella camera.",
    "Toca una cámara para cortar a ella en el cabezal. Los cortes se guardan en este teléfono y la reproducción los sigue; si la cámara elegida no grababa, la sustituye otra que sí. Tus propias grabaciones se encuentran solas. Para la cámara de otro miembro, descarga su vídeo de la carpeta que compartió (Abrir en Google Drive o Dropbox) y toca Añadir grabación en esa cámara.",
    "Touchez une caméra pour y passer à la position de lecture. Les coupes sont enregistrées sur ce téléphone et la lecture les suit ; si la caméra choisie ne filmait pas, une autre qui filmait prend le relais. Vos propres enregistrements sont trouvés automatiquement. Pour la caméra d'un autre membre, téléchargez sa vidéo depuis le dossier partagé (Ouvrir dans Google Drive ou Dropbox), puis touchez Ajouter les images sur cette caméra.",
    "Tippe auf eine Kamera, um an der Abspielposition zu ihr zu schneiden. Schnitte werden auf diesem Handy gespeichert und die Wiedergabe folgt ihnen; hat die gewählte Kamera nicht gefilmt, übernimmt eine andere, die gefilmt hat. Deine eigenen Aufnahmen werden automatisch gefunden. Für die Kamera eines anderen Mitglieds lade das Video aus dem geteilten Ordner herunter (In Google Drive oder Dropbox öffnen) und tippe bei dieser Kamera auf Material hinzufügen.")
