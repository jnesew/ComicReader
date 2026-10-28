# Forking and publishing

The project can be built from a fresh clone without the maintainer's local files
or signing key. [BUILDING.md](BUILDING.md) is the development and verification
procedure; [RELEASING.md](RELEASING.md) covers repeat releases. This guide covers
application identity, branding, and the first independent publication.

## Choose your distribution identity

Edit these public settings in `gradle.properties`:

| Setting | Purpose |
| --- | --- |
| `comicViewerApplicationId` | Unique installed Android identity, for example `org.example.myreader` |
| `comicViewerArtifactPrefix` | Safe filename prefix, for example `MyReader-Android` |
| `comicViewerReleaseName` | Release-note heading, for example `MyReader` |
| `comicViewerReleaseBranch` | Branch whose published history must contain release commits |
| `comicViewerRetiredApplicationIds` | Comma-separated, explicitly retired IDs; normally empty for a new fork |
| `comicViewerVersionName`, `comicViewerVersionCode` | Your release version and monotonically increasing Android code |

Keep the `comicViewer` property and environment-variable prefixes when renaming
the product; they are build-tool interfaces, not user-visible branding. Configure
`origin` to your own GitHub repository. The release helper reads its owner/name
from that remote; it currently supports GitHub.com repositories.

Use a new application ID and your own signing certificate for an independently
published app. This permits side-by-side installation and separates its private
storage and update channel. It does not migrate the upstream app's library state
or document grants. Sharing an ID does not grant the ability to issue compatible
updates: those also require the original signing identity.

The Java/resource namespace can remain `io.github.jnesew.comicviewer`. Changing
`comicViewerApplicationId` updates both Gradle and direct SDK packaging without
renaming Java packages. The test provider's authority and grants follow the
configured application ID. A namespace refactor is optional and requires updating
source packages, imports, manifests, runner names, source-checking scripts, and
the direct-build namespace.

For a new ID, you can start your own version/code sequence. Choose a version tag
that is unused in your repository; inherited upstream tags are not release tags
for your new app. Existing installations of your app must always receive a higher
version code for changed APK contents.

## Update the public-facing project

Review at least:

- `app/src/main/res/values/strings.xml` and `values-fi/strings.xml`: app name and
  text, with equivalent updates for any other locales you add.
- `artwork/` and Android launcher resources: your identity and icon.
- README and `docs/INSTALLING.md`: repository links and the Obtainium badge payload
  (application ID, URL, author, and display name).
- `PRIVACY.md`, `SECURITY.md`, and `.github/FUNDING.yml`: maintainer/contact,
  support, reporting, funding, and statements that match your actual behavior.
- `fastlane/metadata/android/`, screenshots, and `docs/STORE-ASSETS.md`: listing
  text, images, and accurate credits for the assets you retain or replace.
- `LICENSE`, `THIRD_PARTY_NOTICES.md`, and `third_party/`: retain applicable
  copyright, license, and attribution notices.
- `AGENTS.md` and build/security checks if your fork deliberately changes product
  boundaries. The inherited checks enforce this project's permission and runtime
  dependency policy; do not describe a changed app as having the old guarantees.

Developer documentation uses configurable identity where practical. User-facing
installation links still describe the published upstream app until you change them.

## Establish your signing identity

Generate a key you control, or use an existing independently trusted key. For example,
these commands prompt for passwords rather than placing them in command arguments:

```bash
mkdir -p signing
keytool -genkeypair -storetype PKCS12 -keystore signing/release.p12 \
  -alias release -keyalg RSA -keysize 4096 -validity 10000
keytool -exportcert -keystore signing/release.p12 -alias release \
  -file signing/release-certificate.der
sha256sum signing/release-certificate.der | cut -d' ' -f1 > scripts/release-certificate.sha256
```

