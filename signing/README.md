# Local release signing

Android accepts an update only when the application ID and signing identity match the installed
app. ComicViewer's signing material is therefore never committed.

For a local signed release build:

1. Copy `keystore.properties.example` to the ignored `keystore.properties`, or keep the populated
   file completely outside the checkout.
2. Point `storeFile` at the external keystore and provide the passwords and key alias.
3. Run `./gradlew assembleRelease` or `./build-local.sh`.

To keep the properties file outside the checkout:

```bash
export COMICVIEWER_SIGNING_PROPERTIES=/absolute/private/path/comicviewer-signing.properties
./gradlew assembleRelease
```

The four values may instead be provided through `COMICVIEWER_KEYSTORE_PATH`,
`COMICVIEWER_STORE_PASSWORD`, `COMICVIEWER_KEY_ALIAS`, and
`COMICVIEWER_KEY_PASSWORD`.

With no signing configuration, Gradle intentionally produces an unsigned release for source
verification or external-distributor signing. The direct SDK build requires
`./build-local.sh --unsigned` for the same purpose.

Never place a private key or signing password in a commit, source archive, release asset, issue,
message, command argument, or build log. Only the public certificate fingerprint belongs in
release records. Follow [the release process](../docs/RELEASING.md) before publishing an APK.
