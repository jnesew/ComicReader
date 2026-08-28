# Contributing to ComicViewer

ComicViewer welcomes focused bug reports and contributions that preserve its purpose as a small,
offline comic reader.

## Project constraints

Changes should preserve these boundaries unless a proposal is discussed first:

- no network permission, analytics, advertising, accounts, subscriptions, or cloud dependency;
- no broad storage or media permission;
- no Google Play Services, Firebase, proprietary runtime dependency, or unnecessary production
  library;
- bounded memory and hostile-input handling for untrusted comics and document providers;
- Android API 26 through 36 compatibility;
- readable, dependency-light platform Java rather than framework expansion without a clear benefit.

CBR/RAR, CB7/7z, general reflowable EPUB reading, and cross-device synchronization are currently
outside the project's scope.

## Before opening an issue

Search existing issues first. For a bug, include the ComicViewer and Android versions, comic format,
expected behavior, observed behavior, and minimal reproduction steps. Do not upload private,
copyrighted, or sensitive comics. Use [private vulnerability reporting](SECURITY.md) for security
issues.

## Development setup

See [docs/BUILDING.md](docs/BUILDING.md). Before submitting a change, run:

```bash
scripts/verify-source-security.sh
scripts/verify-database-roundtrip.sh
./gradlew --dependency-verification strict testDebugUnitTest lintRelease assembleRelease
scripts/verify-reproducible-build.sh
```

Add or update focused tests for behavior changes. Keep user-visible strings in resources and update
both English and Finnish resources when practical.

## Pull requests

Keep pull requests narrow, describe user-visible behavior and security implications, and disclose
any new dependency, permission, build-tool change, or generated asset. Confirm the checks you ran.
By contributing, you agree that your contribution is licensed under the repository's MIT License.
