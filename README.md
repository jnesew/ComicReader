<p align="center">
  <img src="artwork/comicviewer-launcher-icon-512.png" alt="ComicViewer icon" width="128">
</p>

# ComicViewer for Android

ComicViewer is a focused, offline comic reader for Android. It reads user-selected comics without
accounts, ads, analytics, subscriptions, network access, or broad storage permissions.

## Highlights

- CBZ, ZIP, image-based EPUB, and PDF support
- Single-page, cover-aware two-page spread, and memory-conscious continuous-scroll reading
- Fit width, fit page, actual size, pinch zoom, double-tap zoom, and tiled rendering
- Per-title progress, custom titles, zoom, scroll position, reading direction, bookmarks, and favorites
- Read/unread actions and series unread counts, including newly imported issues
- Selectable page buffering: Increased by default at 4 GB RAM, High at 8 GB, Standard below 4 GB
- Global layout and direction defaults with per-title overrides
- Seamless continuous scrolling and symmetric first/last-page navigation across series issues
- Searchable cover library with filters, sorting, configurable density, and cached covers
- Optional recursive scanning of multiple user-selected library folders, with exact-copy reconciliation
- Verified automatic reconnection of moved comics when their full content identity was retained
- Series grouping from conservative metadata or folder structure, with per-comic correction and independent series favorites
- Page previews while scrubbing, natural page ordering, and direct “open with” support
- English and Finnish interface resources
- Android 8.0 (API 26) through Android 16 (API 36)

## Screenshots

Captured in ComicViewer v1.3.0 on Android 16. Both library views use Compact cover size
with three covers per row. Click a screenshot to view it at full resolution.

<table>
  <tr>
    <td align="center"><a href="docs/screenshots/series-library.png"><img src="docs/screenshots/series-library.png" alt="Three-column series library with favorites and mixed reading progress" width="260"></a><br><sub>Series and favorites</sub></td>
    <td align="center"><a href="docs/screenshots/title-library.png"><img src="docs/screenshots/title-library.png" alt="Three-column title library with issue numbers and saved reading progress" width="260"></a><br><sub>Titles and progress</sub></td>
    <td align="center"><a href="docs/screenshots/paged-reading.png"><img src="docs/screenshots/paged-reading.png" alt="Pepper and Carrot in Fit page mode with separate bookmark and favorite controls" width="260"></a><br><sub>Single-page reading</sub></td>
  </tr>
  <tr>
    <td align="center"><a href="docs/screenshots/continuous-scroll.png"><img src="docs/screenshots/continuous-scroll.png" alt="Signal in the Rain long strip in continuous mode with reader controls visible" width="260"></a><br><sub>Continuous scrolling</sub></td>
    <td align="center"><a href="docs/screenshots/continuous-series.png"><img src="docs/screenshots/continuous-series.png" alt="Little Nemo issues separated by a named issue boundary in continuous reading" width="260"></a><br><sub>Continuous series reading</sub></td>
    <td align="center"><a href="docs/screenshots/library-folders.png"><img src="docs/screenshots/library-folders.png" alt="Two local library folders with a shared rescan action" width="260"></a><br><sub>Multiple library folders</sub></td>
  </tr>
</table>

<p align="center">
  <a href="docs/screenshots/two-page-spread.png"><img src="docs/screenshots/two-page-spread.png" alt="Complete Pepper and Carrot pages displayed side by side in an immersive landscape spread" width="800"></a><br>
  <sub>Two-page spread</sub>
</p>

<details>
<summary>Immersive reading, reader options, and comic details</summary>

<table>
  <tr>
    <td align="center"><a href="docs/screenshots/immersive-continuous-reading.png"><img src="docs/screenshots/immersive-continuous-reading.png" alt="Signal in the Rain long strip with all reader controls hidden" width="260"></a><br><sub>Immersive reading</sub></td>
    <td align="center"><a href="docs/screenshots/reader-options.png"><img src="docs/screenshots/reader-options.png" alt="Reader preferences for controls, zoom, layout, direction, and buffering" width="260"></a><br><sub>Reader options</sub></td>
    <td align="center"><a href="docs/screenshots/edit-comic.png"><img src="docs/screenshots/edit-comic.png" alt="Comic editor with title, series, and issue number fields" width="260"></a><br><sub>Edit comic details</sub></td>
  </tr>
