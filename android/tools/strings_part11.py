# -*- coding: utf-8 -*-
"""Cases (iOS 1.1.9): folders of recordings, verified photos, audio-only
recordings and imports, with guided checklists, notes, a per-item proof and a
case report; plus recordings kept only in the gallery and uploaded later.

Lifted from iOS's String Catalog where the text is the same; Android's own
wording where iOS mentions Photos or the iPhone.
"""
import strings_part10  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ------------------------------------------------------------------ Cases list
src("cases_title", "Cases")
src("cases_close", "Close cases")
new("cases_search_hint",
    "Search names, notes and details",
    "Cerca nei nomi, nelle note e nei dettagli",
    "Buscar en nombres, notas y detalles",
    "Rechercher dans les noms, notes et détails",
    "Namen, Notizen und Details durchsuchen")
src("cases_new", "New case")
src("cases_new_name_hint", "Name, e.g. Flat 4B deposit")
src("cases_create", "Create")
src("cases_untitled", "Untitled case")
src("cases_create_first", "Create your first case")
src("cases_footer", "A case groups recordings, photos and files about one matter. Deleting a case keeps its items.")
src("cases_not_in_a_case", "Not in a case")
new("cases_not_in_a_case_footer",
    "Tap Add to case on a recording to file it, or open a case and tap Add recordings to pick several.",
    "Tocca Aggiungi a un caso su una registrazione per archiviarla, oppure apri un caso e tocca Aggiungi registrazioni per sceglierne diverse.",
    "Toca Añadir a un caso en una grabación para archivarla, o abre un caso y toca Añadir grabaciones para elegir varias.",
    "Touchez Ajouter à un dossier sur un enregistrement pour le classer, ou ouvrez un dossier et touchez Ajouter des enregistrements pour en choisir plusieurs.",
    "Tippen Sie bei einer Aufnahme auf Zu Fall hinzufügen, um sie abzulegen, oder öffnen Sie einen Fall und tippen Sie auf Aufnahmen hinzufügen, um mehrere zu wählen.")
src("cases_add_to_case", "Add to case")
new("cases_item_count", "%d item(s)", "%d elemento/i", "%d elemento(s)", "%d élément(s)", "%d Element(e)")
new("cases_matches", "%d match(es)", "%d risultato/i", "%d resultado(s)", "%d résultat(s)", "%d Treffer")
new("cases_no_matches", "Nothing found", "Nessun risultato", "No se encontró nada", "Aucun résultat", "Nichts gefunden")
new("cases_delete_case", "Delete case", "Elimina caso", "Eliminar caso", "Supprimer le dossier", "Fall löschen")
new("cases_delete_case_message",
    "Deleting a case keeps its items: they move back to Not in a case.",
    "Eliminare un caso ne conserva gli elementi: tornano in Non in un caso.",
    "Eliminar un caso conserva sus elementos: vuelven a Sin caso.",
    "Supprimer un dossier conserve ses éléments : ils retournent dans Hors dossier.",
    "Das Löschen eines Falls behält seine Elemente: Sie wandern zurück zu Nicht in einem Fall.")

# ----------------------------------------------------------------- Case detail
src("case_nothing_yet", "Nothing here yet")
src("case_nothing_yet_description", "Add past recordings, or a new photo, audio recording or file.")
src("case_add_recordings", "Add recordings…")
src("case_export_report", "Export case report")
new("case_export_footer",
    "One PDF covering every item, with each item's file, record and time-stamp proof.",
    "Un PDF che copre ogni elemento, con il file, il record e la prova di marca temporale di ciascuno.",
    "Un PDF que cubre cada elemento, con el archivo, el registro y la prueba de sello de tiempo de cada uno.",
    "Un PDF couvrant chaque élément, avec le fichier, le relevé et la preuve d'horodatage de chacun.",
    "Eine PDF-Datei für jedes Element, mit Datei, Protokoll und Zeitstempelnachweis jedes Elements.")
src("case_rename", "Rename case")
src("case_rename_name", "Name")
src("action_save", "Save")
src("case_report_failed", "Couldn't build the report")
src("case_add_recordings_title", "Add recordings")
src("case_nothing_else", "Nothing else to add")
src("case_every_recording_in", "Every recording is already in this case.")
src("case_in_another", "In another case")
src("case_adding_moves", "Adding one of these moves it here.")
src("case_add", "Add")
src("case_add_count", "Add (%lld)")
src("case_move_to", "Move to case")

