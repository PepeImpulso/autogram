#!/usr/bin/env bash
# One-time setup: copy the release secrets into the `release` environment of
# PepeImpulso/autogram so the "Release APK" workflow can build and publish
# on a tag push (and cloud sessions, which cannot run
# publish_autogram_release.sh, can release by pushing a tag).
#
# Values come from this machine's local.properties, keystore.properties and
# keystore file; nothing is printed. RELEASES_REPO_PAT is not stored
# locally: export it before running, or paste it at the prompt. It needs
# `contents: write` on PepeImpulso/autogram-releases (the same token the old
# autogram-private repo used, or a new fine-grained one).
#
# Usage: bash scripts/set_release_secrets.sh
set -euo pipefail

REPO="PepeImpulso/autogram"
ENV_NAME="release"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

prop() { # prop <file> <key>
    grep -E "^$2=" "$1" | head -1 | cut -d= -f2- | tr -d '\r'
}

set_secret() { # set_secret <name> <value>
    if [[ -z "$2" ]]; then
        echo "ERROR: $1 is empty; fix the local file and rerun." >&2
        exit 1
    fi
    printf '%s' "$2" | gh secret set "$1" --repo "$REPO" --env "$ENV_NAME"
    echo "  set $1"
}

LOCAL="$ROOT/local.properties"
KEYS="$ROOT/keystore.properties"
[[ -f "$LOCAL" && -f "$KEYS" ]] || { echo "ERROR: local.properties / keystore.properties not found in $ROOT" >&2; exit 1; }

STORE_FILE="$(prop "$KEYS" storeFile)"
[[ "$STORE_FILE" = /* || "$STORE_FILE" =~ ^[A-Za-z]: ]] || STORE_FILE="$ROOT/app/$STORE_FILE"
[[ -f "$STORE_FILE" ]] || { echo "ERROR: keystore not found: $STORE_FILE" >&2; exit 1; }

PAT="${RELEASES_REPO_PAT:-}"
if [[ -z "$PAT" ]]; then
    read -r -s -p "RELEASES_REPO_PAT (input hidden): " PAT
    echo
fi

echo "Setting secrets on $REPO (environment: $ENV_NAME)"
set_secret TELEGRAM_API_ID "$(prop "$LOCAL" telegram.apiId)"
set_secret TELEGRAM_API_HASH "$(prop "$LOCAL" telegram.apiHash)"
set_secret SENTRY_DSN "$(prop "$LOCAL" sentry.dsn)"
set_secret KEYSTORE_BASE64 "$(base64 -w0 "$STORE_FILE")"
set_secret KEYSTORE_PASSWORD "$(prop "$KEYS" storePassword)"
set_secret KEY_ALIAS "$(prop "$KEYS" keyAlias)"
set_secret KEY_PASSWORD "$(prop "$KEYS" keyPassword)"
set_secret RELEASES_REPO_PAT "$PAT"
echo "Done. Tag pushes now build and publish in CI."
