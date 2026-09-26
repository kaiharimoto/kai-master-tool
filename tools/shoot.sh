#!/usr/bin/env bash
# Draw the play stage to PNG files without opening a window.
#
#   tools/shoot.sh                                  # the default contact sheet
#   tools/shoot.sh --shots=desk-night-seated        # one named shot
#   tools/shoot.sh --shots=desk-night-pov           # the seat the stage opens at
#   tools/shoot.sh --pitch=80 --yaw=45              # aim the whole run
#   tools/shoot.sh --tap=0.27,0.70                  # put a finger on the glass
#   tools/shoot.sh --drag=0.5,0.5:0.7,0.5           # and drag it: on felt, an orbit
#   tools/shoot.sh --out=/tmp/x --density=2         # tablet metrics
#
# A shot name carries a seat: overhead, table, seated, pov. --pitch/--yaw aim
# the run instead, anywhere in the envelope, and a run that is aimed ignores the
# seat in every name — because a tuning carrying a camera *is* the camera.
#
# Shots land in shots/ at the repository root unless --out says otherwise.
# See app/studio/src/jvmMain/.../Studio.kt for the whole flag list.
# --tune=x.json replays a tuning exported from the in-app panel.
#
# --neue photographs Neue Master Tool, the desktop builder, instead: one page
# per run, e.g. `tools/shoot.sh --neue --page=builder --theme=ink --name=b-ink`.
# See app/studio/src/jvmMain/.../NeueStudio.kt for its flags.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The studio needs :ui, which needs the Android SDK even for the desktop target.
if [[ ! -f "$repo/app/local.properties" && -d "${ANDROID_HOME:-/nonexistent}" ]]; then
  echo "sdk.dir=$ANDROID_HOME" > "$repo/app/local.properties"
fi

task=":studio:shoot"
if [[ " $* " == *" --neue "* ]]; then
  task=":studio:shootNeue"
fi
args="$(printf '%s ' "$@" | sed 's/--neue //')"
if [[ "$args" != *"--out="* ]]; then
  mkdir -p "$repo/shots"
  args="--out=$repo/shots $args"
fi

cd "$repo/app"
exec ./gradlew --quiet -Pmastertool.android=true -Pmastertool.studio=true \
  "$task" -Pshot.args="$args"
