# Store assets and provenance

## Screenshot captures

The README gallery and fastlane screenshot selection show ComicViewer v1.3.0 on an
Android 16 (API 36) emulator. Portrait captures are 1080 × 2400 pixels; the landscape
spread is 2400 × 1080. The library uses Compact cover size with three columns at
420 dpi and the default font scale. All captures are unedited PNGs from the app,
with no added device frames or promotional overlays. Android's demo status bar
sets the clock and system indicators consistently.

English screenshots are supplied under `en-US`; Finnish listing text does not claim
the English captures are Finnish. The 512 px icon is existing project artwork, under MIT.

| Store image | Identical documentation capture | View |
| --- | --- | --- |
| 1.png | [series-library.png](screenshots/series-library.png) | Series, favorites, and progress |
| 2.png | [paged-reading.png](screenshots/paged-reading.png) | Single page, bookmark, and comic favorite |
| 3.png | [continuous-scroll.png](screenshots/continuous-scroll.png) | Long-strip continuous reading |
| 4.png | [library-folders.png](screenshots/library-folders.png) | Multiple library folders |
| 5.png | [continuous-series.png](screenshots/continuous-series.png) | Continuous reading across issues |

Additional README captures show the title library, immersive continuous reading,
reader options, comic metadata editing, and a landscape spread. The old JPEG
captures and store copies have been replaced.

## Artwork sources

Comics were supplied in the screenshot test pack and public-domain sample library.
Their checksum manifests were verified before import. The source records below
identify every classic page visible in the captures, including partial thumbnails
at the bottom of the title library. The sample library's per-archive provenance
records match these files; Wikimedia Commons catalogues the original newspaper
artwork as public domain. These designations concern the artwork, not a new MIT
license for it.

| Classic page and source record | Creator |
| --- | --- |
| [Little Nemo in Slumberland — 1905-10-15](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-10-15.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-10-22](https://commons.wikimedia.org/wiki/File:Little_Nemo_in_Slumberland_(1905-10-22).jpeg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-05](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-05.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-12](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-12.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-19](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-19.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-12-03](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-12-03.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-12-31](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-12-31.jpg) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1904-09-10](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1904-09-10.png) | Winsor McCay |
| [Little Sammy Sneeze — 1905-05-28](https://commons.wikimedia.org/wiki/File:Little_Sammy_Sneeze_1905-05-28.jpg) | Winsor McCay |
| [The Upside Downs of Little Lady Lovekins and Old Man Muffaroo — 1904-03-27](https://commons.wikimedia.org/wiki/File:Ambigrams_by_Gustave_Verbeek_(1904)_-_comics_The_Upside_Downs_of_Little_Lady_Lovekins_and_Old_Man_Muffaroo_-_The_bad_snake_and_the_good_wizard.jpg) | Gustave Verbeek |

*Pepper & Carrot — Episode 10: Summer Special* appears as a library cover, page 3
in the paged-reader capture, and pages 4–5 in the landscape spread (page numbers
include the sample archive's cover). The official
[episode source and credits](https://www.peppercarrot.com/en/webcomic-sources/ep10_Summer-Special.html)
license it under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/):

- Art and scenario: David Revoy.
- English translation: Alex Gryson.
- Hereva universe creation: David Revoy; lead maintainer: Craig Maloney.
- Hereva writers: Craig Maloney, Nicolas Artance, Scribblemaniac, and Valvin.
- Hereva correctors: Alex Gryson, CGand, Hali, Marno van der Maas, Moini, and Willem Sonke.

“Signal in the Rain — Episode 1: The Lost Signal” is an original AI-generated test
sample supplied by the maintainer. The pack dedicates the rights its distributor
holds, to the extent they exist, under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). Its cover appears in
the library and its 1200 × 12000 long-strip page appears in the continuous-reader
captures and behind the reader-options dialog.

ComicViewer scales the pages and crops cover thumbnails to its cards. The source
artwork retains its original licensing. Comics are not bundled in the APK.

## Listing layout

The [F-Droid metadata guide](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/)
describes the locale directory layout. Version 1.3.0 uses localized
`<locale>/changelogs/5.txt` entries; code 4 belongs to v1.2.0 and code 3 to v1.1.0.
