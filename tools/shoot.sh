#!/usr/bin/env bash
# Photograph Neue Master Tool without opening a window: one page per run.
#
#   tools/shoot.sh --page=builder --groups=true --name=b     # shots/b.png
#   tools/shoot.sh --page=builder --theme=ink
#   tools/shoot.sh --page=decks --save=true --default=true
#   tools/shoot.sh --page=builder --mouse="right@0.3,0.25"   # a real gesture
#
# Shots land in shots/ at the repository root unless --out says otherwise.
# See app/studio/src/jvmMain/.../NeueStudio.kt for every flag. `--neue`, which
# used to choose Neue over the classic tablet's play stage, is still accepted.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The studio needs :builder, which needs the Android SDK even for the desktop target.
if [[ ! -f "$repo/app/local.properties" && -d "${ANDROID_HOME:-/nonexistent}" ]]; then
  echo "sdk.dir=$ANDROID_HOME" > "$repo/app/local.properties"
fi

task=":studio:shootNeue"
args="$(printf '%s ' "$@" | sed 's/--neue //')"
if [[ "$args" != *"--out="* ]]; then
  mkdir -p "$repo/shots"
  args="--out=$repo/shots $args"
fi

cd "$repo/app"
exec ./gradlew --quiet -Pmastertool.android=true -Pmastertool.studio=true \
  "$task" -Pshot.args="$args"
