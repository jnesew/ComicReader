#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
source_revision=$(git rev-parse --short=12 HEAD)
if [[ -n $(git status --porcelain) ]]; then source_revision+="-dirty"; fi
./gradlew --dependency-verification strict -PcomicViewerUnsignedRelease=true assembleReaderDebug
version=$(sed -n 's/^comicViewerVersionName=//p' gradle.properties)
mkdir -p dist
destination="dist/ComicViewer-ReaderTest-v${version}-${source_revision}-debug.apk"
cp app/build/outputs/apk/readerDebug/app-readerDebug.apk "$destination"
sha256sum "$destination"
