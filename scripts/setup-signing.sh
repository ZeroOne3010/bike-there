#!/usr/bin/env bash
set -euo pipefail
umask 077

repo=ZeroOne3010/bike-there
backup_dir=${1:-"$HOME/.bike-there-signing"}
keystore="$backup_dir/bike-there.p12"
passfile="$backup_dir/password"

gh auth status --hostname github.com >/dev/null
configured_fingerprint=$(gh variable list --repo "$repo" --json name,value \
    --jq '.[] | select(.name == "ANDROID_SIGNING_CERT_SHA256") | .value')
configured_secrets=$(gh secret list --repo "$repo" --json name --jq '.[].name')

if [[ -z "$configured_fingerprint" && "$configured_secrets" == *ANDROID_KEYSTORE* ]]; then
    echo "Signing secrets already exist without a certificate fingerprint. Verify the original key before changing them." >&2
    exit 1
fi
if [[ ! -f "$keystore" && -n "$configured_fingerprint" ]]; then
    echo "Signing is already configured. Run this with the original signing backup directory." >&2
    exit 1
fi
if [[ -e "$keystore" || -e "$passfile" ]]; then
    if [[ ! -f "$keystore" || ! -f "$passfile" ]]; then
        echo "A signing file is missing from $backup_dir. Restore it from your backup." >&2
        exit 1
    fi
else
    mkdir -p "$backup_dir"
    staging=$(mktemp -d "$backup_dir/.new.XXXXXXXX")
    trap 'rm -rf "$staging"' EXIT
    openssl rand -hex 32 > "$staging/password"
    openssl req -x509 -newkey rsa:4096 -sha256 -days 12000 -subj '/CN=Bike There' \
        -passout "file:$staging/password" -keyout "$staging/key.pem" -out "$staging/cert.pem"
    openssl pkcs12 -export -name bike-there -macalg sha256 \
        -inkey "$staging/key.pem" -in "$staging/cert.pem" -out "$staging/bike-there.p12" \
        -passin fd:3 -passout "file:$staging/password" 3< "$staging/password"
    rm "$staging/key.pem" "$staging/cert.pem"
    mv "$staging/password" "$passfile"
    mv "$staging/bike-there.p12" "$keystore"
    rmdir "$staging"
    trap - EXIT
fi

fingerprint=$(openssl pkcs12 -in "$keystore" -passin "file:$passfile" -clcerts -nokeys \
    | openssl x509 -outform der | openssl dgst -sha256 -r | awk '{print $1}')
if [[ -n "$configured_fingerprint" && "$configured_fingerprint" != "$fingerprint" ]]; then
    echo "This key does not match the project's configured signing certificate." >&2
    exit 1
fi
gh variable set ANDROID_SIGNING_CERT_SHA256 --repo "$repo" --body "$fingerprint"
base64 < "$keystore" | tr -d '\r\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
tr -d '\r\n' < "$passfile" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf 'Signing configured. Keep a private backup of both files in %s.\n' "$backup_dir"
