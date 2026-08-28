# Security and privacy model

ComicViewer is an offline document viewer. It requests no Android permission, has no production
dependency, performs no network operation, and accepts content only through Android's Storage
Access Framework grants. Comic files and document-provider metadata are nevertheless untrusted.

## Hostile-input limits

The shared `InputLimits` policy is applied at the point bytes are read, not only to provider or ZIP
size declarations. Declarations remain useful early-rejection signals.

| Input | Limit |
|---|---:|
| Provider/archive/PDF document copy or complete fingerprint | 4 GiB |
| Expanded image page or extracted cover | 256 MiB |
| ZIP entries | 50,000 |
| Comic/EPUB/PDF pages | 20,000 |
| Declared aggregate ZIP expansion | 16 GiB |
| Declared compression ratio (entries at least 1 MiB) | 1,000:1 |
| XML metadata document | 4 MiB (ComicInfo.xml remains 1 MiB) |
| Image dimension | 262,144 pixels per axis |
| Image area | 256 megapixels |
| Non-region-decoder fallback bitmap | 32 megapixels after sampling |
| Display title or series name | 512 Unicode code points |
| Issue number | 128 Unicode code points |
| Provider/archive identifier or relative path | 8,192 Unicode code points |

Streams that exceed their byte budget, repeatedly return no data, or observe thread interruption
fail with a controlled I/O error. Temporary archive, PDF, page, and cover writes use a sibling
`.partial` file and remove it on failure or cancellation before any complete cache is replaced.
Image dimensions are checked before preview/full-bitmap allocation, and PDF/CBZ counts are checked
before page-sized arrays are allocated.

## Build and release boundary

- Gradle 8.14.4's binary distribution and wrapper JAR have pinned published SHA-256 values.
- Android Gradle Plugin 8.13.2 and JUnit's transitive graph use strict dependency verification.
- Repository filters fail closed: Android build groups resolve only from Google, while test and
  ordinary Java groups resolve from Maven Central. Plugin Portal is not configured.
- `scripts/verify-source-security.sh` checks the wrapper, dependency metadata, tracked artifacts,
  permission declarations, and common private-key/credential patterns.
- `scripts/verify-database-roundtrip.sh` checks normalized supplementary-plane Unicode and the
  Java metadata limits across a close/reopen SQLite round trip.
- `scripts/verify-reproducible-build.sh` builds two isolated unsigned source trees and requires
  byte-for-byte identical APKs.
- CI has read-only repository permission, creates no signed artifact, and checks the final APK has
  no requested Android permission and no runtime dependency.

Production signing keys and passwords remain on the maintainer's computer or trusted signing
system. They are never copied into source, CI, issues, messages, or release assets. The first public
APK establishes the long-lived direct/Obtainium certificate.

## Backup caveat

`android:allowBackup="false"`, pre-Android-12 backup exclusions, and Android-12+ cloud/device
transfer exclusions request that all application data remain in its originating profile. Android
permits device manufacturers to customize some device-to-device transfer behavior, so the project
does not claim control over modified OEM software outside the application sandbox.

## Local verification

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

The release-specific package, version, permission, alignment, signature, certificate, and checksum
gate is `scripts/prepare-obtainium-release.sh`. Vulnerability reports follow the root
[security policy](../SECURITY.md).
