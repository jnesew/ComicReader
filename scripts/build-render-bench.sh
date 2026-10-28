#!/usr/bin/env bash
set -euo pipefail
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"
python3 scripts/verify-render-bench-fixtures.py
source_revision=$(git rev-parse --short=12 HEAD)
if [[ -n $(git status --porcelain) ]]; then source_revision+="-dirty"; fi
./gradlew --dependency-verification strict -PcomicViewerUnsignedRelease=true \
    "-PrenderBenchSource=$source_revision" assembleRenderBench
version=$(sed -n 's/^comicViewerBenchVersionName=//p' gradle.properties)
mkdir -p dist
destination="dist/ComicViewer-RenderBench-v${version}-${source_revision}.apk"
cp app/build/outputs/apk/renderBench/app-renderBench.apk "$destination"
sha256sum "$destination"
