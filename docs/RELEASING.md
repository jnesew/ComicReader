# Releasing ComicViewer

This guide covers direct GitHub and Obtainium releases. Production signing is a maintainer-only
operation performed outside source control and CI.

## Release contract

Every stable direct release uses:

- application ID `com.localtools.comicviewer`;
- semantic Android `versionName` `X.Y.Z`;
- a positive, increasing Android `versionCode`;
- Git tag `vX.Y.Z` on the exact source commit;
- exactly one APK asset named `ComicViewer-Android-vX.Y.Z.apk`;
- the same long-lived production signing certificate as every earlier direct release;
- a matching `.sha256` checksum file.

The first public release is version name `1.0.0` and version code `1`. Development-signed
installations cannot update to the new production identity and must be uninstalled first.

## Production key

Generate the production key on the maintainer's trusted computer and keep independent encrypted
backups. Never commit or upload the keystore, passwords, populated signing properties, or shell
history containing secrets. Losing the key prevents future direct APKs from updating existing
installations.

An interactive example that avoids passwords in command arguments is:

```bash
keytool -genkeypair -v \
  -keystore /absolute/private/path/comicviewer-production.jks \
  -alias comicviewer \
  -keyalg RSA -keysize 4096 -validity 10000
```

Copy `signing/keystore.properties.example` to an external private location and set
`COMICVIEWER_SIGNING_PROPERTIES` to it, or provide all four documented signing environment
variables. See [../signing/README.md](../signing/README.md).

## Prepare the source

1. Update `comicViewerVersionName` and `comicViewerVersionCode` in `gradle.properties`.
2. Update `CHANGELOG.md`.
3. Run the complete unsigned verification gate:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

4. Test the unsigned or locally signed candidate on a secondary Android device.
5. Review `git status` and commit the exact verified source.

## Sign and stage

Configure signing outside the checkout. Before creating the final tag, a non-public dry run may use:

```bash
scripts/prepare-obtainium-release.sh --allow-untagged
```

Record the public certificate SHA-256 printed by the verifier. It is not secret. Once the exact
source commit is final, create `vX.Y.Z`, then require that certificate in the release gate:

```bash
export COMICVIEWER_EXPECTED_CERT_SHA256=the-recorded-public-certificate-digest
scripts/prepare-obtainium-release.sh
```

The script verifies a clean tagged checkout, package and version, zero requested permissions,
alignment, signature, certificate, and checksum. It stages one APK and checksum under
`dist/obtainium-vX.Y.Z/` and prints a `gh release create` command without executing it.

## Publish and test

Push the verified commit and tag, publish a normal GitHub Release, and confirm the asset names.
Add the public repository URL to Obtainium with prereleases disabled, install on a secondary phone,
then publish a higher-version-code test release before relying on automatic updates.

If F-Droid later signs its independently built APK with a different certificate, treat F-Droid and
direct/Obtainium packages as separate installation channels. Android does not cross-update between
different signing identities.