# ------------------------------------------------------------------ Item detail
src("item_recording", "Recording")
src("item_photo", "Photo")
src("item_audio", "Audio recording")
src("item_imported_file", "Imported file")
src("item_time_stamped", "Time-stamped")
src("item_imported", "Imported")
src("item_play_recording", "Play recording")
src("item_play_audio", "Play audio")
src("item_pause", "Pause")
src("item_play_video", "Play video")
src("item_name_hint", "Name (optional)")
src("item_case", "Case")
src("item_new_case", "New case…")
src("item_recording_saved", "Recording saved")
src("item_remove", "Remove from Witness")
src("item_remove_confirm", "Remove this item?")
src("item_remove_action", "Remove")
src("item_removed", "Removed")
new("item_remove_footer_video",
    "Removes the app's thumbnail, audio copy, details and notes. The recording itself stays in your gallery and your cloud account.",
    "Rimuove la miniatura, la copia audio, i dettagli e le note dell'app. La registrazione resta nella galleria e nel tuo account cloud.",
    "Elimina la miniatura, la copia de audio, los detalles y las notas de la app. La grabación sigue en tu galería y en tu cuenta en la nube.",
    "Supprime la miniature, la copie audio, les détails et les notes de l'app. L'enregistrement reste dans votre galerie et votre compte cloud.",
    "Entfernt Vorschaubild, Audiokopie, Details und Notizen der App. Die Aufnahme selbst bleibt in Ihrer Galerie und Ihrem Cloud-Konto.")
new("item_remove_footer_other",
    "Removes the app's copy. Anything already in your gallery or your cloud account stays there.",
    "Rimuove la copia dell'app. Ciò che è già nella galleria o nel tuo account cloud resta lì.",
    "Elimina la copia de la app. Lo que ya esté en tu galería o en tu cuenta en la nube sigue ahí.",
    "Supprime la copie de l'app. Ce qui est déjà dans votre galerie ou votre compte cloud y reste.",
    "Entfernt die Kopie der App. Was bereits in Ihrer Galerie oder Ihrem Cloud-Konto liegt, bleibt dort.")
new("item_video_missing",
    "This recording isn't in your gallery on this phone. It may have been deleted there, or saved only to your cloud account.",
    "Questa registrazione non è nella galleria di questo telefono. Potrebbe essere stata eliminata o salvata solo nel tuo account cloud.",
    "Esta grabación no está en la galería de este teléfono. Puede haberse eliminado allí o guardado solo en tu cuenta en la nube.",
    "Cet enregistrement n'est pas dans la galerie de ce téléphone. Il a pu être supprimé ou enregistré uniquement dans votre compte cloud.",
    "Diese Aufnahme ist nicht in der Galerie dieses Telefons. Sie wurde dort vielleicht gelöscht oder nur in Ihrem Cloud-Konto gespeichert.")
src("item_details", "Details")
src("item_details_footer",
    "Pick what this was to get a checklist. Everything is optional and is kept apart from the recording's proof.")
src("item_notes", "Notes")
src("item_add_note", "Add a note")
src("item_proof", "Proof")
src("item_proof_manifest", "Session manifest")
src("item_proof_sha", "SHA-256")
src("item_proof_stamped", "Independently time-stamped")
src("item_proof_pending", "Time-stamp pending")
src("item_proof_uploaded", "Copied to your cloud account")
src("item_proof_waiting", "Waiting to upload")
src("item_proof_imported_footer",
    "Imported files are hashed and time-stamped when they're imported. That proves they haven't changed since then, not when they were made.")
new("item_proof_video_footer",
    "The recording is in your gallery and your cloud account. Its manifest lists every segment's hash and is time-stamped once uploaded.",
    "La registrazione è nella galleria e nel tuo account cloud. Il suo manifest elenca l'hash di ogni segmento ed è marcato temporalmente una volta caricato.",
    "La grabación está en tu galería y en tu cuenta en la nube. Su manifiesto lista el hash de cada segmento y se sella en el tiempo una vez subido.",
    "L'enregistrement est dans votre galerie et votre compte cloud. Son manifeste liste le hash de chaque segment et est horodaté une fois envoyé.",
    "Die Aufnahme liegt in Ihrer Galerie und Ihrem Cloud-Konto. Ihr Manifest listet den Hash jedes Segments und wird nach dem Hochladen mit Zeitstempel versehen.")

# ------------------------------------------------------------------ Checklists
src("template_encounter", "Police or security encounter")
src("template_accident", "Accident")
src("template_property", "Property or rental")
src("template_workplace", "Workplace")
src("template_other", "Something else")
src("prompt_officer_names", "Officer names or badge numbers")
src("prompt_agency", "Agency or department")
src("prompt_vehicles", "Vehicle or unit numbers")
src("prompt_reason", "Reason they gave")
src("prompt_witnesses", "Witnesses (names and contacts)")
src("prompt_report_number", "Report or incident number")
src("prompt_other_driver", "Other driver's name and contacts")
src("prompt_plate", "Other vehicle's plate")
src("prompt_insurance", "Insurance company and policy number")
src("prompt_damage", "Damage description")
src("prompt_police_report_number", "Police report number")
src("prompt_address", "Address and unit")
src("prompt_areas", "Rooms or areas covered")
src("prompt_existing_damage", "Existing damage noted")
src("prompt_meters", "Meter readings")
src("prompt_present", "Landlord, agent or contractor present")
src("prompt_people", "People involved")
src("prompt_location", "Location")
src("prompt_what_happened", "What happened")
src("prompt_reported_to", "Reported to (HR, manager)")
src("prompt_what_shows", "What this shows")
src("prompt_reference", "Reference number")

