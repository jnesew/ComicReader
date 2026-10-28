#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
builder="gradle"
output_path=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --local-builder) builder="local"; shift ;;
        --output)
            [[ $# -ge 2 && -n "$2" ]] || { echo '--output requires a path.' >&2; exit 2; }
            output_path="$2"
            shift 2
            ;;
        *) echo "Usage: $0 [--local-builder] [--output VERIFIED_UNSIGNED_APK]" >&2; exit 2 ;;
    esac
done

cd "$project_dir"
if [[ -n "$output_path" && -e "$output_path" ]]; then
    echo 'Refusing to overwrite the reproducibility output.' >&2
    exit 1
fi
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo "Commit or remove source-tree changes before reproducibility verification." >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/comicviewer-reproducible.XXXXXX")"
cleanup() {
    find "$work_dir" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

commit="$(git rev-parse HEAD)"
origin_url="$(git remote get-url origin)"
for checkout in "$work_dir/one" "$work_dir/two"; do
    git clone --quiet --no-local --no-checkout "$project_dir" "$checkout"
    git -C "$checkout" checkout --quiet --detach "$commit"
    git -C "$checkout" remote set-url origin "$origin_url"
    test -z "$(git -C "$checkout" status --porcelain)"
done

version_name="$(sed -n 's/^comicViewerVersionName=//p' gradle.properties | tail -n 1)"
artifact_prefix="$(sed -n 's/^comicViewerArtifactPrefix=//p' gradle.properties | tail -n 1 | tr -d '\r')"
artifact_prefix="${artifact_prefix:-ComicViewer-Android}"
[[ "$artifact_prefix" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo 'Invalid comicViewerArtifactPrefix.' >&2; exit 1; }
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
            ./gradlew --no-daemon --offline --dependency-verification strict -PcomicViewerUnsignedRelease=true clean assembleRelease
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
        cp "$checkout/dist/${artifact_prefix}-v${version_name}-unsigned.apk" "$output"
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
if [[ -n "$output_path" ]]; then
    # Use noclobber so a competing writer cannot replace an existing result.
    (set -o noclobber; cat "$work_dir/one.apk" > "$output_path")
fi
