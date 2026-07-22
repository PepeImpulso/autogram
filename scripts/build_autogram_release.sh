#!/usr/bin/env bash
# Builds a signed release APK of Autogram locally.
#
# Requires the signing material to be present on this machine:
#   - <repo>/keystore.properties   (gitignored; contains storeFile/storePassword/keyAlias/keyPassword)
#   - the .jks file referenced by keystore.properties → storeFile
#   - <repo>/local.properties      (gitignored; sdk.dir + telegram.apiId + telegram.apiHash)
#
# Output: app/build/outputs/apk/github/release/app-github-release.apk
# The script also copies it to /c/tmp/autogram-<version>.apk (git-bash path) or $TMP for convenience.
#
# If you only need to cut a release without having the keystore locally, push a `vX.Y.Z` tag to
# PepeImpulso/autogram instead — the Release APK workflow builds + signs + publishes via CI.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# --- Prereqs ---------------------------------------------------------------
missing=()
for f in keystore.properties local.properties gradlew; do
    [[ -f "$f" ]] || missing+=("$f")
done
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "ERROR: missing required files: ${missing[*]}" >&2
    echo "See docs/RELEASE_OPS.md for expected content and locations." >&2
    exit 1
fi

storeFile=$(awk -F= '/^storeFile=/{print $2}' keystore.properties | tr -d '\r')
if [[ -z "$storeFile" || ! -f "$storeFile" ]]; then
    echo "ERROR: keystore file not found at: $storeFile" >&2
    echo "Update keystore.properties → storeFile to a valid path." >&2
    exit 1
fi

# JDK 17 is required by the Android Gradle Plugin we use. Respect a caller's
# JAVA_HOME if set and valid; otherwise fall back to Microsoft JDK 17 on Windows.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    for candidate in \
        "/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot" \
        "/c/Program Files/Eclipse Adoptium/jdk-17" \
        "/c/Program Files/Android/Android Studio/jbr"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "ERROR: no JDK 17 found. Install Microsoft JDK 17 or point JAVA_HOME at one." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# --- Build -----------------------------------------------------------------
echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] versionName=$(grep -E '^\s*versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"(.+)".*/\1/')"
./gradlew --no-daemon :app:assembleGithubRelease

APK="app/build/outputs/apk/github/release/app-github-release.apk"
if [[ ! -f "$APK" ]]; then
    echo "ERROR: build succeeded but APK not found at $APK" >&2
    exit 1
fi

# --- Stage with a versioned name ------------------------------------------
VERSION=$(grep -E '^\s*versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"(.+)".*/\1/')
OUT_DIR="${AUTOGRAM_OUT_DIR:-/c/tmp}"
mkdir -p "$OUT_DIR"
STAGED="$OUT_DIR/autogram-${VERSION}.apk"
cp "$APK" "$STAGED"

echo
echo "[build] OK"
echo "  version : $VERSION"
echo "  raw     : $APK"
echo "  staged  : $STAGED"
echo
echo "Next: run scripts/publish_autogram_release.sh to tag + upload,"
echo "or push a vX.Y.Z tag to PepeImpulso/autogram and let CI do it."
