#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
    echo "Usage: $0 SIGNED_APK REBUILT_UNSIGNED_APK EXPECTED_CERT_SHA256" >&2
    exit 2
fi
signed="$1"
unsigned="$2"
expected="${3,,}"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Expected a verified certificate SHA-256.' >&2; exit 2; }
tools_dir="${ANDROID_HOME:?Set ANDROID_HOME}/build-tools/35.0.1"
certs="$("$tools_dir/apksigner" verify --print-certs "$signed")"
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs")"
[[ "${actual,,}" == "$expected" ]] || { echo 'Signing certificate mismatch.' >&2; exit 1; }
"$tools_dir/zipalign" -c -P 16 4 "$signed"
export PATH="$tools_dir:$PATH"
apksigcopier compare --unsigned "$signed" "$unsigned"
echo 'Signing certificate and signature-aware rebuild comparison passed.'
