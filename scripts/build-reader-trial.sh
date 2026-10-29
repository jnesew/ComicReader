#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
choice=${1:-bands}
case "$choice" in
    control) bands=1; code=$(sed -n 's/^comicViewerReaderControlVersionCode=//p' gradle.properties) ;;
    bands) bands=2; code=$(sed -n 's/^comicViewerReaderTestVersionCode=//p' gradle.properties) ;;
    *) echo 'Usage: scripts/build-reader-trial.sh [control|bands]' >&2; exit 2 ;;
esac
source_revision=$(git rev-parse --short=12 HEAD)
if [[ -n $(git status --porcelain) ]]; then source_revision+="-dirty"; fi
./gradlew --dependency-verification strict -PcomicViewerUnsignedRelease=true \
    "-PreaderTrialTallBands=$bands" "-PcomicViewerReaderTestVersionCode=$code" assembleReaderTrial
version=$(sed -n 's/^comicViewerVersionName=//p' gradle.properties)
mkdir -p dist
destination="dist/ComicViewer-ReaderTest-v${version}-${source_revision}-${choice}-optimized.apk"
cp app/build/outputs/apk/readerTrial/app-readerTrial.apk "$destination"
sha256sum "$destination" | tee "${destination}.sha256"
