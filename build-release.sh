#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo 'Commit source changes before building a release candidate.' >&2
    exit 1
fi
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
export TZ=UTC LC_ALL=C.UTF-8
./gradlew --no-daemon --dependency-verification strict \
    -PcomicViewerUnsignedRelease=true clean testDebugUnitTest lintRelease assembleRelease
version_name="$(sed -n 's/^comicViewerVersionName=//p' gradle.properties | tail -n 1)"
mkdir -p dist
output="dist/ComicViewer-Android-v${version_name}-unsigned.apk"
cp app/build/outputs/apk/release/app-release-unsigned.apk "$output"
sha256sum "$output"
echo 'Unsigned Gradle release candidate. Follow docs/FDROID-READINESS.md for signing and verification.'