</table>

</details>

<sub>Sample artwork: [Pepper & Carrot — Episode 10: Summer Special](https://www.peppercarrot.com/en/webcomic-sources/ep10_Summer-Special.html), art, scenario, and Hereva creation by David Revoy; English translation by Alex Gryson; [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). “Signal in the Rain” is an original AI-generated test sample supplied under [CC0](https://creativecommons.org/publicdomain/zero/1.0/). Classic comics are by Winsor McCay and Gustave Verbeek. See [artwork sources and full credits](docs/STORE-ASSETS.md#artwork-sources).</sub>

## Privacy by design

ComicViewer requests no Android permissions, including no `INTERNET`, storage, or media permission.
Files are opened through Android's system document picker, which grants access only to items the user
chooses. The app has no Google Play Services, Firebase, advertising, analytics, telemetry, account,
or third-party runtime library.

See the full [privacy policy](PRIVACY.md) and
[security model](docs/SECURITY_MODEL.md).

## Install

Download `ComicViewer-Android-vX.Y.Z.apk` from the
[latest GitHub Release](../../releases/latest). Android may ask you to allow installation from the
app used to open the APK.

> [!IMPORTANT]
> Version 1.0.0 used the retired application ID `com.localtools.comicviewer`. Starting with
> version 1.0.1, ComicViewer uses `io.github.jnesew.comicviewer`. Android cannot update across application
> IDs: uninstall 1.0.0 before installing 1.0.1 or later. App settings, reading state, and folder
> access from 1.0.0 do not transfer automatically.

Android accepts an update only when the application ID and signing certificate match. Builds from a
different distribution channel may use a different certificate and therefore require uninstalling
the existing app first.

### Obtainium

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.jnesew.comicviewer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fjnesew%2FComicReader%22%2C%22author%22%3A%22jnesew%22%2C%22name%22%3A%22ComicViewer%22%7D">
    <img src="artwork/badge-obtainium.png" alt="Get ComicViewer on Obtainium" width="200">
  </a>
</p>

Tap the badge on an Android device to add ComicViewer to Obtainium, or add this repository's public
URL manually. Stable releases use matching `vX.Y.Z` tags and Android version names, an increasing
version code, and one APK named `ComicViewer-Android-vX.Y.Z.apk`.

## Basic controls

| Gesture or control | Result |
|---|---|
| Tap the left or right edge | Previous or next page, adjusted for reading direction |
| Swipe horizontally while fitted | Previous or next page |
| Drag | Pan a zoomed page or scroll vertically |
| Pinch | Zoom around the gesture focus |
| Double tap | Zoom in or return to Fit width |
| Center tap | Show or hide reader controls |
| Reading-layout control | Choose single-page, two-page spread, or continuous mode |
| Page slider | Preview and open another page |

The [user guide](docs/USER_GUIDE.md) covers importing, library folders, series grouping, reading
modes, controls, and local data behavior.

## Build from source

Requirements are JDK 17, Android SDK Platform 36, and Android SDK Build Tools 35.0.1.

```bash
cp local.properties.example local.properties
# Set sdk.dir in local.properties, then:
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
```

Without local signing configuration, the release build is intentionally unsigned. Detailed Gradle,
direct-SDK, and reproducibility instructions are in [docs/BUILDING.md](docs/BUILDING.md).

## Scope

CBR/RAR and CB7/7z are intentionally unsupported. EPUB support is limited to fixed-layout or
consistently single-image spine pages; ComicViewer is not a general reflowable ebook reader.

## Contributing and security

Contributions should preserve the app's narrow offline purpose and zero-permission runtime. See
[CONTRIBUTING.md](CONTRIBUTING.md) and the [vulnerability-reporting policy](SECURITY.md).

ComicViewer is free and open-source software under the [MIT License](LICENSE). Build-time and
test-only components are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
