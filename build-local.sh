#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
signing_mode="signed"
export TZ=UTC

case "${1:-}" in
    "") ;;
    --unsigned) signing_mode="unsigned" ;;
    *)
        echo "Usage: $0 [--unsigned]" >&2
        exit 2
        ;;
esac

if [[ -z "$sdk_root" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK." >&2
    exit 1
fi

build_tools="$sdk_root/build-tools/35.0.1"
android_jar="$sdk_root/platforms/android-36/android.jar"
if [[ ! -x "$build_tools/aapt2" || ! -f "$android_jar" ]]; then
    echo "Install Android SDK Platform 36 and Build Tools 35.0.1." >&2
    exit 1
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
if ! command -v "$java_bin" >/dev/null 2>&1; then
    echo "JDK 17 is required (set JAVA_HOME if javac is not on PATH)." >&2
    exit 1
fi

property_value() {
    local key="$1"
    local file="$2"
    sed -n "s/^${key}=//p" "$file" | tail -n 1 | tr -d '\r'
}

version_properties="$project_dir/gradle.properties"
version_code="$(property_value comicViewerVersionCode "$version_properties")"
version_name="$(property_value comicViewerVersionName "$version_properties")"
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
    echo "gradle.properties has an invalid comicViewerVersionCode." >&2
    exit 1
fi
if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    echo "gradle.properties has an invalid comicViewerVersionName." >&2
    exit 1
fi

build_dir="$project_dir/build/local-cli"
generated_dir="$build_dir/generated"
classes_dir="$build_dir/classes"
dex_dir="$build_dir/dex"
dist_dir="$project_dir/dist"
mkdir -p "$generated_dir" "$classes_dir" "$dex_dir" "$dist_dir"
find "$generated_dir" "$classes_dir" "$dex_dir" -mindepth 1 -delete

"$build_tools/aapt2" compile \
    --dir "$project_dir/app/src/main/res" \
    -o "$build_dir/resources.zip"

"$build_tools/aapt2" link \
    -I "$android_jar" \
    --manifest "$project_dir/app/src/main/AndroidManifest.xml" \
    --java "$generated_dir" \
    --min-sdk-version 26 \
    --target-sdk-version 36 \
    --version-code "$version_code" \
    --version-name "$version_name" \
    --auto-add-overlay \
    -o "$build_dir/base-unsigned.apk" \
    "$build_dir/resources.zip"

mapfile -t java_sources < <(find \
    "$project_dir/app/src/main/java" "$generated_dir" \
    -name '*.java' -print)
"$java_bin" \
    -encoding UTF-8 \
    -source 17 \
    -target 17 \
    -classpath "$android_jar" \
    -d "$classes_dir" \
    "${java_sources[@]}"

mapfile -t class_files < <(find "$classes_dir" -name '*.class' -print)
"$build_tools/d8" \
    --lib "$android_jar" \
    --min-api 26 \
    --output "$dex_dir" \
    "${class_files[@]}"

cp "$build_dir/base-unsigned.apk" "$build_dir/with-dex.apk"
build_epoch="${SOURCE_DATE_EPOCH:-315532800}"
if [[ ! "$build_epoch" =~ ^[0-9]+$ ]]; then
    echo "SOURCE_DATE_EPOCH must be a non-negative integer." >&2
    exit 1
fi
touch -d "@$build_epoch" "$dex_dir/classes.dex"
zip -q -X -j "$build_dir/with-dex.apk" "$dex_dir/classes.dex"
"$build_tools/zipalign" -f -p 4 \
    "$build_dir/with-dex.apk" "$build_dir/aligned.apk"

if [[ "$signing_mode" == "unsigned" ]]; then
    apk="$dist_dir/ComicViewer-Android-v${version_name}-unsigned.apk"
    cp "$build_dir/aligned.apk" "$apk"
    "$build_tools/zipalign" -c -p 4 "$apk"
    echo "Built unsigned $apk"
    exit 0
fi

properties="${COMICVIEWER_SIGNING_PROPERTIES:-$project_dir/signing/keystore.properties}"
if [[ ! -f "$properties" ]]; then
    echo "Release signing is not configured. Set COMICVIEWER_SIGNING_PROPERTIES" >&2
    echo "or create signing/keystore.properties; use --unsigned for source verification." >&2
    exit 1
fi

store_file="${COMICVIEWER_KEYSTORE_PATH:-$(property_value storeFile "$properties")}"
store_password="${COMICVIEWER_STORE_PASSWORD:-$(property_value storePassword "$properties")}"
key_alias="${COMICVIEWER_KEY_ALIAS:-$(property_value keyAlias "$properties")}"
key_password="${COMICVIEWER_KEY_PASSWORD:-$(property_value keyPassword "$properties")}"

if [[ -z "$store_file" && -f "$project_dir/signing/comicviewer-local-release.jks" ]]; then
    store_file="signing/comicviewer-local-release.jks"
fi
if [[ "$store_file" == /* ]]; then
    keystore="$store_file"
else
    keystore="$project_dir/$store_file"
fi
if [[ ! -f "$keystore" || -z "$store_password" || -z "$key_alias" || -z "$key_password" ]]; then
    echo "Release signing is incomplete; check the configured keystore and four signing values." >&2
    exit 1
fi

apk="$dist_dir/ComicViewer-Android-v${version_name}.apk"
export COMICVIEWER_APKSIGNER_STORE_PASSWORD="$store_password"
export COMICVIEWER_APKSIGNER_KEY_PASSWORD="$key_password"
trap 'unset COMICVIEWER_APKSIGNER_STORE_PASSWORD COMICVIEWER_APKSIGNER_KEY_PASSWORD' EXIT

JAVA_HOME="${JAVA_HOME:-}" "$build_tools/apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias "$key_alias" \
    --ks-pass env:COMICVIEWER_APKSIGNER_STORE_PASSWORD \
    --key-pass env:COMICVIEWER_APKSIGNER_KEY_PASSWORD \
    --out "$apk" \
    "$build_dir/aligned.apk"

JAVA_HOME="${JAVA_HOME:-}" "$build_tools/apksigner" verify --verbose "$apk"
echo "Built $apk"
