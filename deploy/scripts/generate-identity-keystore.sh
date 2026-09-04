#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  echo "usage: DF_IAM_SIGNING_STORE_PASSWORD=... DF_IAM_SIGNING_KEY_PASSWORD=... $0 <absolute-output.p12>" >&2
  exit 64
}

output=${1:-}
[ -n "$output" ] || usage
case "$output" in
  /*.p12) ;;
  *) echo "output must be an absolute .p12 path" >&2; exit 65 ;;
esac
[ ! -e "$output" ] || { echo "refusing to overwrite $output" >&2; exit 66; }
[ -n "${DF_IAM_SIGNING_STORE_PASSWORD:-}" ] || { echo "missing DF_IAM_SIGNING_STORE_PASSWORD" >&2; exit 67; }
[ -n "${DF_IAM_SIGNING_KEY_PASSWORD:-}" ] || { echo "missing DF_IAM_SIGNING_KEY_PASSWORD" >&2; exit 68; }
[ "${#DF_IAM_SIGNING_STORE_PASSWORD}" -ge 16 ] || { echo "store password must be at least 16 characters" >&2; exit 69; }
[ "${#DF_IAM_SIGNING_KEY_PASSWORD}" -ge 16 ] || { echo "key password must be at least 16 characters" >&2; exit 70; }
[ "$DF_IAM_SIGNING_KEY_PASSWORD" = "$DF_IAM_SIGNING_STORE_PASSWORD" ] || {
  echo "this PKCS12 generator requires identical store and key passwords" >&2
  exit 71
}

mkdir -p -- "$(dirname -- "$output")"
keytool -genkeypair \
  -alias "${DF_IAM_SIGNING_KEY_ALIAS:-dataset-identity}" \
  -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 825 \
  -dname "CN=dataset-identity-signing" \
  -storetype PKCS12 -keystore "$output" \
  -storepass "$DF_IAM_SIGNING_STORE_PASSWORD" \
  -keypass "$DF_IAM_SIGNING_KEY_PASSWORD" \
  -noprompt
chmod 0600 "$output"
echo "SIGNING_KEYSTORE_CREATED=$output"
keytool -list -v -keystore "$output" -storetype PKCS12 \
  -storepass "$DF_IAM_SIGNING_STORE_PASSWORD" \
  -alias "${DF_IAM_SIGNING_KEY_ALIAS:-dataset-identity}" |
  awk -F': ' '/SHA256:/ {print "CERTIFICATE_SHA256=" $2}'
