#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
builder="gradle"
allow_untagged=false

for argument in "$@"; do
    case "$argument" in
        --allow-untagged) allow_untagged=true ;;
        --local-builder) builder="local" ;;
        *)
            echo "Usage: $0 [--allow-untagged] [--local-builder]" >&2
            exit 2
            ;;
    esac
done

if [[ -z "$sdk_root" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK." >&2
    exit 1
fi

build_tools="$sdk_root/build-tools/35.0.1"
for tool in aapt2 apksigner zipalign; do
    if [[ ! -x "$build_tools/$tool" ]]; then
        echo "Android SDK Build Tools 35.0.1 are required." >&2
        exit 1
    fi
done

property_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$project_dir/gradle.properties" | tail -n 1 | tr -d '\r'
}

version_code="$(property_value comicViewerVersionCode)"
version_name="$(property_value comicViewerVersionName)"
release_tag="v${version_name}"
asset_name="ComicViewer-Android-v${version_name}.apk"

if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ||
      ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    echo "Invalid release version in gradle.properties." >&2
    exit 1
fi

cd "$project_dir"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    echo "Commit or remove source-tree changes before staging a release." >&2
    exit 1
fi

scripts/verify-source-security.sh

if ! git tag --points-at HEAD | grep -Fxq "$release_tag"; then
    if [[ "$allow_untagged" != true ]]; then
        echo "HEAD must carry the exact release tag $release_tag." >&2
        echo "Use --allow-untagged only for a non-public verification run." >&2
        exit 1
    fi
    echo "Warning: verifying without release tag $release_tag; do not publish this run." >&2
fi

if [[ "$builder" == "gradle" ]]; then
    ./gradlew --dependency-verification strict clean testDebugUnitTest assembleRelease
    source_apk="$project_dir/app/build/outputs/apk/release/app-release.apk"
    if [[ ! -f "$source_apk" ]]; then
        echo "No signed Gradle release APK was produced. Configure release signing first." >&2
        exit 1
    fi
else
    "$project_dir/build-local.sh"
    source_apk="$project_dir/dist/$asset_name"
fi

"$build_tools/apksigner" verify --verbose --print-certs "$source_apk" > \
    "$project_dir/build/obtainium-signature.txt"
"$build_tools/zipalign" -c -p 4 "$source_apk"

badging="$("$build_tools/aapt2" dump badging "$source_apk")"
expected_package="package: name='com.localtools.comicviewer' versionCode='${version_code}' versionName='${version_name}'"
if ! grep -Fq "$expected_package" <<<"$badging"; then
    echo "APK package/version does not match gradle.properties." >&2
    exit 1
fi

permissions="$("$build_tools/aapt2" dump permissions "$source_apk")"
if grep -q '^uses-permission:' <<<"$permissions"; then
    echo "Release APK unexpectedly requests Android permissions:" >&2
    grep '^uses-permission:' <<<"$permissions" >&2
    exit 1
fi

certificate_sha256="$(sed -n \
    's/^Signer #1 certificate SHA-256 digest: //p' \
    "$project_dir/build/obtainium-signature.txt" | head -n 1 | tr '[:upper:]' '[:lower:]' | tr -d ':')"
if [[ ! "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Could not read the APK signing-certificate SHA-256 digest." >&2
    exit 1
fi

if [[ -n "${COMICVIEWER_EXPECTED_CERT_SHA256:-}" ]]; then
    expected_certificate="$(printf '%s' "$COMICVIEWER_EXPECTED_CERT_SHA256" |
        tr '[:upper:]' '[:lower:]' | tr -d ':')"
    if [[ "$certificate_sha256" != "$expected_certificate" ]]; then
        echo "APK signing certificate does not match COMICVIEWER_EXPECTED_CERT_SHA256." >&2
        exit 1
    fi
fi

staging_dir="$project_dir/dist/obtainium-${release_tag}"
staging_apk="$staging_dir/$asset_name"
checksum_file="$staging_apk.sha256"
mkdir -p "$staging_dir"

shopt -s nullglob
for existing_apk in "$staging_dir"/*.apk; do
    if [[ "$existing_apk" != "$staging_apk" ]]; then
        echo "Unexpected APK already exists in $staging_dir; remove it manually." >&2
        exit 1
    fi
done
shopt -u nullglob

cp -f "$source_apk" "$staging_apk"
(
    cd "$staging_dir"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$asset_name" > "${asset_name}.sha256"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$asset_name" > "${asset_name}.sha256"
    else
        echo "Install sha256sum or shasum to create the release checksum." >&2
        exit 1
    fi
)

echo
echo "Obtainium release staging passed"
echo "  tag:         $release_tag"
echo "  package:     com.localtools.comicviewer"
echo "  versionCode: $version_code"
echo "  versionName: $version_name"
echo "  certificate: $certificate_sha256"
echo "  APK:         $staging_apk"
echo "  checksum:    $checksum_file"
echo
echo "After reviewing the files, a maintainer may publish them from their own PC with:"
printf '  gh release create %q %q %q --verify-tag --title %q --generate-notes\n' \
    "$release_tag" "$staging_apk" "$checksum_file" "$release_tag"
