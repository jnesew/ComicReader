# Building from source

Run commands from the repository root. For a renamed application or independent
release, start with [Forking and publishing](FORKING.md).

## Toolchain and local setup

| Component | Required version | Source of truth |
| --- | --- | --- |
| JDK | 17 | Java compatibility in `app/build.gradle` |
| Gradle | 8.14.6 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.13.2 | `build.gradle` |
| SDK Platform | 36 | `app/build.gradle` |
| SDK Build Tools | 35.0.1 | `app/build.gradle` and SDK scripts |

The shell scripts require Bash, Git, `sha256sum`, and ordinary Unix utilities;
release preparation also requires Python 3.11+. Maintainer checks run on Linux.
Other hosts need equivalent tools (for example GNU coreutils on macOS).
The Gradle wrapper also includes a Windows launcher. The direct SDK convenience
script uses GNU `touch -d`; use the Gradle build on macOS and Windows.

Install the JDK and SDK packages, then configure their locations. For example:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Alternatively, copy `local.properties.example` to the Git-ignored
`local.properties` and set `sdk.dir`. Release preparation checks `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, then `sdk.dir`, in that order. It requires the Java and compiler
versions to agree with JDK 17. SDK licenses must be accepted locally.

The first Gradle run needs repository access to populate the dependency cache.
Use the checked-in wrapper and retain strict dependency verification:

```bash
./gradlew --dependency-verification strict assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. With an emulator or
spare test device connected, `./gradlew installDebug` installs it; open the app
from the device launcher. Debug signing cannot replace an installed release with
a different certificate. Prefer a test device for instrumentation runs.

### Standard reader alongside an installed release

Run `scripts/build-reader-debug.sh` to build **ComicViewer Reader Test**, the
standard reader with the current branch's changes and a separate
`<configured application ID>.readertest` identity. Its source-stamped APK is copied
to `dist/`. It uses the local debug signing key, is debuggable and unoptimized,
and contains no benchmark runner or synthetic comics. Use the optimized Render
Bench APK for controlled performance comparisons.

The reader test installs alongside the published reader and Render Bench, with
independent library data, settings and document grants. Select/import comics again
through the usual document picker. Normal reader memory limits apply; the larger
benchmark-only allowance is not enabled. Its version name follows the reader;
`comicViewerReaderTestVersionCode` is independent and must increase before changed
reader-test APKs are distributed. Release/debug identities and Obtainium metadata
remain configured by their existing settings.

## Verification

Run the full source and release-build gate:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict verifyToolchainDependencies testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

The last command requires a clean committed tree and a populated dependency cache.
It builds that commit twice in isolated checkouts using strict offline resolution,
then requires byte-for-byte identical unsigned APKs. Do not discard edits to make
it run; commit the intended source first. Its `--output <new-path>` option retains
an independently verified APK without overwriting an existing file.

| Check | Evidence provided |
| --- | --- |
| `verify-source-security.sh` | Wrapper/distribution pins, tracked secrets/packages, source permission declarations |
| `verifyToolchainDependencies` | Reviewed dependency versions, complete resolution of configured host graphs, empty app runtime graphs |
| `testDebugUnitTest` | Host-side Java unit tests |
| `lintRelease` and `assembleRelease` | Release lint and optimized APK compilation/packaging |
| `verify-database-roundtrip.sh` | Supplementary SQL policies through Python SQLite |
| `verify-reproducible-build.sh` | Two isolated builds of the committed source match |

### Android instrumentation tests

For a separate, tap-to-run rendering comparison APK with bundled synthetic comics
and user-exported results, see [Standalone rendering comparison](RENDER_BENCH.md).
That device workflow needs no ADB and is separate from the connected suite below.

Run on a connected emulator or test device:

```bash
./gradlew --dependency-verification strict connectedDebugAndroidTest
```

The platform-only runner exercises production SQLite, migrations, reconciliation,
resource ownership, real decoder/canvas rendering, and library UI behavior. It uses
an isolated database and preferences. Its fixture document provider and grant
activity exist only in the test APK. Run this suite before and after database or
resource-ownership changes.

