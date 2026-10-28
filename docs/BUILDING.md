# Building ComicViewer

## Requirements

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 35.0.1
- a POSIX shell for the verification scripts

The checked-in wrapper pins Gradle 8.14.6 and its distribution SHA-256. Android Gradle Plugin
8.13.2 and the test graph are covered by strict dependency verification. The APK has no
third-party runtime dependency.

## Canonical release candidate

Run `./build-release.sh` from a clean commit. It runs tests and release lint, then creates
an unsigned optimized Gradle APK with signing explicitly disabled. The result is
`dist/ComicViewer-Android-v<versionName>-unsigned.apk`, using the version in `gradle.properties`.
See [Signing and release verification](#signing-and-release-verification) below.
Use `./build-release.sh --output <path>` to select a new output file in an existing
directory without overwriting a previously saved candidate.

## Gradle build

Development uses a local checkout. Configure the SDK once, then run the relevant tests and
build tasks locally:

```bash
cp local.properties.example local.properties
# Edit sdk.dir in local.properties.
./gradlew --dependency-verification strict verifyToolchainDependencies testDebugUnitTest lintRelease assembleRelease
```

Without external signing configuration, `assembleRelease` produces an unsigned APK suitable for
source verification or distributor signing. Signing credentials must never be committed to source
control or exposed in CI logs or public build artifacts.

Run the full local gate with:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict verifyToolchainDependencies testDebugUnitTest lintRelease assembleRelease
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
This convenience build uses javac/D8 directly and differs from the optimized Gradle release;
use the Gradle candidate for distribution and signature-aware rebuild comparisons.

## Dependency verification

Repository filters allow Android build components only from Google's Maven repository and ordinary
Java/test components only from Maven Central. Plugin Portal is not configured. Do not weaken strict
verification or regenerate `gradle/verification-metadata.xml` without reviewing every changed
component and checksum.

The pinned AGP 8.13.2 selects older transitive libraries, including through its separate
Unified Test Platform configurations. `gradle/toolchain-security.gradle`, applied from
settings before project evaluation, patches their transitive dependency metadata in both
buildscript and project configurations, including lint's detached configurations.
It does not add library dependencies. The current pins are:

| Library family | Version | Addressed advisories include |
| --- | --- | --- |
| Tink | 1.14.1 | Regenerated Protobuf classes compatible with the pinned 3.x runtime |
| Netty 4.1 modules | 4.1.139.Final | [HTTP/TLS handling](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5) |
| Bouncy Castle (`jdk18on`) | 1.86 | [Certificate validation](https://github.com/advisories/GHSA-9pwp-9qqc-pr26), [GOST CTR](https://github.com/advisories/GHSA-574f-3g2m-x479) |
| Commons Compress | 1.28.0 | [Malformed archive processing](https://github.com/advisories/GHSA-4g9r-vxhx-9pgx) |
| jose4j | 0.9.7 | [Compressed JWE denial of service](https://github.com/advisories/GHSA-3677-xxcr-wjqv) |
| JDOM | 2.0.6.1 | [External XML entities](https://github.com/advisories/GHSA-2363-cqg2-863c) |
| Protobuf Java/Kotlin | 3.25.9 | [Unbounded parsing recursion](https://github.com/advisories/GHSA-735f-pc8j-v9w8) |

Netty modules remain on their existing 4.1 line; native `netty-tcnative` artifacts use
an independent version scheme. Bouncy Castle and Protobuf companion modules stay aligned.
These overrides can be removed when the pinned upstream toolchain selects safe versions.

`./gradlew --dependency-verification strict verifyToolchainDependencies` checks the
resolvable configurations in each project's buildscript and project containers,
including the hidden host-test configurations. It fails on unresolved dependencies,
unexpected versions of the patched modules, or a nonempty application runtime graph.
Each project writes the resolved coordinates to
`build/reports/dependency-security/resolved.json`. Detached configurations are outside
those containers; the strict `lintRelease` check exercises lint's separate graph.
CI and `build-release.sh` run both checks. The dependency task checks the reviewed
version policy, not a live vulnerability database.

The Gradle daemon treats unsafe pre-22 Protobuf generated code as an error through
`org.gradle.jvmargs` in `gradle.properties`. Updating the runtime alone cannot
replace generated classes embedded in another library; update that library too.

When updating the policy, review the advisory ranges and published artifacts, update
their SHA-256 pins, and remove obsolete pins for the replaced versions. Verify normal
builds and the real connected Android suite with strict verification. An empty app
runtime graph does not establish that a host-tool vulnerability is harmless.

## Reproducibility

`scripts/verify-reproducible-build.sh` creates two independent clean Git checkouts of the same commit, performs strict offline
release builds, and requires the unsigned APKs to match byte for byte. A signed APK may differ
because signing metadata is intentionally outside the source-build comparison.
The script requires a clean committed tree and a populated local Gradle dependency cache.

## Signing and release verification

Build the final release commit with the pinned toolchain and record the exact JDK distribution,
JDK patch version, and SDK platform revision alongside the candidate. From a clean commit, run:

```bash
./build-release.sh
scripts/verify-reproducible-build.sh
```

Keep the unsigned APK and sign a copy outside Gradle with the existing release key. An Android
update requires the same application ID and signing certificate. Verify the expected certificate
SHA-256 against an actual installed or published APK; a reported fingerprint alone is insufficient.
Confirm that the version code exceeds every previously distributed APK and that the release tag
matches `v<versionName>` from `gradle.properties`.

For manual SDK signing, set the following path and credential variables locally.
Provide passwords through environment references or the signer's interactive
prompts, never literal command arguments or tracked files:

```bash
"$ANDROID_HOME/build-tools/35.0.1/apksigner" sign \
  --alignment-preserved true \
  --ks "$COMICVIEWER_KEYSTORE_PATH" \
  --ks-key-alias "$COMICVIEWER_KEY_ALIAS" \
  --ks-pass env:COMICVIEWER_STORE_PASSWORD \
  --key-pass env:COMICVIEWER_KEY_PASSWORD \
  --out "$SIGNED_APK" "$UNSIGNED_APK"
scripts/verify-release-apk.sh "$SIGNED_APK" "$REBUILT_UNSIGNED_APK" "$VERIFIED_CERT_SHA256"
```

`UNSIGNED_APK` is the Gradle candidate, `SIGNED_APK` is the signed output, and
`REBUILT_UNSIGNED_APK` is the APK from an independent rebuild of the same commit.
`VERIFIED_CERT_SHA256` is the expected certificate digest. The verifier requires
`ANDROID_HOME`, Build Tools 35.0.1, and the external build-time `apksigcopier` Python module
(see the [release requirements](RELEASING.md#requirements));
it checks the certificate, ZIP alignment, and signature-aware equality with the rebuild.
The comparison helper preserves the copier's explicit UTF-8 ZIP filename flags on
Python versions that otherwise clear them. The original and reconstructed signatures
must both verify with the pinned SDK signer.
Neither unsigned reproducibility nor CI alone establishes a matching signed release.

Test upgrading the previous release with the same certificate, retaining library data,
document grants, reading state, bookmarks, metadata edits, favorites, and settings.
Signing keys remain local and are not shared with distributors.

### Obtainium release preparation

The tracked `scripts/prepare-obtainium-release.sh` orchestrates version checks,
reviewed notes, the canonical unsigned build, independent reproducibility, local
signing, APK verification, and safe output staging. Start with
[RELEASING.md](RELEASING.md) for version bumping, preflight, public preparation,
and unsigned development verification. It never pushes or publishes automatically.
Run its `--setup` once to verify the signing key and save the local keystore path
and alias. Normal preparation uses the tracked public certificate pin and hidden
password entry, automatically reusing the store password for the key when possible.
Its `--non-interactive` mode explicitly opts into environment-supplied passwords;
password fields in properties files are not used by the preparation script.

`scripts/verify-reproducible-build.sh --output <path>` can retain its independently
verified unsigned APK for signature-aware comparison. The destination must not
already exist and its parent directory must exist.

### Distribution metadata

Localized listing text, icons, screenshots, and version-code changelogs live under
`fastlane/metadata/android/en-US/` and `fi-FI/`. Fastlane software is not required.
Keep artwork attribution in [STORE-ASSETS.md](STORE-ASSETS.md) and
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

The F-Droid review template is [fdroid/io.github.jnesew.comicviewer.yml.in](fdroid/io.github.jnesew.comicviewer.yml.in).
It contains unresolved commit and certificate placeholders and is not a ready submission.
Use verified release values, validate the recipe in the target build environment, and compare
its rebuild against the actual signed APK before submission. A successful repository build
does not establish F-Droid acceptance.

## Actual Android database checks

With a test device or emulator connected, run:

```bash
./gradlew --dependency-verification strict connectedDebugAndroidTest
```

The platform-only DatabaseTestRunner exercises the production LibraryDatabase using a
separate named database. No user library is erased. It also runs real decoder/canvas cases.
Coverage includes transaction rollback, schema upgrade, metadata and reading-state retention,
source reconciliation, buffering preferences, and raster cache behavior. A document provider in the
test APK supplies real read grants for multi-folder, exact-copy fallback, incomplete traversal,
cancellation, and restart cases. Folder preferences and navigation use isolated test preferences.
The test provider and grant activity are absent from production APKs. Run this suite before
and after changes to database internals or resource ownership.
Without a device, `assembleDebugAndroidTest` checks packaging but does not run SQLite.
The supplementary `verify-database-roundtrip.sh` script runs example SQL policies through
Python SQLite; it does not execute the production Java database helper or its migrations.

Use an emulator or spare test device for debug instrumentation: its debug certificate
cannot replace an installed release APK signed with the existing release key.
