<p align="center">
  <img src="artwork/comicviewer-launcher-icon-512.png" alt="ComicViewer icon" width="128">
</p>

# ComicViewer for Android

An offline reader for comics stored on your device, with no accounts, ads, analytics,
or network access.

Android 8.0–16 · English and Finnish · [MIT License](LICENSE)

## Features

- CBZ, ZIP, PDF, and image-based EPUB support.
- Single-page, two-page spread, and continuous reading, with zoom and reading-direction controls.
- Searchable library with multiple folders, series grouping, and favorites.
- Saved reading positions, bookmarks, and read/unread tracking across series.
- Light, Dark, and System default appearance.

## Install

Download the APK from the [latest GitHub release](../../releases/latest), or add
ComicViewer to Obtainium for updates:

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.jnesew.comicviewer%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fjnesew%2FComicReader%22%2C%22author%22%3A%22jnesew%22%2C%22name%22%3A%22ComicViewer%22%7D">
    <img src="artwork/badge-obtainium.png" alt="Get ComicViewer on Obtainium" width="200">
  </a>
</p>

See [installation and updates](docs/INSTALLING.md) for setup, manual Obtainium
configuration, and upgrade compatibility.

## Screenshots

### Library

<table>
  <tr>
    <td align="center"><a href="docs/screenshots/title-library.png"><img src="docs/screenshots/title-library.png" alt="Dark title library with issue badges, progress, and read status" width="240"></a><br><sub>Titles and progress</sub></td>
    <td align="center"><a href="docs/screenshots/light-library.png"><img src="docs/screenshots/light-library.png" alt="The same title library in Light appearance" width="240"></a><br><sub>Light appearance</sub></td>
  </tr>
</table>

### Reading

<table>
  <tr>
    <td align="center"><a href="docs/screenshots/paged-reading.png"><img src="docs/screenshots/paged-reading.png" alt="Single-page reading with bookmark, favorite, and zoom controls" width="240"></a><br><sub>Single-page reading</sub></td>
    <td align="center"><a href="docs/screenshots/continuous-series.png"><img src="docs/screenshots/continuous-series.png" alt="Continuous reading across a named series issue boundary" width="240"></a><br><sub>Continuous series reading</sub></td>
  </tr>
</table>

[Full screenshot gallery](docs/SCREENSHOTS.md) — series, two-page spreads, folders,
reader options, and more. [Public-domain artwork credits](docs/STORE-ASSETS.md#artwork-sources).

## Documentation

The [user guide](docs/USER_GUIDE.md) covers importing, supported formats, library
management, and reader controls. See the [changelog](CHANGELOG.md) for release history.

For development, see [building from source](docs/BUILDING.md),
[contributing](CONTRIBUTING.md), and [release preparation](docs/RELEASING.md).
For an independently branded app, see [forking and publishing](docs/FORKING.md).
Build-time and test-only components are listed in [third-party notices](THIRD_PARTY_NOTICES.md).

## Privacy and security

ComicViewer requests no Android permissions. Files stay on your device and are
opened through Android's system file picker. See the [privacy policy](PRIVACY.md),
[security model](docs/SECURITY_MODEL.md), or [report a vulnerability](SECURITY.md).
