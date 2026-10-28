# Installation and updates

ComicViewer supports Android 8.0 (API 26) through Android 16 (API 36).
No comics are bundled; after installation, use the [user guide](USER_GUIDE.md#import-comics)
to add your files or library folders.

## Install an APK

Download `ComicViewer-Android-vX.Y.Z.apk` from the
[latest GitHub release](https://github.com/jnesew/ComicReader/releases/latest) and
open it on your Android device. Android may ask you to allow installation from the
app used to open the APK.

## Obtainium

Tap the **Get ComicViewer on Obtainium** badge in the [README](../README.md#install)
on an Android device to add the app to Obtainium. Alternatively, add the repository
URL manually:

```text
https://github.com/jnesew/ComicReader
```

Obtainium uses the signed APK attached to each GitHub release. Maintainers can find
the version, tag, and asset naming rules in the [release guide](RELEASING.md).

## Updates and compatibility

Install updates over the existing app to retain its library and reading state.
Android accepts an update only when the application ID and signing certificate
match. Builds from a different distribution channel may use a different certificate
and therefore require uninstalling the existing app first. See the
[changelog](../CHANGELOG.md) for changes in each release.

### Upgrading from version 1.0.0

Version 1.0.0 used the retired application ID `com.localtools.comicviewer`.
Starting with version 1.0.1, ComicViewer uses `io.github.jnesew.comicviewer`.
Android treats these as separate apps: uninstall 1.0.0 before installing a newer
version. App settings, reading state, and folder access from 1.0.0 do not transfer
automatically.

## Build your own APK

Follow [Building ComicViewer](BUILDING.md) for the required toolchain, build commands,
and signing details. An unsigned APK cannot be installed directly, and a debug APK
cannot replace a release signed with a different certificate.
