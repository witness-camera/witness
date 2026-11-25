# -*- coding: utf-8 -*-
"""Pro capture features from iOS 1.1.9: stealth landscape, faces-and-plates
blur, voice disguise.

The iOS descriptions name the iPhone and its sensor sizes, so the footers are
Android's own; short labels are lifted from the catalog.
"""
import strings_part9  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

src("pro_stealth_title", "Stealth landscape")
new("pro_stealth_footer",
    "Record a landscape 16:9 video while holding the phone upright, the way you'd hold it for a call or a text. Witness uses the camera's taller 4:3 frame and keeps a landscape strip from the middle, often 1920 × 1080 or better. The viewfinder shows exactly that strip. Single camera only, so the front camera pauses while it's on; not available with 10-bit HDR. With the phone held on its side, recording is normal.",
    "Registra un video orizzontale 16:9 tenendo il telefono in verticale, come lo terresti per una chiamata o un messaggio. Witness usa il formato 4:3, più alto, della fotocamera e tiene una striscia orizzontale al centro, spesso 1920 × 1080 o più. Il mirino mostra esattamente quella striscia. Solo fotocamera singola, quindi la fotocamera anteriore si ferma mentre è attivo; non disponibile con l'HDR a 10 bit. Con il telefono tenuto di lato, la registrazione è normale.",
    "Graba un vídeo horizontal 16:9 mientras sostienes el teléfono en vertical, como lo harías para una llamada o un mensaje. Witness usa el fotograma 4:3, más alto, de la cámara y conserva una franja horizontal del centro, a menudo de 1920 × 1080 o más. El visor muestra exactamente esa franja. Solo una cámara, así que la cámara delantera se detiene mientras está activo; no disponible con HDR de 10 bits. Con el teléfono de lado, la grabación es normal.",
    "Enregistrez une vidéo paysage 16:9 en tenant le téléphone à la verticale, comme pour un appel ou un message. Witness utilise l'image 4:3, plus haute, de l'appareil photo et garde une bande paysage au centre, souvent en 1920 × 1080 ou mieux. Le viseur montre exactement cette bande. Une seule caméra : la caméra avant se met en pause tant que c'est activé ; indisponible avec le HDR 10 bits. Avec le téléphone tenu sur le côté, l'enregistrement est normal.",
    "Nehmen Sie ein 16:9-Querformatvideo auf, während Sie das Telefon hochkant halten, so wie bei einem Anruf oder einer Nachricht. Witness nutzt das höhere 4:3-Bild der Kamera und behält einen Querformatstreifen aus der Mitte, oft 1920 × 1080 oder besser. Der Sucher zeigt genau diesen Streifen. Nur eine Kamera, daher pausiert die Frontkamera, solange es aktiv ist; nicht mit 10-Bit-HDR verfügbar. Wird das Telefon seitlich gehalten, wird normal aufgenommen.")
src("paywall_fact_stealth", "Landscape video with the phone held upright.")

src("pro_blur_title", "Blur faces and licence plates")
src("pro_voice_title", "Disguise voices in that copy")
new("pro_blur_footer",
    "Also save a copy of each recording, and of the selfie camera's video when it is recording, with faces and licence plates blurred, ready to post without exposing bystanders. Detection runs entirely on this phone and nothing is uploaded to analyse it. It is automatic and can miss a face turned away or a plate at a steep angle, so check the copy before publishing. Your original recording is never changed.",
    "Salva anche una copia di ogni registrazione, e del video della fotocamera frontale quando registra, con volti e targhe sfocati, pronta da pubblicare senza esporre i passanti. Il riconoscimento avviene interamente su questo telefono e niente viene caricato per analizzarlo. È automatico e può non rilevare un volto girato o una targa con un angolo molto stretto, quindi controlla la copia prima di pubblicarla. La registrazione originale non viene mai modificata.",
    "Guarda también una copia de cada grabación, y del vídeo de la cámara frontal cuando graba, con las caras y las matrículas difuminadas, lista para publicar sin exponer a los transeúntes. La detección se hace por completo en este teléfono y no se sube nada para analizarlo. Es automática y puede no detectar una cara de perfil o una matrícula en un ángulo muy cerrado, así que revisa la copia antes de publicarla. Tu grabación original nunca se modifica.",
    "Enregistre aussi une copie de chaque vidéo, et de celle de la caméra avant quand elle filme, avec les visages et les plaques d'immatriculation floutés, prête à publier sans exposer les passants. La détection se fait entièrement sur ce téléphone et rien n'est envoyé pour l'analyser. Elle est automatique et peut manquer un visage tourné ou une plaque vue sous un angle prononcé : vérifiez la copie avant de publier. Votre enregistrement original n'est jamais modifié.",
    "Speichert zusätzlich eine Kopie jeder Aufnahme, und des Videos der Frontkamera, wenn sie aufnimmt, mit verpixelten Gesichtern und Kennzeichen, bereit zum Veröffentlichen, ohne Umstehende preiszugeben. Die Erkennung läuft vollständig auf diesem Telefon, und nichts wird zur Analyse hochgeladen. Sie ist automatisch und kann ein abgewandtes Gesicht oder ein Kennzeichen in einem steilen Winkel übersehen, prüfen Sie die Kopie also vor dem Veröffentlichen. Ihre Originalaufnahme wird nie verändert.")
src("pro_voice_footer",
    "Voice disguise pitches voices down and reshapes their tone so speakers are hard to recognise while words stay clear. It deters ordinary listeners, not specialist analysis.")
src("paywall_fact_blur", "Faces and licence plates blurred, ready to publish.")
