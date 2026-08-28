#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

fail() {
    echo "Source security check failed: $*" >&2
    exit 1
}

expected_wrapper_sha="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
actual_wrapper_sha="$(sha256sum gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)"
[[ "$actual_wrapper_sha" == "$expected_wrapper_sha" ]] || fail "unexpected Gradle wrapper JAR"

grep -Fxq \
    'distributionSha256Sum=f1771298a70f6db5a29daf62378c4e18a17fc33c9ba6b14362e0cdf40610380d' \
    gradle/wrapper/gradle-wrapper.properties || fail "Gradle distribution checksum is missing"
grep -Fxq 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.4-bin.zip' \
    gradle/wrapper/gradle-wrapper.properties || fail "Gradle distribution URL is not pinned"
grep -Fxq 'validateDistributionUrl=true' gradle/wrapper/gradle-wrapper.properties ||
    fail "Gradle distribution URL validation is disabled"
[[ -s gradle/verification-metadata.xml ]] || fail "dependency verification metadata is missing"

tracked_sensitive="$(git ls-files | grep -E \
    '(^|/)(keystore\.properties|.*\.(jks|keystore|p12|pfx|pem|key))$' || true)"
if [[ -n "$tracked_sensitive" ]]; then
    fail "tracked signing/private-key material: $tracked_sensitive"
fi

tracked_packages="$(git ls-files | grep -E '\.(apk|aab|apks)$' || true)"
if [[ -n "$tracked_packages" ]]; then
    fail "tracked Android package artifact: $tracked_packages"
fi

if git grep -I -n -E \
        -- '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}' \
        -- . ':!third_party/**'; then
    fail "a private-key or credential pattern is present"
fi

password_matches="$(git grep -I -n -E -- \
    '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*[^[:space:]]+' \
    -- . ':!signing/keystore.properties.example' || true)"
unsafe_passwords="$(printf '%s\n' "$password_matches" | \
    grep -Ev '=(replace-me|change-me)$' || true)"
if [[ -n "$unsafe_passwords" ]]; then
    printf '%s\n' "$unsafe_passwords" >&2
    fail "a signing password appears outside an inert placeholder"
fi

if grep -R -n '<uses-permission' app/src/main; then
    fail "the source manifest requests an Android permission"
fi

if grep -R -n -E 'android\.permission\.|<uses-library' app/src/main; then
    fail "an Android permission or runtime library reference is present"
fi

echo "Source security checks passed"
