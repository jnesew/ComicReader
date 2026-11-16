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
| ZIP entries or listed folder children | 50,000 |
| Comic/EPUB/PDF/folder pages | 20,000 |
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
before page-sized arrays are allocated. Explicit image-folder imports apply the same page, child-count,
stream, and image limits, and cap known child-image sizes in aggregate at 4 GiB. Incomplete provider
listings are rejected before the saved page manifest is reconciled.

Visible and optional tiles, sampled backing images, and admitted tile output share one reader
session pixel allowance: half the Java heap class, bounded to 48–192 MiB. The allowance reserves
4–12 MiB for the slider preview cache. Visible pages are planned together across issue boundaries;
optional pixels and offscreen history are released first when visible allocations need room.
Tile decoding and cleanup use one session scheduler with at most three workers and serialized
work for each document, including retiring work that cannot be interrupted inside a platform codec.

Raster formats without region decoding retain at most one sampled full-image backing bitmap per
document. Its 24–64 MiB per-document limit and the 32-megapixel hard fallback limit apply in addition
to the shared session allowance; the decoder increases sampling when the shared allowance cannot
admit the preferred backing image. Actual image headers are validated before decoder construction
and supply allocation dimensions even while page-index metadata is provisional. Extracted page
files can survive decoder eviction within a 256 MiB cache per open document; an atomic extraction
can temporarily require one additional page file. Closing the document removes its page cache.

Memory-pressure trimming releases backing images and tiles; visible allocation failures retry at
a cheaper resolution with bounded attempts. This bounds retained and admitted application pixels,
not all temporary allocations inside Android's decoders, delayed reclamation, or GPU copies.
File-size limits are rejection ceilings rather than a promise that every document below them fits
every device.

EPUB and ComicInfo XML is decoded strictly as UTF-8 or UTF-16 before parsing. DTD and entity
declarations are rejected before the platform DOM parser sees them, and external resolution is
disabled. EPUB may contain the inert HTML5 doctype without an internal subset or external DTD.

## Build and release boundary

- Gradle 8.14.6's binary distribution and wrapper JAR have pinned published SHA-256 values.
- Android Gradle Plugin 8.13.2 and JUnit's transitive graph use strict dependency verification.
- A shared version policy patches affected transitive build-tool libraries in plugin and
  project configurations, including the separate connected-test runner. The
  `verifyToolchainDependencies` task checks resolved coordinates and empty application
  runtime graphs; see [BUILDING.md](BUILDING.md#dependency-verification).
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

Official release signing occurs outside CI. Signing credentials are never stored in this repository
or included in issues, messages, build logs, or release assets. Public release records may include
the non-secret signing certificate fingerprint and APK checksums.

## Backup caveat

`android:allowBackup="false"`, pre-Android-12 backup exclusions, and Android-12+ cloud/device
transfer exclusions request that all application data remain in its originating profile. Android
permits device manufacturers to customize some device-to-device transfer behavior, so the project
does not claim control over modified OEM software outside the application sandbox.

## Local verification

Follow the [build verification gate](BUILDING.md#verification), including actual
Android and manual device checks appropriate to the change. Release identity,
certificate pins, and publication checks are described in [RELEASING.md](RELEASING.md).

Vulnerability reports follow the root [security policy](../SECURITY.md).