The `signing/` directory is Git-ignored. Back up the private key and credentials
outside the repository; losing them can prevent compatible updates. Commit only
the public fingerprint in `scripts/release-certificate.sha256`. Verify it against
the certificate exported from your selected key. Do not inherit the upstream pin
or learn a new pin automatically from an untrusted downloaded APK.

Now run `scripts/prepare-obtainium-release.sh --setup` to validate the key against
that pin and save only the local path/alias. Setup works before your first public
release and does not upload anything.

## Publish the first release

The normal release helper requires an existing published APK for an independent
version/certificate baseline. For a new repository, bootstrap that first release
with the same build and signature checks manually. Afterward, use the normal helper.

1. Set the identity and version properties, review `CHANGELOG.md`, and create
   `docs/RELEASE-NOTES-<version>.md` headed `# <comicViewerReleaseName> <version>`.
   Add the English and Finnish `fastlane/metadata/android/<locale>/changelogs/<code>.txt`
   files required by the helper, each at most 500 characters. Commit the reviewed
   source, including your public certificate pin.
2. Push the commit to your configured release branch and create/push its matching
   `v<versionName>` tag. Check that the local and remote tags identify that exact
   commit. Never move an already published tag to different contents.
3. Run the [connected Android suite](BUILDING.md#android-instrumentation-tests) and
   manual device checks appropriate to your changes. The commands below complete
   the host/release verification gate.
4. Build, reproduce, sign, and compare. Replace the example identity/version below
   with the values you committed; both output files must be new. Export
   `ANDROID_HOME` and `JAVA_HOME` as described in the build guide for these SDK commands:

```bash
mkdir -p dist
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./build-release.sh --output dist/candidate-unsigned.apk
scripts/verify-reproducible-build.sh --output dist/rebuilt-unsigned.apk
cmp dist/candidate-unsigned.apk dist/rebuilt-unsigned.apk

SIGNED_APK=dist/MyReader-Android-v1.0.0.apk
"$ANDROID_HOME/build-tools/35.0.1/apksigner" sign \
  --alignment-preserved true --v4-signing-enabled false \
  --ks signing/release.p12 --ks-key-alias release \
  --out "$SIGNED_APK" dist/candidate-unsigned.apk
CERT_SHA256="$(cat scripts/release-certificate.sha256)"
scripts/verify-release-apk.sh "$SIGNED_APK" dist/rebuilt-unsigned.apk "$CERT_SHA256"
sha256sum "$SIGNED_APK" > "$SIGNED_APK.sha256"
```

The signer prompts for passwords. The verifier needs the host Python packages
listed in [release requirements](RELEASING.md#requirements). Confirm package,
version, permissions, and device behavior before publication. The canonical build
must also match the independently rebuilt APK; the final signature comparison
checks that match cryptographically.

5. Create the release in your own repository, attaching the signed APK and its
   checksum. For the example above, after the source/tag checks and device testing:

```bash
gh release create v1.0.0 dist/MyReader-Android-v1.0.0.apk \
  dist/MyReader-Android-v1.0.0.apk.sha256 \
  --repo YOUR_ACCOUNT/YOUR_REPOSITORY --verify-tag \
  --title 'v1.0.0' --notes-file docs/RELEASE-NOTES-1.0.0.md
```

GitHub supplies the asset digest that subsequent preparations verify. Confirm the
published APK is the one you tested, then point Obtainium and installation docs to
that repository. Future preparations compare version/code and signing identity
against this release while also checking the tracked certificate pin.

For another hosting service, the signed APK is ordinary Android packaging, but the
GitHub-specific preparation helper will need adaptation.

## Store metadata

The `docs/fdroid/*.yml.in` file is a review template, not a submitted recipe. Copy it
under your application ID and update repository/asset URLs, app name, versions,
commit, and certificate. Validate it in the target distributor's build environment;
a local successful build does not establish store acceptance. Preserve the asset
licenses and provenance described in [STORE-ASSETS.md](STORE-ASSETS.md).
