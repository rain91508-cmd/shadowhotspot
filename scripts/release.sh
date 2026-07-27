#!/usr/bin/env bash
#
# Release flow for shadowhotspot.
#
# Steps:
#   1. Resolve a version (arg > $VERSION > VERSION file > latest git tag).
#   2. Ensure dist/ binaries are present (download them if missing).
#   3. Optionally build the companion Android app APK (BUILD_ANDROID=1).
#   4. Package a self-contained release tarball:
#          releases/shadowhotspot-<version>.tar.xz
#      containing configs, host scripts, on-device scripts and the binaries.
#   5. Create (locally) a git tag v<version> if it does not already exist.
#   6. Optionally publish a GitHub Release + upload the tarball (PUBLISH=1, needs `gh`).
#
# Usage:
#   ./scripts/release.sh 0.1.0                 # build tarball + tag
#   PUBLISH=1 ./scripts/release.sh 0.1.0       # also publish to GitHub
#   BUILD_ANDROID=1 ./scripts/release.sh 0.1.0 # also build the APK
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# --- resolve version ---------------------------------------------------------
VERSION="${1:-${VERSION:-}}"
if [ -z "$VERSION" ] && [ -f VERSION ]; then
    VERSION="$(tr -d '[:space:]' < VERSION)"
fi
if [ -z "$VERSION" ]; then
    VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
fi
if [ -z "$VERSION" ]; then
    echo "!! No version given. Usage: $0 <version>  (e.g. 0.1.0)" >&2
    exit 1
fi
VERSION="${VERSION#v}"   # strip a leading v if present

RELEASE_NAME="shadowhotspot-${VERSION}"
OUT_DIR="$ROOT_DIR/releases"
TARBALL="$OUT_DIR/${RELEASE_NAME}.tar.xz"
TAG="v${VERSION}"

echo ">> Release version: $VERSION  (tag $TAG)"

# --- ensure binaries ---------------------------------------------------------
if [ ! -x "$ROOT_DIR/dist/ssserver" ]; then
    echo ">> dist binaries missing — running 00-download-binaries.sh"
    "$ROOT_DIR/scripts/00-download-binaries.sh"
fi

# --- optional Android build --------------------------------------------------
ANDROID_APK=""
if [ "${BUILD_ANDROID:-0}" = "1" ]; then
    if [ -x "$ROOT_DIR/android-app/gradlew" ]; then
        echo ">> Building Android app (assembleRelease)"
        ( cd "$ROOT_DIR/android-app" && ./gradlew assembleRelease )
        ANDROID_APK="$(find "$ROOT_DIR/android-app/app/build/outputs" -name '*.apk' | head -n1 || true)"
        [ -n "$ANDROID_APK" ] && echo ">> APK: $ANDROID_APK"
    else
        echo "!! android-app/gradlew not found — skipping Android build"
    fi
fi

# --- package -----------------------------------------------------------------
mkdir -p "$OUT_DIR"
WORK="$(mktemp -d)"
PKG="$WORK/$RELEASE_NAME"
mkdir -p "$PKG"

cp -r "$ROOT_DIR/scripts" "$PKG/scripts"
cp -r "$ROOT_DIR/config"  "$PKG/config"
cp  "$ROOT_DIR/README.md" "$PKG/README.md"
[ -f "$ROOT_DIR/client/README.md" ] && cp "$ROOT_DIR/client/README.md" "$PKG/client-README.md"

# ship the binaries we actually use
for b in ssserver sslocal ssmanager ssservice ssurl; do
    [ -x "$ROOT_DIR/dist/$b" ] && cp "$ROOT_DIR/dist/$b" "$PKG/"
done
[ -f "$ROOT_DIR/dist/ss.tar.xz" ] && cp "$ROOT_DIR/dist/ss.tar.xz" "$PKG/"

# ship the APK if we built one
if [ -n "$ANDROID_APK" ]; then
    mkdir -p "$PKG/android-app"
    cp "$ANDROID_APK" "$PKG/android-app/"
fi

echo ">> Packaging $TARBALL"
tar -C "$WORK" -cJf "$TARBALL" "$RELEASE_NAME"
echo ">> Built: $TARBALL ($(du -h "$TARBALL" | cut -f1))"

# --- tag ---------------------------------------------------------------------
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo ">> Tag $TAG already exists — leaving it unchanged"
else
    echo ">> Creating tag $TAG"
    git tag -a "$TAG" -m "Release $TAG"
fi

# --- publish -----------------------------------------------------------------
if command -v gh >/dev/null 2>&1 && [ "${PUBLISH:-0}" = "1" ]; then
    echo ">> Publishing GitHub release $TAG"
    gh release create "$TAG" "$TARBALL" \
        --title "shadowhotspot $TAG" \
        --notes "Automated release ${RELEASE_NAME}." \
        --verify-tag
    echo ">> Published: https://github.com/${GITHUB_REPO:-rain91508-cmd/shadowhotspot}/releases/tag/$TAG"
else
    echo ">> Skipped GitHub publish (install 'gh' and set PUBLISH=1 to publish)."
fi

echo ">> Done. Push the tag with: git push origin $TAG"
