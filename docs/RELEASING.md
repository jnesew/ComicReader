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
  by `scripts/verify-release-apk.sh`, importable by the same `python3` interpreter.
- Keep a populated Gradle cache: the two independent reproducibility builds run
  with strict offline dependency verification.

A system package providing the Python module works. Alternatively, install the
pinned, hash-verified release tools in a virtual environment and activate it before
running preparation (the environment path below is an example):

```bash
python3 -m venv /path/to/release-tools
source /path/to/release-tools/bin/activate
python3 -m pip install --require-hashes --only-binary=:all: -r scripts/release-tools.requirements.txt
python3 scripts/compare_apk_signatures.py --check
```

The compatibility helper preserves explicit UTF-8 filename flags when copying APK
signature metadata. Newer Python ZIP writers can otherwise clear those flags on
ASCII names, causing a valid signed APK to fail comparison with its own rebuild.
It still verifies both the original and reconstructed APK signatures with Build
Tools 35.0.1; it does not accept a content digest mismatch.

Run the release-tool tests with JDK 17 and the pinned SDK configured:

```bash
COMICVIEWER_REQUIRE_SIGNING_TESTS=1 python3 -m unittest discover -s scripts/tests -v
```

This includes real signing and comparison using disposable RSA-2048 and RSA-4096
keys, plus rejection of changed rebuilds, corrupted APKs, and wrong certificates.
The environment flag makes missing native-test prerequisites an error; without it,
unavailable native tests are reported as skipped. CI requires these tests.

## One-time signing setup

Run from a terminal:

```bash
scripts/prepare-obtainium-release.sh --setup
```

Enter the keystore path and its password. Setup finds private-key aliases matching
the tracked certificate pin. It selects a sole match automatically or asks you to
choose among multiple matches. It tries the keystore password for the private key
first and requests a separate key password only if needed. Both passwords are
validated before saving any settings.

Setup saves only the keystore path and alias to the Git-ignored
`signing/release.local.json`. It never changes the keystore or existing properties
files. Rerun `--setup` to change the local selection. Setup needs the pinned local
toolchain but does not access GitHub, build, sign, or require a release tag.

The public fingerprint in [release-certificate.sha256](../scripts/release-certificate.sha256)
is the independent signing identity for this project. It was verified against the
[v1.3.0 release APK](https://github.com/jnesew/ComicReader/releases/tag/v1.3.0)
(APK SHA-256 `961b49dd32e0e27a6e1cd287ec59e082eaff8be2dc48389eed6cd4a5caa5ad23`)
and the five earlier published APKs for the current application ID. Public preparation
automatically compares the latest published APK, selected signing key, and finished
APK against this pin. A mismatch stops preparation. Changing the pin requires a
reviewed source change; setup never learns or replaces it from the selected keystore.

## Password entry and existing configuration

The normal release command and public `--check` use hidden terminal password entry.
Usually only the keystore password is needed. A separate private-key password is
requested only when reuse fails. The JDK 17 helper `SigningKeyProbe.java` validates
the store, private key, and certificate before the lengthy build. It uses the JDK's
keystore APIs and never exports the private key.

Passwords are held for the current invocation and sent to the inspector and signer
through standard input. They are excluded from child-process environments,
command-line arguments, local settings, and release files. Each invocation requests
them again. Empty input, EOF, Ctrl+C, incorrect credentials, or inability to disable
echo stops preparation. Version bumping and unsigned development verification do
not request passwords.

Existing nonsecret configuration remains supported, with this precedence:

1. `COMICVIEWER_KEYSTORE_PATH` and `COMICVIEWER_KEY_ALIAS` environment variables.
2. A properties file explicitly selected by `COMICVIEWER_SIGNING_PROPERTIES`.
3. The settings saved by `--setup` in `signing/release.local.json`.
4. The existing ignored `signing/keystore.properties` file.

Relative keystore paths resolve from the repository root. Properties files use
Java `.properties` syntax, including escapes and continuations. Password fields in
properties are ignored by this preparation script; it does not remove old entries.
Interactive mode also ignores environment passwords.

`COMICVIEWER_EXPECTED_CERT_SHA256` is no longer needed. If present for compatibility,
it must match the tracked pin and cannot override it.

For explicit unattended operation, add `--non-interactive` and supply
`COMICVIEWER_STORE_PASSWORD` through the environment. Optionally supply
`COMICVIEWER_KEY_PASSWORD`; if absent or empty, the keystore password is tried.
If the key needs a different password, this mode fails before building rather
than prompting. It also supports `--setup --non-interactive`, using the path and
alias from the configuration above. Ambiguous alias discovery requires an explicit
`COMICVIEWER_KEY_ALIAS`.
For example, when a trusted local secret provider supplies these variables:

```bash
scripts/prepare-obtainium-release.sh --non-interactive
```

This mode never falls back to prompting or passwords in a properties file.
Environment passwords must be single-line values. They are passed to the signer
through standard input and excluded from the environments of build/GitHub/SDK
subprocesses.

## Choose the version explicitly

Use patch releases for backward-compatible fixes and maintenance, minor releases
for backward-compatible features or substantial improvements, and major releases
for deliberate breaking changes, such as dropping supported Android versions or
requiring a library reset. A data-preserving database migration alone does not
require a major release. An explicitly chosen release target takes precedence.

Choose once for the complete release cycle on its staging branch. Feature and
bugfix branches targeting that release inherit its version; merging each branch
does not require another bump. Every newly distributed APK still needs a higher
Android version code than previously distributed APKs.

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

After setup and the source/tag steps above, the usual release flow is one command:

```bash
scripts/prepare-obtainium-release.sh
```

This includes preflight, so there is no need to run it separately. For diagnostics,
`scripts/prepare-obtainium-release.sh --check` performs preflight without building,
signing, or staging. It is a separate invocation and requests passwords again.
The full command validates credentials once, then reuses them for signing later
in the same invocation.

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

Workflow tests use temporary Git repositories/remotes and fake SDK, Gradle,
signing, and GitHub commands. Pseudo-terminal tests check setup, hidden password
entry, automatic reuse, separate-password retries, and cancellation. They also
exercise certificate mismatches, release-policy failures, staging, and reruns.

With JDK 17 selected by `JAVA_HOME`, native inspector tests create disposable
PKCS12/JKS stores and test real unlocking, Unicode passwords, alias lookup, and
certificate-only entries. They skip explicitly when JDK 17 is unavailable.
Neither suite uses release credentials or publishes anything. They do not establish
that the full SDK signing/build workflow or device upgrades pass; test those with
the actual toolchain as well.