# ---------------------------------------------------------------- Adding evidence
src("add_verified_photo", "Take a verified photo")
src("add_audio_only", "Record audio only")
src("add_import_section", "Import")
new("add_import_media", "Photo or video from gallery", "Foto o video dalla galleria",
    "Foto o vídeo de la galería", "Photo ou vidéo de la galerie", "Foto oder Video aus der Galerie")
src("add_import_file", "File")
src("audio_title", "Audio recording")
src("audio_tap_start", "Tap to start")
src("audio_tap_stop", "Tap to stop and save")
src("audio_consent_note",
    "Recording people may need their consent where you are. Audio is hashed and time-stamped like video.")
src("audio_failed", "Couldn't start the microphone.")

# ------------------------------------------------------------------- Home banner
src("home_cases_button", "Cases")
src("home_saved_title", "Recording saved")
src("home_saved_subtitle", "Add details or file it in a case")
src("home_saved_dismiss", "Dismiss")

# ---------------------------------------------------------------- Case report PDF
src("report_case_header", "Case report · %lld items · generated %@")
src("report_case_disclaimer",
    "Each item below lists the SHA-256 of what was captured and whether an independent RFC 3161 time-stamp proof exists for it. Details and notes were written afterwards by the person who made the recording and are not part of the hashed record.")
src("report_case_verify",
    "To check any item, drop its files together with the .json record and .tsr proof shared with this report into witnesscamera.com/verify. It runs in the browser and uploads nothing.")
src("report_field_name", "Name")
src("report_field_ended", "Ended")
src("report_field_location", "Location")
src("report_field_recording", "Recording")
src("report_field_segments", "Segments")
src("report_field_proof", "Time-stamp proof")
src("report_proof_yes", "Yes (RFC 3161)")
src("report_proof_no", "Not obtained")
src("report_imported_note", "Imported: the proof starts when the file was imported, not when it was made.")
src("report_details_after", "Details added afterwards")
src("report_notes_after", "Notes added afterwards")

# ------------------------------------------------- Earlier recordings and space
new("storage_free_up",
    "Free up %1$s: already in your gallery",
    "Libera %1$s: già nella tua galleria",
    "Liberar %1$s: ya está en tu galería",
    "Libérer %1$s : déjà dans votre galerie",
    "%1$s freigeben: bereits in Ihrer Galerie")
src("storage_freeing", "Freeing up space…")
new("storage_upload_earlier",
    "Upload %1$d earlier recording(s) from your gallery",
    "Carica %1$d registrazione/i precedente/i dalla galleria",
    "Subir %1$d grabación(es) anterior(es) de tu galería",
    "Envoyer %1$d enregistrement(s) précédent(s) depuis votre galerie",
    "%1$d frühere Aufnahme(n) aus Ihrer Galerie hochladen")
new("storage_uploading_earlier",
    "Uploading earlier recordings, %1$d of %2$d…",
    "Caricamento delle registrazioni precedenti, %1$d di %2$d…",
    "Subiendo grabaciones anteriores, %1$d de %2$d…",
    "Envoi des enregistrements précédents, %1$d sur %2$d…",
    "Frühere Aufnahmen werden hochgeladen, %1$d von %2$d…")
new("storage_kept_message",
    "%1$d recording(s) kept on this phone: their video is no longer in your gallery, so this is the only copy.",
    "%1$d registrazione/i conservata/e su questo telefono: il video non è più nella galleria, quindi questa è l'unica copia.",
    "%1$d grabación(es) conservada(s) en este teléfono: su vídeo ya no está en la galería, así que es la única copia.",
    "%1$d enregistrement(s) conservé(s) sur ce téléphone : leur vidéo n'est plus dans la galerie, c'est donc la seule copie.",
    "%1$d Aufnahme(n) auf diesem Telefon behalten: Das Video ist nicht mehr in Ihrer Galerie, dies ist also die einzige Kopie.")
new("storage_upload_paused",
    "Upload paused: %1$s Tap again to continue.",
    "Caricamento in pausa: %1$s Tocca di nuovo per continuare.",
    "Subida en pausa: %1$s Toca de nuevo para continuar.",
    "Envoi en pause : %1$s Touchez à nouveau pour continuer.",
    "Hochladen pausiert: %1$s Tippen Sie erneut, um fortzufahren.")
new("storage_upload_missing",
    "%1$d recording(s) couldn't be uploaded: their video is no longer in your gallery.",
    "%1$d registrazione/i non è/sono stata/e caricata/e: il video non è più nella galleria.",
    "%1$d grabación(es) no se pudo/pudieron subir: su vídeo ya no está en la galería.",
    "%1$d enregistrement(s) n'a/ont pas pu être envoyé(s) : leur vidéo n'est plus dans la galerie.",
    "%1$d Aufnahme(n) konnte(n) nicht hochgeladen werden: Das Video ist nicht mehr in Ihrer Galerie.")
