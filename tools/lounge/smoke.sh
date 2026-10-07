#!/usr/bin/env bash
# The Lounge end to end (docs/LOUNGE.md): kai's door opened with the built page (app/guest/build/lounge), kai seated
# and ready in a room, and a friend's browser — headless Chromium — through the passcode, a name, a deck pasted as
# ydke://, a line said in the room, the seat, the deck chosen, the opening throw, and the socket cut mid-duel. It passes
# when kai's computer heard the line, saw the browser's dice, and had the page come back to its seat by itself.
# Screenshots land in shots/lounge. Needs Playwright's Chromium (`npx playwright install chromium`).
set -euo pipefail
# The clicks are where the page draws things at 1280×800: a row added above the seats (the room's match line) moves them.
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
page="$root/app/guest/build/lounge"
shots="$root/shots/lounge"
log="$shots/harness.log"
mkdir -p "$shots"
test -f "$page/index.html" || { echo "No page at $page: run ./gradlew :guest:guestBundle first"; exit 1; }

(cd "$root/app" && LOUNGE_PAGE="$page" LOUNGE_HOLD_MS=600000 ./gradlew -Pmastertool.android=true :neue:jvmTest \
  --tests "com.kaiharimoto.neue.lounge.LoungeBrowserHarness" --rerun -i > "$log" 2>&1) &
harness=$!
trap 'kill $harness 2>/dev/null || true' EXIT
for _ in $(seq 1 600); do
  grep -q "open at" "$log" && break
  kill -0 $harness 2>/dev/null || { tail -40 "$log"; echo "The harness ended before the door opened"; exit 1; }
  sleep 1
done
grep -q "open at" "$log" || { echo "The door never opened"; exit 1; }

# Forty cards of the harness's six, as a ydke:// code.
ydke="$(python3 -c "
import base64, struct
ids = [14558127, 23434538, 89631139, 46986414, 55144522, 44095762]
m = [i for i in ids for _ in range(3)]; m = m + m + m[:4]
print('ydke://' + base64.b64encode(b''.join(struct.pack('<I', i) for i in m)).decode() + '!!!')")"

cd "$shots"
rm -rf profile
node "$here/walk.js" http://127.0.0.1:47391/ . "wait:15000;;shot:1-passcode;;type:labrynth-night;;key:Enter;;wait:8000;;shot:2-name;;\
type:Rin;;key:Enter;;wait:8000;;shot:3-lobby;;click:1232,24;;wait:3000;;click:234,200;;type:$ydke;;click:477,200;;wait:3000;;shot:4-decks;;\
click:1169,24;;wait:2000;;click:1214,177;;wait:2500;;click:234,435;;type:hello from the rail;;key:Enter;;wait:2000;;\
click:1104,300;;wait:2500;;shot:5-seated;;click:1219,382;;wait:10000;;shot:6-table;;\
click:668,635;;wait:10000;;shot:7-thrown;;cut:;;wait:300;;shot:8-dropped;;wait:15000;;shot:9-back"

fail() { grep "\[lounge\]" "$log" | tail -20; echo "$1"; exit 1; }
grep -q "said in the room: Rin: hello from the rail" "$log" || fail "kai never heard the room's chat"
grep -q "the browser threw" "$log" || fail "kai's computer never saw the browser's throw"
grep -q "Rin came back to their seat" "$log" || fail "the page did not come back by itself after its socket was cut"
echo "The Lounge works end to end: $(grep -m1 'the browser threw' "$log" | sed 's/^ *//'), the room's chat heard, and back after a cut" 
