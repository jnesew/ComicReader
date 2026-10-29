# Store assets and provenance

## Screenshot captures

The [screenshot gallery](SCREENSHOTS.md) and fastlane selection show ComicViewer v1.4.0 on an
Android 16 (API 36) emulator. Portrait captures are 1080 × 2400 pixels; the landscape
spread is 2400 × 1080. Libraries use Compact cover size with three columns at
420 dpi and the default font scale. All captures are unedited PNGs from the app,
with no added device frames or promotional overlays. Android's demo status bar
sets the clock and system indicators consistently.

The title and series libraries show Dark appearance. The matching title-library
example and folder manager show Light appearance. Reader controls retain their
dark palette. Reading states, bookmarks, favorites, and settings were selected
through the app's normal controls.

English screenshots are supplied under `en-US`; Finnish listing text does not claim
the English captures are Finnish. The 512 px icon is existing project artwork, under MIT.

| Store image | Identical documentation capture | View |
| --- | --- | --- |
| 1.png | [series-library.png](screenshots/series-library.png) | Series, favorites, and unread counts |
| 2.png | [paged-reading.png](screenshots/paged-reading.png) | Single page, bookmark, and comic favorite |
| 3.png | [continuous-scroll.png](screenshots/continuous-scroll.png) | Continuous reading within a collection |
| 4.png | [library-folders.png](screenshots/library-folders.png) | Multiple library folders in Light appearance |
| 5.png | [continuous-series.png](screenshots/continuous-series.png) | Continuous reading across issues |
| 6.png | [light-library.png](screenshots/light-library.png) | Light appearance, issue badges, and hybrid status |
| 7.png | [title-library.png](screenshots/title-library.png) | Matching Dark title library |

The README previews the title library in both appearances, single-page reading,
and continuous series reading. The full gallery also shows library management,
immersive reading, reader options, and a landscape spread.

## Artwork sources

All visible comics come from the maintainer-supplied
`ComicViewer-Public-Domain-Sample-Library.zip`. Its `SHA256SUMS` entries were verified
before import. Archive SHA-256:
`408a36a11da35ce8ab19b733700007fb787ccd9e1ff7191c8de3ca65a5f72d99`.

The pack records each downloaded image's checksum, creator, source page, and
Wikimedia Commons public-domain designation. The table lists every page visible
in these captures, including partial cover thumbnails behind the editor. Those
designations concern the artwork; they do not place it under the repository's MIT
license.

| Classic page and source record | Creator |
| --- | --- |
| [Little Nemo in Slumberland — 1905-10-15](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-10-15.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-10-22](https://commons.wikimedia.org/wiki/File:Little_Nemo_in_Slumberland_(1905-10-22).jpeg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-10-29](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-10-29.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-05](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-05.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-12](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-12.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-19](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-19.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-11-26](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-11-26.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-12-03](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-12-03.jpg) | Winsor McCay |
| [Little Nemo in Slumberland — 1905-12-10](https://commons.wikimedia.org/wiki/File:Little_Nemo_1905-12-10.jpg) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1904-09-10](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1904-09-10.png) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1904-12-10](https://commons.wikimedia.org/wiki/File:Dream-of-the-Rarebit-Fiend-12-10-1904.jpg) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1905-01-07](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1905-01-07.jpg) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1905-01-28](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1905-01-28.jpg) | Winsor McCay |
| [Dream of the Rarebit Fiend — 1905-02-25](https://commons.wikimedia.org/wiki/File:Dream_of_the_Rarebit_Fiend_1905-02-25.jpg) | Winsor McCay |
| [Little Sammy Sneeze — 1905-05-28](https://commons.wikimedia.org/wiki/File:Little_Sammy_Sneeze_1905-05-28.jpg) | Winsor McCay |
| [The Upside Downs of Little Lady Lovekins and Old Man Muffaroo — 1904-03-13](https://commons.wikimedia.org/wiki/File:Ambigrams_by_Gustave_Verbeek_(1904)_-_comics_The_Upside_Downs_of_Little_Lady_Lovekins_and_Old_Man_Muffaroo_-_The_wonderful_cure_of_the_waterfall.jpg) | Gustave Verbeek |
| [The Yellow Kid — 1896-11-08](https://commons.wikimedia.org/wiki/File:1896-11-08_Yellow_Kid.jpg) | Richard F. Outcault |

For the multi-page reader examples, the twelve Little Nemo sample images were
repackaged unchanged in chronological order into **Little Nemo - Collected Pages**.
This local screenshot fixture demonstrates page navigation, bookmarks, continuous
reading, and two-page spreads. It is separate from the twelve individual issues
used to demonstrate continuous series reading. The paged capture shows the third
image (1905-10-29); the spread shows the fourth and fifth (1905-11-05 and 1905-11-12).
No source artwork was edited. ComicViewer scales the pages and crops cover thumbnails
to its cards. Comics are not bundled in the APK or added to the source repository.

## Listing layout

Localized listing text, icons, screenshots, and version-code changelogs live under
`fastlane/metadata/android/<locale>/`. Version 1.4.1 uses `changelogs/8.txt`;
code 7 belongs to v1.4.0, code 6 to v1.3.1, code 5 to v1.3.0, and code 4 to v1.2.0.
The screenshot gallery remains the v1.4.0 appearance reference; this maintenance
release does not change the interface.
