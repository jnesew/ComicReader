#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
builder="gradle"
if [[ "${1:-}" == "--local-builder" ]]; then
    builder="local"
elif [[ -n "${1:-}" ]]; then
    echo "Usage: $0 [--local-builder]" >&2
    exit 2
fi

cd "$project_dir"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo "Commit or remove source-tree changes before reproducibility verification." >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/comicviewer-reproducible.XXXXXX")"
cleanup() {
    find "$work_dir" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$work_dir/one" "$work_dir/two"
git archive --format=tar HEAD | tar -xf - -C "$work_dir/one"
git archive --format=tar HEAD | tar -xf - -C "$work_dir/two"

version_name="$(sed -n 's/^comicViewerVersionName=//p' gradle.properties | tail -n 1)"
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
export TZ=UTC
export LC_ALL=C.UTF-8
unset COMICVIEWER_SIGNING_PROPERTIES COMICVIEWER_KEYSTORE_PATH
unset COMICVIEWER_STORE_PASSWORD COMICVIEWER_KEY_ALIAS COMICVIEWER_KEY_PASSWORD

build_one() {
    local checkout="$1"
    local output="$2"
    if [[ "$builder" == "gradle" ]]; then
        (
            cd "$checkout"
            ./gradlew --no-daemon --offline --dependency-verification strict clean assembleRelease
        )
        local apk="$checkout/app/build/outputs/apk/release/app-release-unsigned.apk"
        [[ -f "$apk" ]] || {
            echo "The isolated Gradle build did not produce an unsigned release APK." >&2
            exit 1
        }
        cp "$apk" "$output"
    else
        (
            cd "$checkout"
            ./build-local.sh --unsigned
        )
        cp "$checkout/dist/ComicViewer-Android-v${version_name}-unsigned.apk" "$output"
    fi
}

build_one "$work_dir/one" "$work_dir/one.apk"
build_one "$work_dir/two" "$work_dir/two.apk"

first_sha="$(sha256sum "$work_dir/one.apk" | cut -d' ' -f1)"
second_sha="$(sha256sum "$work_dir/two.apk" | cut -d' ' -f1)"
if [[ "$first_sha" != "$second_sha" ]] || ! cmp -s "$work_dir/one.apk" "$work_dir/two.apk"; then
    echo "Unsigned builds are not byte-for-byte reproducible:" >&2
    echo "  first:  $first_sha" >&2
    echo "  second: $second_sha" >&2
    exit 1
fi

echo "Reproducible unsigned build: $first_sha"
