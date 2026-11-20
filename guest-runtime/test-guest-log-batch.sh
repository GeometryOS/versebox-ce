#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DEST=$(mktemp -d "${TMPDIR:-$HOME}/guest-log-batch.XXXXXX")
trap 'rm -rf "$DEST"' EXIT HUP INT TERM
javac --release 8 -d "$DEST" \
    "$ROOT/guest-runtime/src/main/java/me/kanadex/geometryos/guestruntime/GuestLogBatch.java" \
    "$ROOT/guest-runtime/test/GuestLogBatchTest.java"
java -cp "$DEST" me.kanadex.geometryos.guestruntime.GuestLogBatchTest