#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
output=""
if [[ $# -gt 0 ]]; then
    if [[ $# -ne 2 || "$1" != "--output" || -z "$2" ]]; then
        echo "Usage: $0 [--output NEW_UNSIGNED_APK]" >&2
        exit 2
    fi
    output="$2"
    if [[ -e "$output" || ! -d "$(dirname "$output")" ]]; then
        echo 'Output must be a new file in an existing directory.' >&2
        exit 1
    fi
fi
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo 'Commit source changes before building a release candidate.' >&2
    exit 1
fi
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
export TZ=UTC LC_ALL=C.UTF-8
./gradlew --no-daemon --dependency-verification strict \
    -PcomicViewerUnsignedRelease=true clean verifyToolchainDependencies testDebugUnitTest lintRelease assembleRelease
version_name="$(sed -n 's/^comicViewerVersionName=//p' gradle.properties | tail -n 1)"
mkdir -p dist
if [[ -z "$output" ]]; then
    output="dist/ComicViewer-Android-v${version_name}-unsigned.apk"
    cp app/build/outputs/apk/release/app-release-unsigned.apk "$output"
else
    (set -o noclobber; cat app/build/outputs/apk/release/app-release-unsigned.apk > "$output")
fi
sha256sum "$output"
echo 'Unsigned Gradle release candidate. Follow docs/BUILDING.md for signing and verification.'
