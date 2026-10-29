#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
if [[ $# -ne 0 ]]; then echo 'Usage: scripts/build-reader-test.sh' >&2; exit 2; fi
source_revision=$(git rev-parse --short=12 HEAD)
if [[ -n $(git status --porcelain) ]]; then source_revision+="-dirty"; fi
./gradlew --dependency-verification strict -PcomicViewerUnsignedRelease=true assembleReaderTest
version=$(sed -n 's/^comicViewerVersionName=//p' gradle.properties)
mkdir -p dist
destination="dist/ComicViewer-ReaderTest-v${version}-${source_revision}-optimized.apk"
cp app/build/outputs/apk/readerTest/app-readerTest.apk "$destination"
sha256sum "$destination" | tee "${destination}.sha256"
