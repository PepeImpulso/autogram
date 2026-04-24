#!/usr/bin/env bash
# Cuts a new Autogram release end-to-end.
#
# Two modes:
#   1. CI mode (DEFAULT) — zero local-signing required:
#        - push current master + tag vX.Y.Z to PepeImpulso/autogram
#        - the Release APK workflow signs via KEYSTORE_BASE64 secret
#        - cross-repo publishes to PepeImpulso/autogram-releases using RELEASES_REPO_PAT
#        Use: scripts/publish_autogram_release.sh 1.2.3
#
#   2. Manual mode (--manual) — requires local keystore:
#        - runs scripts/build_autogram_release.sh to produce signed APK
#        - gh release create directly on PepeImpulso/autogram-releases
#        - pushes a source tag by default; pass --no-tag to skip it
#        Use: scripts/publish_autogram_release.sh --manual 1.2.3
#
# Either way the app's in-app updater (CheckForUpdatesUseCase) will detect the
# new version within 6 h of the next app open, or immediately when the user
# taps "Buscar actualizaciones" in Ajustes.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE="ci"
PUSH_TAG=1
NOTES_ARG=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --manual) MODE="manual"; shift ;;
        --no-tag) PUSH_TAG=0; shift ;;
        --tag)    PUSH_TAG=1; shift ;;
        --notes)  NOTES_ARG="$2"; shift 2 ;;
        -h|--help)
            grep -E '^# ' "${BASH_SOURCE[0]}" | sed 's/^# //'
            exit 0 ;;
        *) VERSION="$1"; shift ;;
    esac
done

if [[ -z "${VERSION:-}" ]]; then
    echo "Usage: $0 [--manual] [--no-tag] <version e.g. 1.2.3>" >&2
    exit 2
fi

TAG="v$VERSION"

# --- Sanity checks --------------------------------------------------------
if ! command -v gh >/dev/null; then
    echo "ERROR: gh CLI not found in PATH." >&2
    exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
    echo "ERROR: gh CLI not authenticated. Run: gh auth login" >&2
    exit 1
fi

# Require the version in build.gradle.kts to match what we're tagging, so the
# APK inside ships with the expected BuildConfig.VERSION_NAME.
IN_GRADLE=$(grep -E '^\s*versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"(.+)".*/\1/')
if [[ "$IN_GRADLE" != "$VERSION" ]]; then
    echo "ERROR: versionName in app/build.gradle.kts is '$IN_GRADLE', but you asked to release '$VERSION'." >&2
    echo "Bump versionCode + versionName in app/build.gradle.kts and commit before retrying." >&2
    exit 1
fi

# Tag must not already exist.
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "ERROR: tag $TAG already exists locally." >&2
    exit 1
fi
if gh api "repos/PepeImpulso/autogram/git/refs/tags/$TAG" --silent 2>/dev/null; then
    echo "ERROR: tag $TAG already exists on origin." >&2
    exit 1
fi

BRANCH=$(git branch --show-current)
if [[ "$BRANCH" != "master" ]]; then
    echo "WARN: current branch is '$BRANCH', not master. Continue? (y/N)" >&2
    read -r answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || exit 1
fi

echo "[publish] version=$VERSION  mode=$MODE  tag=$TAG  branch=$BRANCH"

# --- Mode: CI -------------------------------------------------------------
if [[ "$MODE" == "ci" ]]; then
    # Make sure local is in sync with origin so the CI build uses the commit we think.
    git push origin "$BRANCH"
    git tag -a "$TAG" -m "$TAG"
    git push origin "$TAG"

    echo
    echo "[publish] Tag pushed. CI workflow 'Release APK' will build + sign + publish."
    echo "  Watch:   gh run watch \$(gh run list --workflow='Release APK' --limit 1 --json databaseId --jq '.[0].databaseId') --exit-status"
    echo "  Release: https://github.com/PepeImpulso/autogram-releases/releases/tag/$TAG"
    echo
    exit 0
fi

# --- Mode: Manual ---------------------------------------------------------
# Build signed APK locally; upload to autogram-releases directly.
"${REPO_ROOT}/scripts/build_autogram_release.sh"
STAGED="${AUTOGRAM_OUT_DIR:-/c/tmp}/autogram-${VERSION}.apk"
if [[ ! -f "$STAGED" ]]; then
    echo "ERROR: staged APK not found at $STAGED" >&2
    exit 1
fi

if [[ "$PUSH_TAG" == "1" ]]; then
    git push origin "$BRANCH"
    git tag -a "$TAG" -m "$TAG"
    git push origin "$TAG"
fi

# Publish (or replace) the release on the public repo.
# Release-notes discovery order:
#   1. explicit --notes <file>
#   2. docs/release-notes/<TAG>.md in-repo
#   3. fallback stub with build info
TITLE="$TAG"
NOTES_FILE=""
CLEANUP_NOTES=0
if [[ -n "$NOTES_ARG" ]]; then
    if [[ ! -f "$NOTES_ARG" ]]; then
        echo "ERROR: --notes file not found: $NOTES_ARG" >&2
        exit 1
    fi
    NOTES_FILE="$NOTES_ARG"
elif [[ -f "$REPO_ROOT/docs/release-notes/$TAG.md" ]]; then
    NOTES_FILE="$REPO_ROOT/docs/release-notes/$TAG.md"
    echo "[publish] using notes from docs/release-notes/$TAG.md"
else
    NOTES_FILE="$(mktemp)"
    CLEANUP_NOTES=1
    cat > "$NOTES_FILE" <<EOF
Autogram $TAG

Build: local signed ($(date -Iseconds)).
Source commit: $(git rev-parse --short HEAD).

(No release notes file found at docs/release-notes/$TAG.md — consider
adding one in NitrOdoo format for future users.)
EOF
fi

if gh release view "$TAG" --repo PepeImpulso/autogram-releases >/dev/null 2>&1; then
    echo "[publish] release $TAG already exists — uploading APK as asset update + refreshing notes"
    gh release upload "$TAG" "$STAGED" --repo PepeImpulso/autogram-releases --clobber
    gh release edit "$TAG" --repo PepeImpulso/autogram-releases --notes-file "$NOTES_FILE"
else
    gh release create "$TAG" "$STAGED" \
        --repo PepeImpulso/autogram-releases \
        --title "$TITLE" \
        --notes-file "$NOTES_FILE"
fi

[[ "$CLEANUP_NOTES" == "1" ]] && rm -f "$NOTES_FILE"

echo
echo "[publish] OK"
echo "  URL: https://github.com/PepeImpulso/autogram-releases/releases/tag/$TAG"
