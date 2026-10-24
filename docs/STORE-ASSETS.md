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

Exact source-record matching is incomplete for the editor capture's background thumbnails
and the 22 October page. The reader pages have been visually identified; the original
archive download manifest was unavailable during review. This inventory is not a completed
asset-rights audit.

Other documentation screenshots retain their existing notices in THIRD_PARTY_NOTICES.md:
Pepper & Carrot episode 10 (David Revoy et al., CC BY 4.0), and the original Signal in
the Rain sample (CC0). They are not bundled as comics or added to this store selection.

The [F-Droid metadata guide](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)
describes the locale directory layout. Version 1.3.0 uses localized
`<locale>/changelogs/5.txt` entries; code 4 belongs to v1.2.0 and code 3 to v1.1.0.

## Refreshed library captures

`docs/screenshots/series-library.png` and `library-folders.png` are unedited
1080 × 2400 PNG captures of ComicViewer v1.3.0 on an Android 16 (API 36) emulator.
The library uses Compact cover size with three columns. These replace the README's
series capture and add the multiple-folder dialog; the store selection above is unchanged.

The four classic covers below are matched to the supplied sample library's source
manifest and per-archive provenance. The manifest labels them public domain.

| Cover and source record | Creator | Source designation |
| --- | --- | --- |
| [Little Nemo in Slumberland — 1905-10-15](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-10-15.jpg) | Winsor McCay | Public domain |
| [Dream of the Rarebit Fiend — 1904-09-10](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1904-09-10.png) | Winsor McCay | Public domain |
| [Little Sammy Sneeze — 1905-05-28](https://commons.wikimedia.org/wiki/File:Little_Sammy_Sneeze_1905-05-28.jpg) | Winsor McCay | Public domain |
| [The Upside Downs of Little Lady Lovekins and Old Man Muffaroo — 1904-03-27](https://commons.wikimedia.org/wiki/File:Ambigrams_by_Gustave_Verbeek_(1904)_-_comics_The_Upside_Downs_of_Little_Lady_Lovekins_and_Old_Man_Muffaroo_-_The_bad_snake_and_the_good_wizard.jpg) | Gustave Verbeek | Public domain |

The remaining covers are *Pepper & Carrot — Episode 10: Summer Special*
(art, scenario, and Hereva universe by David Revoy; English translation by Alex Gryson;
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
[episode source](https://www.peppercarrot.com/en/webcomic-sources/ep10_Summer-Special.html))
and “Signal in the Rain,” an original CC0 test sample. ComicViewer scales and crops
the artwork to its cover cards. Artwork retains its original licensing.
