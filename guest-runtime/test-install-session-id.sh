#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST=$(mktemp -d "${TMPDIR:-$HOME}/install-session-id.XXXXXX")
trap 'rm -rf "$DEST"' EXIT HUP INT TERM
javac --release 8 -d "$DEST" \
    "$ROOT/guest-runtime/src/main/java/me/kanadex/geometryos/guestruntime/InstallSessionId.java" \
    "$ROOT/guest-runtime/test/InstallSessionIdTest.java"
java -cp "$DEST" me.kanadex.geometryos.guestruntime.InstallSessionIdTest