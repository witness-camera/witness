# -*- coding: utf-8 -*-
"""Generates res/values*/strings.xml for the Android port.

Translations for copy shared with the iOS build are lifted verbatim from that
build's String Catalog, so the two apps read identically in all five
languages. Android-specific copy carries its own translations.
"""
import json
import os
import sys
import xml.sax.saxutils as saxutils

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import strings_part13  # noqa: F401  (registers every part)
from strings_part1 import STRINGS

_HERE = os.path.dirname(os.path.abspath(__file__))
# Paths relative to this script: android/tools -> ../../ios (public layout) or ../../ (app repo layout).
CATALOG = next(p for p in (os.path.join(_HERE, "../../ios/WitnessRecord/Localizable.xcstrings"), os.path.join(_HERE, "../../WitnessRecord/Localizable.xcstrings")) if os.path.exists(p))
OUT_ROOT = os.path.join(_HERE, "../app/src/main/res")
LANGS = ["it", "es", "fr", "de"]

catalog = json.load(open(CATALOG))["strings"]


def translation(source, lang):
    entry = catalog.get(source)
    if not entry:
        return None
    unit = entry.get("localizations", {}).get(lang, {}).get("stringUnit")
    return unit.get("value") if unit else None


def to_android_format(text):
    """iOS format specifiers -> Android ones."""
    return text.replace("%@", "%s").replace("%lld", "%d")


def escape(text):
    """Android string resource escaping.

    Newlines become the literal \\n escape; apostrophes, quotes and @/? at the
    start of a value must be backslash-escaped or aapt2 rejects the resource.
    """
    text = saxutils.escape(text)  # & < >
    text = text.replace("\\", "\\\\") if "\\" in text else text
    text = text.replace("'", "\\'").replace('"', '\\"')
    text = text.replace("\n", "\\n")
    return text


missing = []
resources = {lang: {} for lang in ["en"] + LANGS}

for key, (kind, payload) in STRINGS.items():
    if kind == "src":
        english = payload
        resources["en"][key] = english
        for lang in LANGS:
            value = translation(english, lang)
            if value is None:
                missing.append((key, lang, english[:60]))
            else:
                resources[lang][key] = value
    else:
        resources["en"][key] = payload["en"]
        for lang in LANGS:
            value = payload.get(lang)
            if value is None:
                missing.append((key, lang, payload["en"][:60]))
            else:
                resources[lang][key] = value


def write(lang, values, folder):
    path = os.path.join(OUT_ROOT, folder)
    os.makedirs(path, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for key in sorted(values):
        value = escape(to_android_format(values[key]))
        lines.append('    <string name="%s">%s</string>' % (key, value))
    lines.append("</resources>")
    with open(os.path.join(path, "strings.xml"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return len(values)


counts = {"en": write("en", resources["en"], "values")}
for lang in LANGS:
    counts[lang] = write(lang, resources[lang], "values-%s" % lang)

print("keys per locale:", counts)
if missing:
    print("MISSING (%d):" % len(missing))
    for key, lang, sample in missing:
        print("  %-42s %s  %s" % (key, lang, sample))
else:
    print("no missing translations")
