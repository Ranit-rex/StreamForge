#!/usr/bin/env sh
# StreamForge Gradle bootstrap. This intentionally works without gradle-wrapper.jar,
# so project ZIP builders can build the repository even when the binary wrapper is omitted.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.13
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_ROOT="$GRADLE_USER_HOME/wrapper/dists/streamforge-gradle-$GRADLE_VERSION"
GRADLE_HOME="$DIST_ROOT/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$DIST_ROOT"
  ZIP="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    else
      echo "Gradle bootstrap requires curl or wget." >&2
      exit 1
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$DIST_ROOT"
  else
    echo "Gradle bootstrap requires unzip." >&2
    exit 1
  fi
fi
exec "$GRADLE_HOME/bin/gradle" -p "$APP_HOME" "$@"
