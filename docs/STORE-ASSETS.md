# Store assets and provenance

The fastlane listing reuses existing screenshots without editing their pixels.
The uncropped notification screenshot is excluded. English screenshots are supplied
under en-US; Finnish listing text does not claim the English captures are Finnish.
The 512 px icon is the existing project artwork, under MIT.

| Store image | Existing screenshot | Artwork |
| --- | --- | --- |
| 1.jpg | docs/screenshots/paged-reading.jpg | Winsor McCay, Little Nemo in Slumberland, moon episode, 3 December 1905 |
| 2.jpg | docs/screenshots/continuous-series.jpg | Winsor McCay, Little Nemo in Slumberland, 15 and 22 October 1905 |
| 3.jpg | docs/screenshots/edit-comic.jpg | Little Nemo in Slumberland, 1905 issue thumbnails behind the current editor dialog |

The [moon page](https://commons.wikimedia.org/wiki/File:Little_Nemo_moon.jpg)
and [first strip](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-10-15.jpg)
are catalogued as public domain by Wikimedia Commons. These source records identify
McCay (died 1934) and the original newspaper artwork. The screenshots show scaled
renderings inside ComicViewer; the underlying comic art is not relicensed as MIT.

Before submission, finish matching every background thumbnail in the editor capture
and the 22 October page to its exact source record. The reader pages have been visually
identified; the original archive download manifest was not available in this checkout.
This listing is preparation, not a completed asset-rights audit. Additional library-grid
screenshots can be included after their individual comic sources are recorded here.

Other documentation screenshots retain their existing notices in THIRD_PARTY_NOTICES.md:
Pepper & Carrot episode 10 (David Revoy et al., CC BY 4.0), and the original Signal in
the Rain sample (CC0). They are not bundled as comics or added to this store selection.

The [F-Droid metadata guide](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)
describes the locale directory layout. Add the final release notes as
`<locale>/changelogs/<versionCode>.txt` before tagging; do not label this unreleased
refactor as versionCode 3, which belongs to the already released v1.1.0.
