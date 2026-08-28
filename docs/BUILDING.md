# Building ComicViewer

## Requirements

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.1
- a POSIX shell for the verification scripts

The checked-in wrapper pins Gradle 8.14.4 and its distribution SHA-256. Android Gradle Plugin
8.13.2 and the test graph are covered by strict dependency verification. The APK has no
third-party runtime dependency.

## Gradle build

```bash
cp local.properties.example local.properties
# Edit sdk.dir in local.properties.
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
```

Without a local signing configuration, `assembleRelease` produces an unsigned APK suitable for
source verification or external-distributor signing.

Run the full local gate with:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

## Dependency-free direct SDK build

`build-local.sh` invokes the installed Android SDK tools directly rather than Gradle. Set the SDK
and JDK locations, then request an unsigned build:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
./build-local.sh --unsigned
```

The result is written under `dist/` with the version from `gradle.properties`.

## Dependency verification

Repository filters allow Android build components only from Google's Maven repository and ordinary
Java/test components only from Maven Central. Plugin Portal is not configured. Do not weaken strict
verification or regenerate `gradle/verification-metadata.xml` without reviewing every changed
component and checksum.

## Reproducibility

`scripts/verify-reproducible-build.sh` creates two isolated source trees, performs strict offline
release builds, and requires the unsigned APKs to match byte for byte. A signed APK may differ
because signing metadata is intentionally outside the source-build comparison.

## Signing

Signing is optional for source verification and required for direct public distribution. Keep the
production keystore and populated properties file outside the checkout. See
[RELEASING.md](RELEASING.md) and [../signing/README.md](../signing/README.md).
