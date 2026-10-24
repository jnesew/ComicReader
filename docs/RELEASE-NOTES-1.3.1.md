# ComicViewer 1.3.1

GIF comics now receive cover thumbnails and import successfully into the library.
The cover decoder preserves the usual crop while bounding memory for image formats
that cannot decode a region directly.

The Actual size zoom option now displays `100%` correctly in English and Finnish.

For developers, the repository now includes the local Obtainium release preparation
script. It checks versions against published APKs, verifies reproducible builds,
and prepares a verified release bundle. Signing passwords use hidden terminal
prompts by default, with an explicit unattended mode for environment passwords.
See [Preparing an Obtainium release](RELEASING.md) for the workflow and versioning rules.
