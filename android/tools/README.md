# tools

`gen_strings.py` regenerates `app/src/main/res/values*/strings.xml`.

Copy shared with the iOS app is not translated again here: the generator reads
the iOS build's `WitnessRecord/Localizable.xcstrings` and lifts the existing
it/es/fr/de translations verbatim, so the two apps read identically. Only copy
that had to change for Android — no Action Button, no Siri, a gallery instead
of Photos, the foreground-service notification — carries its own translations,
declared inline in `strings_part*.py` via `new(...)`.

Run it from this directory after editing the string table:

```sh
python3 gen_strings.py
```

It prints the key count per locale and lists anything missing a translation.
