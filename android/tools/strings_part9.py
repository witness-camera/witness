# -*- coding: utf-8 -*-
"""iOS 1.1.9 - 1.1.12 settings and recording changes: the free on-screen
timestamp (with the Witness line for non-Pro), Pro stabilization, the
quick-start screen choice, Support moving into Settings, and moment markers.

Lifted from iOS's String Catalog where it has the text; Android-only wording
carries its own translations.
"""
import strings_part8  # noqa: F401  (registers every part)
from strings_part1 import STRINGS, src, new

# ---------------------------------------------------- free visible timestamp
src("recorded_with_witness", "Recorded with Witness")
src("settings_timestamp_toggle", "Visible timestamp & location")
src("settings_timestamp_footer",
    "Burns the date, time and coordinates into the picture itself, visible to anyone who watches the video. This is separate from the invisible metadata every recording already carries, on or off: the SHA-256 hash, the chain-of-custody manifest, and the independent RFC 3161 timestamp.")
src("settings_timestamp_footer_branded",
    "It also reads \"Recorded with Witness\". Witness Pro leaves only the time and place.")
new("settings_timestamp_hdr_note",
    "Switched off while 10-bit HDR is on.",
    "Disattivato finché l'HDR a 10 bit è attivo.",
    "Desactivado mientras el HDR de 10 bits esté activado.",
    "Désactivé tant que le HDR 10 bits est activé.",
    "Aus, solange 10-Bit-HDR aktiv ist.")

# ------------------------------------------------------------ Pro stabilization
src("stabilization_title", "Stabilization")
src("stabilization_off", "Off")
src("stabilization_standard", "Standard")
src("stabilization_cinematic", "Cinematic")
src("stabilization_extreme", "Extreme")
src("stabilization_footer",
    "Standard smooths small shakes with almost no crop. Cinematic is steadier and crops in more. Extreme is the steadiest, for walking or running, and crops in the most. Off keeps the full frame with no smoothing.")
src("stabilization_fallback",
    "Your current resolution and frame rate can't do this one, so the strongest mode they can do is used instead.")
new("stabilization_unsupported",
    "This phone's camera can't do this one, so the strongest mode it can do is used instead.",
    "La fotocamera di questo telefono non supporta questa modalità: viene usata la più forte che può gestire.",
    "La cámara de este teléfono no admite esta opción, así que se usa el modo más potente que sí admite.",
    "L'appareil photo de ce téléphone ne gère pas ce mode : le plus fort qu'il peut gérer est utilisé à la place.",
    "Die Kamera dieses Telefons kann diese Stufe nicht, daher wird die stärkste unterstützte verwendet.")

# ------------------------------------------------------- quick-start screen
src("settings_quick_start_screen", "Screen when started this way")
src("quick_start_black", "Black")
src("quick_start_camera", "Show camera")
src("settings_quick_start_footer_screen",
    "Black keeps the screen dark while recording; triple-tap it for the controls, then tap the eye to see the camera. Show camera opens straight into the viewfinder.")

# ------------------------------------------------- Help and Support in Settings
src("settings_help_row", "Help finding your footage")
src("settings_section_support", "Support")
src("settings_support_footer", "Kept running by donations.")

# ------------------------------------------------------------ moment markers
src("markers_mark_moment", "Mark this moment")
src("markers_marked", "Marked %lld:%@")
src("paywall_fact_stabilization", "Stabilization from off to extreme, for walking or running.")
src("paywall_fact_stamp_no_tag", "On-screen timestamp and location without the Witness tag.")
src("paywall_fact_markers", "Mark key moments with the volume button.")