`assembleDebugAndroidTest` only packages the tests. The supplementary Python SQL
script does not execute production Java, Android SQLite, or migrations. Successful
builds do not establish manual rendering, memory-pressure, provider, lifecycle,
upgrade, or distribution behavior; record actual device evidence separately.

## Unsigned release candidate

From a clean committed tree:

```bash
./build-release.sh
```

This runs a clean optimized Gradle build, dependency checks, unit tests, and lint
with signing disabled. It writes
`dist/<comicViewerArtifactPrefix>-v<comicViewerVersionName>-unsigned.apk`.
Both values come from `gradle.properties`. Use `--output <new-path>` to preserve
an earlier candidate; the output's parent directory must already exist.

For signing, versioning, tags, and verified bundles, use the single procedure in
[RELEASING.md](RELEASING.md). Store-listing assets and attribution are described
in [STORE-ASSETS.md](STORE-ASSETS.md); distribution templates and fork identity are
covered in [FORKING.md](FORKING.md).

`build-local.sh --unsigned` is an optional direct `javac`/D8 convenience build. It
needs JDK 17, the SDK environment variables, and Unix `zip`/GNU utilities. It does
not run release lint, unit tests, or R8 optimization. Use the Gradle candidate for
release verification and distribution.

## Dependency verification

The wrapper JAR and Gradle distribution have pinned official SHA-256 values.
Gradle's dependency graph is covered by `gradle/verification-metadata.xml`.
Repository filters route Android build groups to Google's Maven repository and
ordinary Java/test groups to Maven Central; Plugin Portal is not configured.
Review changed artifacts and upstream checksums before updating these pins.

AGP's transitive libraries are patched by `gradle/toolchain-security.gradle`.
The shared component-metadata policy covers plugin dependencies, project
configurations, the isolated Android test runner, and lint's detached graph:

| Family | Version | Advisory examples |
| --- | --- | --- |
| Tink | 1.14.1 | Replaces legacy generated Protobuf classes |
| Guava (host tools) | 33.7.2-jre | [Deserialization memory allocation](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) |
| Netty 4.1 | 4.1.139.Final | [HTTP/TLS handling](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5) |
| Bouncy Castle `jdk18on` | 1.86 | [Certificate validation](https://github.com/advisories/GHSA-9pwp-9qqc-pr26), [GOST CTR](https://github.com/advisories/GHSA-574f-3g2m-x479) |
| Commons Compress | 1.28.0 | [Malformed archives](https://github.com/advisories/GHSA-4g9r-vxhx-9pgx) |
| jose4j | 0.9.7 | [Compressed JWE](https://github.com/advisories/GHSA-3677-xxcr-wjqv) |
| JDOM | 2.0.6.1 | [External XML entities](https://github.com/advisories/GHSA-2363-cqg2-863c) |
| Protobuf Java/Kotlin | 3.25.9 | [Parsing recursion](https://github.com/advisories/GHSA-735f-pc8j-v9w8) |

Companion modules stay aligned. Native `netty-tcnative` has an independent version
scheme. The daemon treats unsafe pre-22 generated Protobuf code as an error through
`org.gradle.jvmargs`; updating a runtime alone does not replace generated classes
inside a dependency. Update the library that contains them too.

`verifyToolchainDependencies` inspects the resolvable configurations in each
project's buildscript and project containers. It rejects incomplete resolution,
unexpected protected versions, and nonempty application runtime graphs. Reports
are written to each project's `build/reports/dependency-security/resolved.json`.
Detached lint configurations are exercised by strict `lintRelease`. CI and the
canonical release script run both checks. This is a reviewed version policy, not
a live advisory scan.

These overrides do not replace libraries bundled inside Gradle itself. Gradle
8.14.6 still supplies Guava 33.4.6 to its own core classloader; the Guava 33.7.2
override patches the project-resolved AGP/test tooling, not that bundled copy.
The bundled copy needs separate upstream assessment or a patched distribution;
its deserialization reachability has not been established by these dependency checks.

When changing the policy, verify artifacts, remove obsolete pins for replaced
versions, and test normal builds plus the connected suite. Overrides can be removed
when the pinned upstream tools select safe versions. Empty app runtime graphs do
not establish that host-tool vulnerabilities are harmless. See the
[security model](SECURITY_MODEL.md) for input limits and other boundaries.
