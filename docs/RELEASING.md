# Preparing an Obtainium release

`scripts/prepare-obtainium-release.sh` prepares and verifies a release locally. It
does not commit, push, create tags, upload files, or publish a GitHub release.
`gradle.properties` remains the source of truth for the app version. Obtainium
consumes the normal signed APK attached to a matching GitHub release.

## Requirements

- Python 3.11 or newer, Bash, Git, and the toolchain in [BUILDING.md](BUILDING.md).
- Set `JAVA_HOME` to JDK 17. The script rejects another Java or compiler version.
- Locate the SDK through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in the
  ignored `local.properties`, in that order. Platform 36 and Build Tools 35.0.1
  must be installed.
- For public preparation and version bumping, install and authenticate GitHub CLI
  (`gh`). `origin` must identify the project's GitHub repository over HTTPS or SSH.
  Release history and APK downloads use read-only GitHub API requests.
- Public preparation additionally requires `apksigcopier`, the build-time tool used
  by `scripts/verify-release-apk.sh`, available on `PATH`.
- Keep a populated Gradle cache: the two independent reproducibility builds run
  with strict offline dependency verification.

Signing uses the existing ignored `signing/keystore.properties`, an alternative
file selected by `COMICVIEWER_SIGNING_PROPERTIES`, or these environment variables
(which take precedence over the properties file):

- `COMICVIEWER_KEYSTORE_PATH`
- `COMICVIEWER_KEY_ALIAS`
- `COMICVIEWER_STORE_PASSWORD`
- `COMICVIEWER_KEY_PASSWORD`

Relative keystore paths resolve from the repository root. Signing passwords are
passed to SDK tools through environment references, never literal command-line
arguments. They are not written to the bundle or manifest. Properties files use
Java `.properties` syntax, including escapes and continuations.

Set `COMICVIEWER_EXPECTED_CERT_SHA256` to the previously verified release
certificate fingerprint. Upper/lowercase and colon-separated fingerprints are
accepted. The script compares it with both the latest published APK and the
configured keystore alias before building, then checks the final signed APK.
The fingerprint is public; the private key and passwords remain local.

## Choose the version explicitly

Start from a clean checkout containing the intended changes. For example:

```bash
scripts/prepare-obtainium-release.sh --bump patch
```

Use `minor` or `major` instead when appropriate. The bump uses the higher of the
current and published version bases, increments the selected component, and resets
lower components. The Android version code becomes one above the greater of the
current code and all available published APK codes for the current application ID.
For example, v1.3.0/code 5 becomes v1.3.1/code 6 with `--bump patch`.

This command updates `gradle.properties` and creates:

- a dated section in `CHANGELOG.md`;
- `docs/RELEASE-NOTES-<version>.md`;
- English and Finnish `fastlane/metadata/android/<locale>/changelogs/<code>.txt`.

Existing release notes are never overwritten. Replace every generated `TODO` with
reviewed text; preparation rejects unfinished notes and localized changelogs above
500 characters. Commit the changes after editing. Normal preparation never bumps
the version automatically.

Published releases, including prereleases, establish the version-code baseline.
Draft releases and local-only test tags do not. The script reads package and version
information from published APKs, caches them under ignored `dist/release-cache/`,
and verifies cached bytes against GitHub's asset SHA-256 on every run. Network or
history-verification failure stops public preparation; stale cached release lists
cannot silently substitute for live history.

The retired `com.localtools.comicviewer` application ID is excluded. A release with
no APK is excluded only if its remote tagged source proves it used that retired ID.
Missing APKs for the current ID, ambiguous multiple-APK releases, absent asset
digests, or mismatched tag/APK versions require inspection rather than a guessed
baseline. The workflow supports stable `X.Y.Z` releases; published prerelease APKs
still count when choosing a safe version code.

## Commit, merge, and tag the source

Merge the intended release changes into `main` and push the source. Create and push
the exact `v<versionName>` tag at the release commit. Public preparation requires:

- a clean committed checkout, with `HEAD` at that exact local and remote tag;
- the release commit reachable from a freshly fetched remote `main`;
- a version name and code above the published baseline.

A tag by itself is insufficient if the corresponding source changes are absent
from remote `main`. Source and published-version checks run again before final staging. The script never
moves a tag or pushes anything on your behalf.

## Preflight, build, and review

After configuring signing and the expected certificate:

```bash
# Validate source, versions, toolchain, notes, signing configuration, and existing output.
scripts/prepare-obtainium-release.sh --check

# Run the full local verification, sign, and stage the completed bundle.
scripts/prepare-obtainium-release.sh
```

The full run calls the existing source-security and database-roundtrip checks,
`build-release.sh` (unit tests, release lint, optimized unsigned Gradle build), and
`verify-reproducible-build.sh` (two independent builds). The canonical unsigned
candidate must match that independently rebuilt APK byte for byte. It also checks
the runtime dependency graph, package/version, debuggable flag, permissions, ZIP
alignment, final certificate, and signature-aware equality with the rebuilt APK.
Signing happens only after the preceding build checks pass, outside Gradle.

The completed bundle appears atomically at `dist/obtainium-v<version>/`:

| File | Purpose |
| --- | --- |
| `ComicViewer-Android-v<version>.apk` | Verified signed APK |
| `<APK filename>.sha256` | APK checksum, usable with `sha256sum --check` |
| `release-notes.md` | Reviewed notes copied from the versioned source document |
| `changelog-en-US.txt`, `changelog-fi-FI.txt` | Localized listing changelogs |
| `release.json` | Source commit, version, certificate, toolchain, baseline, file hashes, and actual check results |

Preparation refuses to overwrite conflicting, modified, incomplete, or older-format
output. Identical reruns preserve the bundle. A rerun of an already published
version is allowed only from its exact source commit, with the same version code
and a byte-identical signed APK; it prints no new-publication command. Otherwise,
bump the version.

The canonical build's `--output` option puts the candidate inside the temporary
work directory. Preparation does not replace previously saved unsigned candidates
under `dist/` either.

A directory lock at `dist/.release-preparation.lock` prevents concurrent builds.
Ordinary failures and interruption clean the temporary bundle and release the lock.
After a process is forcibly killed or the host loses power, inspect for a running
build before removing a leftover lock or `.release-*` temporary directory. Existing
completed bundles remain intact.

Test installation, upgrade retention, and reader behavior on a device before
publishing. Preparation does not run connected Android or manual device tests;
`release.json` records those as `not_run`. Follow [BUILDING.md](BUILDING.md#actual-android-database-checks)
for the real connected suite. The supplementary Python SQL check is not that suite.

After reviewing the bundle, use the printed `gh release create` command if ready.
It names the exact repository/tag/assets and uses `--notes-file release-notes.md`.
Publication remains a separate manual action.

## Untagged local verification

To exercise the build workflow before merging/tagging, use a clean committed checkout:

```bash
scripts/prepare-obtainium-release.sh --check --development
scripts/prepare-obtainium-release.sh --development
```

This mode runs the same unsigned build and reproducibility checks without GitHub
access, signing credentials, or `apksigcopier`. It writes to
`dist/development-v<version>-<commit>/`, names the APK
`ComicViewer-Android-v<version>-development-unsigned.apk`, and records
`publishable: false`. It prints no publication command. An unsigned APK cannot
replace an installed signed release.

The former `--allow-untagged` and `--local-builder` flags are intentionally rejected.
Use `--development` for verification; `build-local.sh --unsigned` remains a separate
direct-SDK convenience build.

## Testing the release tooling

```bash
python3 -m unittest discover -s scripts/tests -v
```

These tests use temporary Git repositories/remotes and fake SDK, Gradle, signing,
and GitHub commands. They exercise release-policy failures, staging, and reruns
without real credentials or publication. They do not establish that real signing,
Android builds, or device upgrades pass; test the local workflow with the actual
toolchain as well.
