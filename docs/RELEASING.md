# Preparing a release

The tracked `scripts/prepare-obtainium-release.sh` verifies and stages a signed APK
for GitHub releases and Obtainium. It never commits, pushes, creates/moves tags,
or publishes. For development setup and checks, see [BUILDING.md](BUILDING.md).
For a new app or repository with no published APK, follow
[first publication](FORKING.md#publish-the-first-release) instead.

## Requirements

Use the toolchain in [BUILDING.md](BUILDING.md#toolchain-and-local-setup), plus:

- Python 3.11+, Bash, Git, and authenticated GitHub CLI (`gh`).
- `origin` identifying your GitHub.com repository over HTTPS or SSH.
- A populated Gradle dependency cache for the isolated offline rebuilds.
- The `apksigcopier` Python module importable by the same `python3` interpreter.

A system package providing that module works. Alternatively, use an external
virtual environment with the pinned, hash-verified host tools:

```bash
python3 -m venv "$HOME/.venvs/reader-release"
source "$HOME/.venvs/reader-release/bin/activate"
python3 -m pip install --require-hashes --only-binary=:all: -r scripts/release-tools.requirements.txt
python3 scripts/compare_apk_signatures.py --check
```

`gradle.properties` holds the public app ID, artifact prefix, release-note name,
release branch, retired IDs, version name, and version code. Defaults preserve
older checkouts; [FORKING.md](FORKING.md#choose-your-distribution-identity) describes
the settings. Signing credentials and local SDK settings remain untracked.

## Signing setup

The tracked public fingerprint in `scripts/release-certificate.sha256` is the
independent signing identity. Setup verifies your key against it; it never learns
or replaces the pin from a selected key or downloaded APK. A fork must establish
its own pin using [its trusted certificate](FORKING.md#establish-your-signing-identity).

From a terminal:

```bash
scripts/prepare-obtainium-release.sh --setup
```

Enter the keystore path and hidden password. Setup discovers matching private-key
aliases, selects a sole match or offers a choice, and reuses the store password
for the key if possible. It asks for a different key password only when needed.
The JDK inspector validates the private key before saving only the path and alias
to Git-ignored `signing/release.local.json`. It never exports the key. Rerun setup
to change that local selection. Setup does not build or contact GitHub.

Normal preparation prompts again for the password on each invocation. It keeps
passwords in memory, sends them to the inspector/signer via standard input, and
excludes them from child environments, command arguments, and staged files.
Empty input, EOF, Ctrl+C, failed unlocking, or unavailable hidden entry stops the run.

Nonsecret path/alias settings have this precedence:

1. `COMICVIEWER_KEYSTORE_PATH` and `COMICVIEWER_KEY_ALIAS` environment variables.
2. An explicit `COMICVIEWER_SIGNING_PROPERTIES` properties file.
3. Saved `signing/release.local.json` settings.
4. The legacy ignored `signing/keystore.properties` file.

Relative paths resolve from the repository root. Properties use Java escapes and
continuations. Stored password fields are ignored; interactive mode also ignores
environment passwords. The compatibility variable `COMICVIEWER_EXPECTED_CERT_SHA256`,
if present, must agree with the tracked pin and cannot override it.

For unattended operation, explicitly use `--non-interactive` with
`COMICVIEWER_STORE_PASSWORD` supplied by a trusted local secret provider. Optional
`COMICVIEWER_KEY_PASSWORD` selects a separate password; otherwise reuse is attempted.
Values must be single-line. This mode supports `--setup` too, never prompts or
falls back to stored passwords, and needs an explicit alias if discovery is ambiguous.
Upstream CI builds unsigned artifacts and does not receive release credentials.

## Version, notes, and source

Choose the version once for the complete release cycle:

| Bump | Use |
| --- | --- |
| Patch | Compatible fixes, cleanup, build tooling, or documentation |
| Minor | Compatible features or substantial functionality improvements |
| Major | Deliberate breaking changes, such as dropping Android support or requiring data resets |

A data-preserving schema migration alone is not a major change. Feature/bugfix
branches inherit their staging branch's version. Skip bumping if it is already set.
Every newly distributed APK with changed contents needs a higher Android code.

From a clean checkout, start a new cycle with, for example:

```bash
scripts/prepare-obtainium-release.sh --bump patch
```

The selected component increments from the higher local/published version base;
lower components reset. The code becomes one above the highest local/published
code for the configured app ID. The command updates `gradle.properties` and creates:

- a dated `CHANGELOG.md` section;
- `docs/RELEASE-NOTES-<version>.md`, headed `# <release name> <version>`;
- English/Finnish `fastlane/metadata/android/<locale>/changelogs/<code>.txt` files.

Replace generated TODO text, review the release date, and commit. Localized listing
changelogs must contain 1–500 characters. Existing notes are not overwritten.
Normal preparation never bumps the version automatically.

Push the intended source to the configured release branch (upstream uses `main`)
and create/push its exact `v<versionName>` tag. Public preparation requires a clean
checkout at that local and remote tag, with the commit reachable from the freshly
fetched release branch. It will not move tags or push source on your behalf.

Published releases, including prereleases, establish the version-code baseline;
drafts and local test tags do not. The helper reads package/version/certificate
from published APKs, caches them under ignored `dist/release-cache/`, and checks
GitHub's SHA-256 asset digest on each run. Live history/network failures stop the run.

Only explicitly configured retired IDs are excluded. A release missing an APK is
excluded only when its tagged source proves a retired ID. Foreign packages,
ambiguous APK assets, missing digests, or tag/APK disagreements require inspection.
Published prereleases count, while new release targets use stable `X.Y.Z` names.

## Prepare and review

After signing setup and the source/tag steps, run:

```bash
scripts/prepare-obtainium-release.sh
```

It checks credentials, source, published versions, notes, and the independent
certificate pin before building. For a preflight-only invocation use `--check`;
it prompts separately because passwords are not persisted between invocations.

The full run performs the [build verification gate](BUILDING.md#verification),
compares the canonical unsigned candidate with two isolated builds, inspects
package/version/permissions and debuggable status, signs outside Gradle, and checks
ZIP alignment, the pinned certificate, and signature-aware equality with the rebuild.
Source and live release-history checks run again before final staging.

The comparison helper preserves explicit UTF-8 ZIP filename flags on newer Python
versions. It still requires the original and reconstructed signatures to verify
with Build Tools 35.0.1; a content digest mismatch is a failure.

The bundle appears atomically under `dist/obtainium-v<version>/`:

| File | Purpose |
| --- | --- |
| `<artifact prefix>-v<version>.apk` | Verified signed APK |
| `<APK filename>.sha256` | Checksum usable with `sha256sum --check` |
| `release-notes.md` | Reviewed versioned release notes |
| `changelog-en-US.txt`, `changelog-fi-FI.txt` | Listing changelogs |
| `release.json` | Source, identity, toolchain, baseline, hashes, and actual check results |

Conflicting, incomplete, modified, or older-format bundles are not overwritten.
Identical reruns preserve them. Rebuilding a published version requires its exact
source/code and byte-identical signed APK; otherwise bump the version. The helper
prints no new-publication command for an already published exact rebuild.

A directory lock prevents concurrent preparation. Normal failures clean temporary
work and release the lock. After a forced kill or power loss, inspect running
processes before removing `dist/.release-preparation.lock` or `.release-*` leftovers.
Existing completed bundles and earlier unsigned candidates are preserved.

Run the [connected Android suite](BUILDING.md#android-instrumentation-tests) and
manual installation/upgrade/reader checks before publishing. Preparation does not
perform device tests and records them as `not_run` in the manifest. It then prints
a repository-specific `gh release create` command with exact assets/tag and
`--notes-file`; execute it only after reviewing the bundle and device evidence.
Signing and local builds do not establish distributor acceptance. F-Droid review
templates and fork metadata are described in [FORKING.md](FORKING.md#store-metadata).

## Verification before tagging

From a clean committed checkout:

```bash
scripts/prepare-obtainium-release.sh --check --development
scripts/prepare-obtainium-release.sh --development
```

Development mode runs the unsigned build/reproducibility checks without GitHub,
credentials, or `apksigcopier`. Its separate bundle at
`dist/development-v<version>-<commit>/` uses a `-development-unsigned.apk` suffix,
records `publishable: false`, and prints no publication command. An unsigned APK
cannot replace a signed installation. Use this for unmerged/untagged source.

## Release-tool tests

```bash
COMICVIEWER_REQUIRE_SIGNING_TESTS=1 python3 -m unittest discover -s scripts/tests -v
```

With the SDK, JDK 17, and host Python tools installed, this includes policy tests
with temporary Git remotes/fake external tools plus real disposable PKCS12/JKS
inspection and SDK signing/comparison tests. It covers hidden terminal entry,
certificate failures, fork identity/branch handling, version guards, cancellation,
reruns, and rejected corrupted/mismatched APKs. It does not use release credentials
or publish. Without the environment flag, missing native prerequisites cause skips;
CI requires them. These tests complement actual build and device evidence.

The upstream public certificate pin was verified against the
[v1.3.0 APK](https://github.com/jnesew/ComicReader/releases/tag/v1.3.0), SHA-256
`961b49dd32e0e27a6e1cd287ec59e082eaff8be2dc48389eed6cd4a5caa5ad23`, and five earlier
published APKs for the current upstream application ID. Retain independent
certificate evidence when adapting the release identity.
