# F-Droid release preparation

Status: preparation in progress. No F-Droid submission or new release has been made.
The application ID remains `io.github.jnesew.comicviewer`, schema version remains 8,
and the source is still versionName 1.1.0/versionCode 3 until a release is selected.

## Prepared

- English and Finnish fastlane listing text, existing icon, selected reader/editor captures.
- Recipe review template in `fdroid/io.github.jnesew.comicviewer.yml.in` and two release-note drafts.
- Canonical unsigned Gradle candidate command: `./build-release.sh`.
- Reproducibility now compares two independent clean Git checkouts of the same commit,
  including Git metadata, rather than two `git archive` exports.
- Signature-aware verifier: `scripts/verify-release-apk.sh`.
- Platform-only Android SQLite test harness, with no new runtime or test library dependencies.

## Gates still required

1. Run the actual Android database harness before extracting database delegates:
   `./gradlew --dependency-verification strict connectedDebugAndroidTest`.
   It reports seven production-helper tests using a separate fixture database: metadata,
   index rollback, relinking, duplicate merge, missing-source policy, atomic forgetting,
   and schema 1 to 8 upgrade. The existing Python SQL script is supplementary.
2. Finish R5 delegates after that baseline passes; run the identical harness afterward.
3. Run unit tests, release lint/build, clean-checkout reproducibility and device regression
   checks. The local direct SDK build is compilation evidence, not the canonical release.
4. Complete the remaining screenshot-source checks in STORE-ASSETS.md and inspect the
   pinned AGP 8.13.2 POM/NOTICE in the build environment.
5. Choose the release version/code above all previously distributed versions, finalize
   the release-note drafts and copy them to both locale `changelogs/<code>.txt` files.
6. Verify the existing signing certificate from an actual installed/published APK. The
   prior release notes report `2b1a3a161ca63ffc15718f5be8897597dc33d3fce663634e219cef8950b8c80f`;
   do not populate the recipe from this report alone.
7. Fill the recipe placeholders, confirm the category and JDK/SDK setup in current
   fdroiddata, and run its metadata lint/update checks and source build. No scanner
   exclusions or dependency-verification relaxations are proposed.
8. Compare that rebuild to the actual signed candidate, test upgrading v1.1.0 with
   retained data/grants, then publish and submit when authorized.

## Canonical release and signing

Use the pinned JDK 17, Gradle 8.14.4, AGP 8.13.2, SDK Platform 36 and Build Tools 35.0.1.
Record the exact JDK distribution/patch and SDK platform revision with the candidate.
`build-local.sh` is a developer convenience; its D8 output differs from the optimized
Gradle release and must not be substituted for the APK in this recipe.

From the clean final release commit, run:

```bash
./build-release.sh
scripts/verify-reproducible-build.sh
```

The first command explicitly disables Gradle signing even if a local signing configuration
exists. Keep the generated unsigned APK. Sign it outside Gradle with the existing key;
provide passwords through environment references (or apksigner's interactive prompts),
never literal command arguments. Example after setting the named paths/credentials:

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

`--alignment-preserved` is supported by the installed 35.0.1 signer and addresses its
ZIP realignment change. It still requires verification against the real candidate.
No private signing key is shared with F-Droid. The upstream-binary route preserves
update compatibility by accepting only a matching rebuild and approved certificate.
See [F-Droid reproducibility](https://f-droid.org/en/docs/Reproducible_Builds/)
and the [signer alignment issue](https://github.com/obfusk/apksigcopier/issues/105).

The verifier uses the external build-time [apksigcopier command](https://github.com/obfusk/apksigcopier).
It is not an app dependency. No actual signed candidate comparison has run in this workspace.

Use the [submission guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
for the final fdroiddata review. The `.yml.in` file deliberately has unresolved values;
it is not a ready submission. Passing repository CI does not establish F-Droid acceptance.
