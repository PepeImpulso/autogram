#!/usr/bin/env bash
# Builds the signed Android App Bundle intended for Google Play.
#
# Requires the same local.properties and keystore.properties files as the
# GitHub release build. Google Play must receive the existing signing key on
# first enrollment so installations from GitHub remain upgrade-compatible.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

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
    exit 1
fi

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
    echo "ERROR: no JDK 17 found." >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew --no-daemon :app:bundlePlayRelease

AAB="app/build/outputs/bundle/playRelease/app-play-release.aab"
if [[ ! -f "$AAB" ]]; then
    echo "ERROR: build succeeded but AAB not found at $AAB" >&2
    exit 1
fi

MERGED_MANIFEST="app/build/intermediates/merged_manifests/playRelease/processPlayReleaseManifest/AndroidManifest.xml"
AUTO_DESCRIPTOR="app/build/intermediates/packaged_res/playRelease/packagePlayReleaseResources/xml/automotive_app_desc.xml"
if [[ ! -f "$MERGED_MANIFEST" || ! -f "$AUTO_DESCRIPTOR" ]]; then
    echo "ERROR: Play manifest verification inputs were not generated." >&2
    exit 1
fi

for forbidden in \
    REQUEST_INSTALL_PACKAGES \
    AutogramCarAppService \
    minCarApiLevel \
    StoreScreenshotActivity \
    SentryInitProvider \
    SentryPerformanceProvider \
    io.sentry; do
    if grep -q "$forbidden" "$MERGED_MANIFEST"; then
        echo "ERROR: forbidden Play manifest entry found: $forbidden" >&2
        exit 1
    fi
done

if jar tf "$AAB" | grep -qi sentry; then
    echo "ERROR: Sentry artifact found in the Play bundle." >&2
    exit 1
fi

if ! grep -q 'name="notification"' "$AUTO_DESCRIPTOR"; then
    echo "ERROR: Android Auto notification capability is missing." >&2
    exit 1
fi
for forbidden in 'name="template"' 'name="media"'; do
    if grep -q "$forbidden" "$AUTO_DESCRIPTOR"; then
        echo "ERROR: production-ineligible Android Auto capability found: $forbidden" >&2
        exit 1
    fi
done

VERSION=$(grep -E '^\s*versionName' app/build.gradle.kts | head -1 | sed -E 's/.*"(.+)".*/\1/')
echo
echo "[build] Google Play bundle ready"
echo "  version : $VERSION"
echo "  bundle  : $AAB"
echo "  Android Auto: notification messaging only"
echo "  crash reporting: no Sentry artifacts"
